/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package tac
package fpcf
package analyses
package pointsto

import scala.collection.mutable.ArrayBuffer

import org.opalj.collection.immutable.UIDSet
import org.opalj.fpcf.Entity
import org.opalj.fpcf.EPK
import org.opalj.fpcf.EPS
import org.opalj.fpcf.InterimEUBP
import org.opalj.fpcf.InterimPartialResult
import org.opalj.fpcf.PartialResult
import org.opalj.fpcf.ProperPropertyComputationResult
import org.opalj.fpcf.PropertyBounds
import org.opalj.fpcf.PropertyKind
import org.opalj.fpcf.PropertyStore
import org.opalj.fpcf.Results
import org.opalj.fpcf.SomeEPS
import org.opalj.fpcf.UBP
import org.opalj.fpcf.UBPS
import org.opalj.value.IsReferenceValue
import org.opalj.value.IsSArrayValue
import org.opalj.value.ValueInformation
import org.opalj.br.DeclaredMethod
import org.opalj.br.analyses.SomeProject
import org.opalj.br.fpcf.FPCFAnalysis
import org.opalj.br.fpcf.FPCFTriggeredAnalysisScheduler
import org.opalj.br.fpcf.cg.properties.Callers
import org.opalj.br.fpcf.pointsto.properties.PointsTo
import org.opalj.br.DefinedMethod
import org.opalj.br.Method
import org.opalj.br.fpcf.cg.properties.Callees
import org.opalj.br.ObjectType
import org.opalj.tac.fpcf.analyses.cg.ReachableMethodAnalysis
import org.opalj.tac.fpcf.properties.TACAI
import org.opalj.tac.fpcf.analyses.cg.valueOriginsOfPCs

/**
 * An andersen-style points-to analysis, i.e. points-to sets are modeled as subsets.
 * The analysis is field-based, array-based and context-insensitive.
 * As the analysis is build on top of the [[org.opalj.tac.TACAI]], it is (implicitly)
 * flow-sensitive (which is not the case for pure andersen-style).
 *
 * Points-to sets may be attached to the following entities:
 *  - [[org.opalj.tac.common.DefinitionSite]] for local variables.
 *  - [[org.opalj.br.Field]] for fields (either static of instance)
 *  - [[org.opalj.br.DeclaredMethod]] for the points-to set of the return values.
 *  - [[org.opalj.br.analyses.VirtualFormalParameter]] for the parameters of a method.
 *  - [[org.opalj.br.ObjectType]] for the element type of an array.
 *
 * @author Florian Kuebler
 */
class AndersenStylePointsToAnalysis private[analyses] (
        final val project: SomeProject
) extends ReachableMethodAnalysis with AbstractPointsToBasedAnalysis[Entity] {

    override def processMethod(
        definedMethod: DefinedMethod, tacEP: EPS[Method, TACAI]
    ): ProperPropertyComputationResult = {
        doProcessMethod(new PointsToState(definedMethod, tacEP))
    }

    // maps the points-to set of actual parameters (including *this*) the the formal parameters
    private[this] def handleCall(
        call: Call[DUVar[ValueInformation]], pc: Int
    )(implicit state: PointsToState): Unit = {
        val tac = state.tac
        val callees = state.callees(ps)
        for (target ← callees.directCallees(pc)) {
            val fps = formalParameters(target)

            if (fps != null) {
                val receiverOpt = call.receiverOption
                // handle receiver for non static methods
                if (receiverOpt.isDefined) {
                    val fp = fps(0)
                    state.setOrUpdatePointsToSet(
                        fp, currentPointsTo(fp, receiverOpt.get.asVar.definedBy)
                    )
                }

                // in case of signature polymorphic methods, we give up
                if (call.params.size == target.descriptor.parametersCount) {
                    // handle params
                    for (i ← 0 until target.descriptor.parametersCount) {
                        val fp = fps(i + 1)
                        state.setOrUpdatePointsToSet(
                            fp, currentPointsTo(fp, call.params(i).asVar.definedBy)
                        )
                    }
                } else {
                    // todo: it should not be needed to mark it as incomplete
                }
            } else {
                state.addIncompletePointsToInfo(pc)
            }
        }

        // todo: reduce code duplication
        for (target ← callees.indirectCallees(pc)) {
            val fps = formalParameters(target)

            if (fps != null) {

                // handle receiver for non static methods
                val receiverOpt = callees.indirectCallReceiver(pc, target)
                if (receiverOpt.isDefined) {
                    val fp = fps(0)
                    state.setOrUpdatePointsToSet(
                        fp,
                        currentPointsTo(
                            fp, valueOriginsOfPCs(receiverOpt.get._2, tac.pcToIndex)
                        )
                    )
                } else {
                    // todo: distinguish between static methods and unavailable info
                }

                val indirectParams = callees.indirectCallParameters(pc, target)
                for (i ← 0 until target.descriptor.parametersCount) {
                    val fp = fps(i + 1)
                    val indirectParam = indirectParams(i)
                    if (indirectParam.isDefined) {
                        state.setOrUpdatePointsToSet(
                            fp,
                            currentPointsTo(
                                fp, valueOriginsOfPCs(indirectParam.get._2, tac.pcToIndex)
                            )
                        )
                    } else {
                        state.addIncompletePointsToInfo(pc)
                    }
                }
            } else {
                state.addIncompletePointsToInfo(pc)
            }
        }
    }

    private[this] def doProcessMethod(
        implicit
        state: PointsToState
    ): ProperPropertyComputationResult = {
        val tac = state.tac
        val method = state.method.definedMethod

        for (stmt ← tac.stmts) stmt match {
            case Assignment(pc, _, New(_, t)) ⇒
                val defSite = definitionSites(method, pc)
                state.setOrUpdatePointsToSet(defSite, UIDSet(t))

            case Assignment(pc, _, NewArray(_, _, tpe)) if tpe.elementType.isObjectType ⇒
                val defSite = definitionSites(method, pc)
                state.setOrUpdatePointsToSet(defSite, UIDSet(tpe.elementType.asObjectType))

            // that case should not happen
            case Assignment(pc, DVar(_: IsReferenceValue, _), UVar(_, defSites)) ⇒
                val defSiteObject = definitionSites(method, pc)
                state.setOrUpdatePointsToSet(
                    defSiteObject, currentPointsTo(defSiteObject, defSites)
                )

            case Assignment(pc, DVar(_: IsReferenceValue, _), GetField(_, declaringClass, name, fieldType, _)) ⇒
                val defSiteObject = definitionSites(method, pc)
                val fieldOpt = p.resolveFieldReference(declaringClass, name, fieldType)
                if (fieldOpt.isDefined) {
                    state.setOrUpdatePointsToSet(
                        defSiteObject,
                        currentPointsTo(defSiteObject, fieldOpt.get)
                    )
                } else {
                    state.addIncompletePointsToInfo(pc)
                }

            case Assignment(pc, DVar(_: IsReferenceValue, _), GetStatic(_, declaringClass, name, fieldType)) ⇒
                val defSiteObject = definitionSites(method, pc)
                val fieldOpt = p.resolveFieldReference(declaringClass, name, fieldType)
                if (fieldOpt.isDefined) {
                    state.setOrUpdatePointsToSet(defSiteObject, currentPointsTo(defSiteObject, fieldOpt.get))
                } else {
                    state.addIncompletePointsToInfo(pc)
                }

            case Assignment(pc, _, ArrayLoad(_, _, UVar(av: IsSArrayValue, _))) if av.theUpperTypeBound.elementType.isObjectType ⇒
                val defSiteObject = definitionSites(method, pc)
                val arrayBaseType = av.theUpperTypeBound.elementType
                state.setOrUpdatePointsToSet(
                    defSiteObject,
                    currentPointsTo(defSiteObject, arrayBaseType)
                )

            // TODO: Use handleCall here
            case Assignment(pc, targetVar, call: FunctionCall[DUVar[ValueInformation]]) ⇒
                val targets = state.callees(ps).callees(pc)
                val defSiteObject = definitionSites(method, pc)

                if (targetVar.value.isReferenceValue) {
                    var pointsToSet = UIDSet.empty[ObjectType]
                    for (target ← targets) {
                        pointsToSet ++= currentPointsTo(defSiteObject, target)
                    }

                    state.setOrUpdatePointsToSet(defSiteObject, pointsToSet)
                }

                handleCall(call, pc)

            case Assignment(pc, targetVar, _: Const) if targetVar.value.isReferenceValue ⇒
                val defSite = definitionSites(method, pc)
                state.setOrUpdatePointsToSet(defSite, UIDSet.empty)

            case Assignment(_, DVar(av: IsSArrayValue, _), _) if av.theUpperTypeBound.elementType.isObjectType ⇒
                throw new IllegalArgumentException(s"unexpected assignment: $stmt")

            case Assignment(_, DVar(rv: IsReferenceValue, _), _) if !rv.isInstanceOf[IsSArrayValue] ⇒
                throw new IllegalArgumentException(s"unexpected assignment: $stmt")

            case call: Call[_] ⇒
                handleCall(call.asInstanceOf[Call[DUVar[ValueInformation]]], call.pc)

            case ExprStmt(pc, call: Call[_]) ⇒
                handleCall(call.asInstanceOf[Call[DUVar[ValueInformation]]], pc)

            case ArrayStore(_, UVar(av: IsSArrayValue, _), _, UVar(_, defSites)) if av.theUpperTypeBound.elementType.isReferenceType ⇒
                val arrayBaseType = av.theUpperTypeBound.elementType
                state.setOrUpdatePointsToSet(
                    arrayBaseType, currentPointsTo(arrayBaseType, defSites)
                )

            case PutField(pc, declaringClass, name, fieldType: ObjectType, _, UVar(_, defSites)) ⇒
                val fieldOpt = p.resolveFieldReference(declaringClass, name, fieldType)
                if (fieldOpt.isDefined) {
                    state.setOrUpdatePointsToSet(
                        fieldOpt.get, currentPointsTo(fieldOpt.get, defSites)
                    )
                } else {
                    state.addIncompletePointsToInfo(pc)
                }

            case PutStatic(pc, declaringClass, name, fieldType: ObjectType, UVar(_, defSites)) ⇒
                val fieldOpt = p.resolveFieldReference(declaringClass, name, fieldType)
                if (fieldOpt.isDefined)
                    state.setOrUpdatePointsToSet(
                        fieldOpt.get, currentPointsTo(fieldOpt.get, defSites)
                    )
                else {
                    state.addIncompletePointsToInfo(pc)
                }

            case ReturnValue(_, UVar(_: IsReferenceValue, defSites)) ⇒
                state.setOrUpdatePointsToSet(
                    state.method, currentPointsTo(state.method, defSites)
                )

            case _ ⇒
        }

        // todo: we have to handle the exceptions that might be thrown by this method

        returnResult
    }

    /**
     * The continuation function. It handles updates for the methods tac, callees and for points-to
     * sets.
     */
    private[this] def c(state: PointsToState)(eps: SomeEPS): ProperPropertyComputationResult = {
        eps match {
            case UBP(tacai: TACAI) if tacai.tac.isDefined ⇒
                state.updateTACDependee(eps.asInstanceOf[EPS[Method, TACAI]])
                doProcessMethod(state)

            case UBP(_: TACAI) ⇒
                InterimPartialResult(Some(eps), c(state))

            case UBPS(pointsTo: PointsTo, isFinal) ⇒
                for (depender ← state.dependersOf(eps.e)) {
                    state.setOrUpdatePointsToSet(depender, pointsTo.types)
                }

                if (isFinal) {
                    state.removePointsToDependee(eps.e)
                } else {
                    state.updatePointsToDependency(eps.asInstanceOf[EPS[Entity, PointsTo]])
                }

                returnResult(state)

            case UBP(callees: Callees) ⇒
                // todo instead of rerunning the complete analysis, get new calls for all pcs only

                state.updateCalleesDependee(eps.asInstanceOf[EPS[DeclaredMethod, Callees]])
                doProcessMethod(state)
        }
    }

    /**
     * Constructs the [[org.opalj.fpcf.PropertyComputationResult]] associated with the state.
     */
    @inline private[this] def returnResult(
        implicit
        state: PointsToState
    ): ProperPropertyComputationResult = {
        val results = ArrayBuffer.empty[ProperPropertyComputationResult]
        if (state.hasOpenDependencies) results += InterimPartialResult(state.dependees, c(state))

        for ((e, pointsToSet) ← state.pointsToSetsIterator) {
            results += PartialResult[Entity, PointsTo](e, PointsTo.key, {

                case _: EPK[Entity, PointsTo] ⇒
                    Some(InterimEUBP(e, PointsTo(pointsToSet)))

                case UBP(ub) ⇒
                    // IMPROVE: only process new Types
                    val newPointsTo = ub.updated(pointsToSet)
                    if (newPointsTo.numElements != ub.numElements)
                        Some(InterimEUBP(e, newPointsTo))
                    else
                        None
            })
        }

        state.clearPointsToSet()

        Results(results)
    }
}

object AndersenStylePointsToAnalysisScheduler extends FPCFTriggeredAnalysisScheduler {
    override type InitializationData = Null

    override def uses: Set[PropertyBounds] = PropertyBounds.ubs(
        Callers,
        Callees,
        PointsTo,
        TACAI
    )

    override def derivesCollaboratively: Set[PropertyBounds] = Set(PropertyBounds.ub(PointsTo))

    override def derivesEagerly: Set[PropertyBounds] = Set.empty

    override def init(p: SomeProject, ps: PropertyStore): Null = {
        null
    }

    override def beforeSchedule(p: SomeProject, ps: PropertyStore): Unit = {}

    override def register(
        p: SomeProject, ps: PropertyStore, unused: Null
    ): AndersenStylePointsToAnalysis = {
        val analysis = new AndersenStylePointsToAnalysis(p)
        // register the analysis for initial values for callers (i.e. methods becoming reachable)
        ps.registerTriggeredComputation(Callers.key, analysis.analyze)
        analysis
    }

    override def afterPhaseScheduling(ps: PropertyStore, analysis: FPCFAnalysis): Unit = {}

    override def afterPhaseCompletion(
        p:        SomeProject,
        ps:       PropertyStore,
        analysis: FPCFAnalysis
    ): Unit = {}

    override def triggeredBy: PropertyKind = Callers
}