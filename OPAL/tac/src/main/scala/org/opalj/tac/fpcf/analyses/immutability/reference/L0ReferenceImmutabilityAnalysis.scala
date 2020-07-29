/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj.tac.fpcf.analyses.immutability.reference

import org.opalj.br.BooleanType
import org.opalj.br.ByteType
import org.opalj.br.ClassFile
import org.opalj.br.DeclaredMethod
import org.opalj.br.Field
import org.opalj.br.FloatType
import org.opalj.br.IntegerType
import org.opalj.br.Method
import org.opalj.br.PCs
import org.opalj.br.ShortType
import org.opalj.br.analyses.SomeProject
import org.opalj.br.fpcf.BasicFPCFEagerAnalysisScheduler
import org.opalj.br.fpcf.BasicFPCFLazyAnalysisScheduler
import org.opalj.br.fpcf.FPCFAnalysis
import org.opalj.br.fpcf.FPCFAnalysisScheduler
import org.opalj.br.fpcf.properties.AtMost
import org.opalj.br.fpcf.properties.EscapeInCallee
import org.opalj.br.fpcf.properties.EscapeProperty
import org.opalj.br.fpcf.properties.EscapeViaReturn
import org.opalj.br.fpcf.properties.FieldPrematurelyRead
import org.opalj.br.fpcf.properties.ImmutableReference
import org.opalj.br.fpcf.properties.LazyInitializedNotThreadSafeButDeterministicReference
import org.opalj.br.fpcf.properties.LazyInitializedNotThreadSafeOrNotDeterministicReference
import org.opalj.br.fpcf.properties.LazyInitializedThreadSafeReference
import org.opalj.br.fpcf.properties.MutableReference
import org.opalj.br.fpcf.properties.NoEscape
import org.opalj.br.fpcf.properties.Purity
import org.opalj.br.fpcf.properties.ReferenceImmutability
import org.opalj.br.fpcf.properties.cg.Callees
import org.opalj.fpcf.EOptionP
import org.opalj.fpcf.Entity
import org.opalj.fpcf.FinalEP
import org.opalj.fpcf.FinalP
import org.opalj.fpcf.InterimEP
import org.opalj.fpcf.InterimResult
import org.opalj.fpcf.InterimUBP
import org.opalj.fpcf.ProperPropertyComputationResult
import org.opalj.fpcf.PropertyBounds
import org.opalj.fpcf.PropertyComputationResult
import org.opalj.fpcf.PropertyStore
import org.opalj.fpcf.Result
import org.opalj.fpcf.SomeEPS
import org.opalj.tac.PutField
import org.opalj.tac.PutStatic
import org.opalj.tac.Stmt
import org.opalj.tac.TACMethodParameter
import org.opalj.tac.TACode
import org.opalj.tac.common.DefinitionSite
import org.opalj.tac.fpcf.properties.TACAI
import org.opalj.tac.SelfReferenceParameter
import scala.annotation.switch
import org.opalj.br.ReferenceType

/**
 *
 * Determines the immutability of the reference of a classes field.
 * In cases of fields with primitive types, we consider them also as a combination of fields and types
 * The field immutability will be determined in the FieldImmutabilityAnalysis.
 *
 * Examples:
 * class ... {
 * ...
 * final Object o;
 * int n;
 * ...
 * }
 *
 * In both cases we consider o and an as references with their respective types.
 * The combination of both is the field immutability.
 *
 *
 * @note Requires that the 3-address code's expressions are not deeply nested.
 *
 * @author Tobias Peter Roth
 * @author Dominik Helm
 * @author Florian Kübler
 * @author Michael Eichberg
 *
 */
class L0ReferenceImmutabilityAnalysis private[analyses] (val project: SomeProject)
    extends AbstractReferenceImmutabilityAnalysisLazyInitialization
    with AbstractReferenceImmutabilityAnalysis
    with FPCFAnalysis {

    def doDetermineReferenceImmutability(entity: Entity): PropertyComputationResult =
        entity match {
            case field: Field ⇒ determineReferenceImmutability(field)
            case _ ⇒
                val m = entity.getClass.getSimpleName+" is not an org.opalj.br.Field"
                throw new IllegalArgumentException(m)
        }

    /**
     * Analyzes the immutability fields references.
     *
     * This analysis is only ''soundy'' if the class file does not contain native methods.
     * If the analysis is schedulued using its companion object all class files with
     * native methods are filtered.
     */
    private[analyses] def determineReferenceImmutability(
        field: Field
    ): ProperPropertyComputationResult = {

        implicit val state: State = State(field)
        // Fields are not final if they are read prematurely!
        if (!field.isFinal && isPrematurelyRead(propertyStore(field, FieldPrematurelyRead.key))) {
            return Result(field, MutableReference)
        };
        state.referenceImmutability = ImmutableReference

        // It still has to be determined of the referred object could escape
        if (field.isFinal)
            return Result(field, ImmutableReference)
        //return createResult();

        val thisType = field.classFile.thisType

        if (field.isPublic && !field.isFinal)
            return Result(field, MutableReference)
        //in cases of a public, package private or protected reference, the referenced object could escape

        // Collect all classes that have access to the field, i.e. the declaring class and possibly
        // classes in the same package as well as subclasses
        // Give up if the set of classes having access to the field is not closed
        val initialClasses =
            if (field.isProtected || field.isPackagePrivate) {
                if (!closedPackages.isClosed(thisType.packageName)) {
                    return Result(field, MutableReference);
                }
                project.classesPerPackage(thisType.packageName)
            } else {
                Set(field.classFile)
            }

        val classesHavingAccess: Iterator[ClassFile] =
            if (field.isProtected) {
                if (typeExtensibility(thisType).isYesOrUnknown) {
                    //state.notEscapes = false
                    return Result(field, MutableReference);
                }
                val subclassesIterator: Iterator[ClassFile] =
                    classHierarchy.allSubclassTypes(thisType, reflexive = false).flatMap { ot ⇒
                        project.classFile(ot).filter(cf ⇒ !initialClasses.contains(cf))
                    }
                initialClasses.iterator ++ subclassesIterator
            } else {
                initialClasses.iterator
            }

        // If there are native methods, we give up
        if (classesHavingAccess.exists(_.methods.exists(_.isNative))) {
            //state.notEscapes = false
            if (!field.isFinal)
                return Result(field, MutableReference)
        }

        for {
            (method, pcs) ← fieldAccessInformation.writeAccesses(field)
            taCode ← getTACAI(method, pcs)
        } {
            if (methodUpdatesField(method, taCode, pcs)) {
                return Result(field, MutableReference);
            }
        }
        if (state.lazyInitInvocation.isDefined) {
            val calleesEOP = propertyStore(state.lazyInitInvocation.get._1, Callees.key)
            handleCalls(calleesEOP)
        }
        createResult()
    }

    def handleCalls(
        calleesEOP: EOptionP[DeclaredMethod, Callees]
    )(
        implicit
        state: State
    ): Boolean = {
        calleesEOP match {
            case FinalP(callees) ⇒
                state.calleesDependee = None
                handleCallees(callees)
            case InterimUBP(callees) ⇒
                state.calleesDependee = Some(calleesEOP)
                handleCallees(callees)
            case _ ⇒
                state.calleesDependee = Some(calleesEOP)
                false
        }
    }

    def handleCallees(callees: Callees)(implicit state: State): Boolean = {
        val pc = state.lazyInitInvocation.get._2
        if (callees.isIncompleteCallSite(pc)) {

            state.referenceImmutability = LazyInitializedNotThreadSafeOrNotDeterministicReference //TODO //MutableReference //NonFinalFieldByAnalysis
            true
        } else {
            val targets = callees.callees(pc)
            if (targets.exists(target ⇒ isNonDeterministic(propertyStore(target, Purity.key)))) {
                state.referenceImmutability = LazyInitializedNotThreadSafeOrNotDeterministicReference //MutableReference //NonFinalFieldByAnalysis
                true
            } else false
        }
    }

    /**
     * Returns the value the field will have after initialization or None if there may be multiple
     * values.
     */
    def getDefaultValue()(implicit state: State): Option[Any] = {

        state.field.fieldType match {
            case FloatType        ⇒ Some(0.0f)
            case IntegerType      ⇒ Some(0)
            case _: ReferenceType ⇒ Some(null)
            case BooleanType      ⇒ Some(false)
            case ByteType         ⇒ Some(0)
            case ShortType        ⇒ Some(0)
            case _                ⇒ None
        }
    }

    /**
     * Prepares the PropertyComputation result, either as IntermediateResult if there are still
     * dependees or as Result otherwise.
     */
    def createResult()(implicit state: State): ProperPropertyComputationResult = {
        if (state.hasDependees && (state.referenceImmutability ne MutableReference)) { //NonFinalFieldByAnalysis))
            InterimResult(
                state.field,
                MutableReference, //NonFinalFieldByAnalysis,
                state.referenceImmutability,
                state.dependees,
                c
            )
        } else {
            Result(state.field, state.referenceImmutability)
        }
    }

    /**
     * Continuation function handling updates to the FieldPrematurelyRead property or to the purity
     * property of the method that initializes a (potentially) lazy initialized field.
     */
    def c(eps: SomeEPS)(implicit state: State): ProperPropertyComputationResult = {
        var isNotFinal = false

        eps.pk match {
            case EscapeProperty.key ⇒
                val newEP = eps.asInstanceOf[EOptionP[DefinitionSite, EscapeProperty]]
                state.escapeDependees = state.escapeDependees.filter(_.e ne newEP.e)
                isNotFinal = handleEscapeProperty(newEP)
            case TACAI.key ⇒
                val newEP = eps.asInstanceOf[EOptionP[Method, TACAI]]
                val method = newEP.e
                val pcs = state.tacDependees(method)._2
                state.tacDependees -= method
                if (eps.isRefinable)
                    state.tacDependees += method -> ((newEP, pcs))
                //TODO tacai funktionen alle ausführen
                isNotFinal = methodUpdatesField(method, newEP.ub.tac.get, pcs)
            case Callees.key ⇒
                isNotFinal = handleCalls(eps.asInstanceOf[EOptionP[DeclaredMethod, Callees]])
            case FieldPrematurelyRead.key ⇒
                isNotFinal = isPrematurelyRead(eps.asInstanceOf[EOptionP[Field, FieldPrematurelyRead]])
            case Purity.key ⇒
                val newEP = eps.asInstanceOf[EOptionP[DeclaredMethod, Purity]]
                state.purityDependees = state.purityDependees.filter(_.e ne newEP.e)
                val nonDeterministicResult = isNonDeterministic(newEP)
                //if (!r) state.referenceImmutability = LazyInitializedReference
                if (state.referenceImmutability != LazyInitializedNotThreadSafeOrNotDeterministicReference &&
                    state.referenceImmutability != LazyInitializedThreadSafeReference) { // both dont need determinism
                    isNotFinal = nonDeterministicResult
                }

            case ReferenceImmutability.key ⇒
                val newEP = eps.asInstanceOf[EOptionP[Field, ReferenceImmutability]]
                state.referenceImmutabilityDependees =
                    state.referenceImmutabilityDependees.filter(_.e ne newEP.e)
                isNotFinal = !isImmutableReference(newEP)
            /*case TypeImmutability_new.key ⇒
                val newEP = eps.asInstanceOf[EOptionP[ObjectType, TypeImmutability_new]]
                state.typeDependees = state.typeDependees.filter(_.e ne newEP.e)
                newEP match {
                    case FinalP(DependentImmutableType) ⇒
                    case FinalP(_)                      ⇒
                    case ep                             ⇒ state.typeDependees += ep
                } */
        }
        if (isNotFinal)
            state.referenceImmutability = MutableReference

        /*println("is not final: " + isNotFinal)
        if (!state.field.isFinal && {
            state.referenceImmutability match {
                case ImmutableReference |
                     LazyInitializedThreadSafeReference |
                     LazyInitializedNotThreadSafeButDeterministicReference => //OrNotDeterministicReference ⇒
                     false
                case _ ⇒ true
            }
        }) {
            Result(state.field, MutableReference) //Result(state.field, NonFinalFieldByAnalysis)
        } else */
        createResult()
    }

    /**
     * Analyzes field writes for a single method, returning false if the field may still be
     * effectively final and true otherwise.
     */
    def methodUpdatesField(
        method: Method,
        taCode: TACode[TACMethodParameter, V],
        pcs:    PCs
    )(implicit state: State): Boolean = {
        val field = state.field
        val stmts = taCode.stmts
        val staticAddition = {
            if (method.isStatic)
                1
            else
                0
        }
        //println("stmts: "+stmts.mkString(", \n"))
        for (pc ← pcs.iterator) {
            val index = taCode.pcToIndex(pc)
            if (index > (-1 + staticAddition)) { //TODO unnötig
                val stmt = stmts(index)
                if (stmt.pc == pc) {
                    (stmt.astID: @switch) match {
                        case PutStatic.ASTID | PutField.ASTID ⇒
                            if (method.isInitializer) {
                                if (field.isStatic) {
                                    if (method.isConstructor)
                                        return true;
                                } else {
                                    val receiverDefs = stmt.asPutField.objRef.asVar.definedBy
                                    if (receiverDefs != SelfReferenceParameter)
                                        return true;
                                }
                            } else {
                                if (field.isStatic ||
                                    stmt.asPutField.objRef.asVar.definedBy == SelfReferenceParameter) {
                                    // We consider lazy initialization if there is only single write
                                    // outside an initializer, so we can ignore synchronization
                                    if (state.referenceImmutability == LazyInitializedThreadSafeReference ||
                                        state.referenceImmutability == LazyInitializedNotThreadSafeButDeterministicReference) //LazyInitializedField)
                                        return true;
                                    // A lazily initialized instance field must be initialized only
                                    // by its owning instance
                                    if (!field.isStatic &&
                                        stmt.asPutField.objRef.asVar.definedBy != SelfReferenceParameter)
                                        return true;
                                    val defaultValue = getDefaultValue()
                                    if (defaultValue.isEmpty)
                                        return true;

                                    // A field written outside an initializer must be lazily
                                    // initialized or it is non-final

                                    val result = handleLazyInitialization(
                                        index,
                                        defaultValue.get,
                                        method,
                                        taCode.stmts,
                                        taCode.cfg,
                                        taCode.pcToIndex,
                                        taCode
                                    )
                                    if (result.isDefined)
                                        return result.get;

                                } else if (referenceHasEscaped(stmt.asPutField.objRef.asVar, stmts, method)) {

                                    // note that here we assume real three address code (flat hierarchy)

                                    // for instance fields it is okay if they are written in the
                                    // constructor (w.r.t. the currently initialized object!)

                                    // If the field that is written is not the one referred to by the
                                    // self reference, it is not effectively final.

                                    // However, a method (e.g. clone) may instantiate a new object and
                                    // write the field as long as that new object did not yet escape.
                                    return true;
                                }

                            }
                        //TODO assignment statement ...
                        //case Assignment.
                        case _ ⇒ throw new RuntimeException("unexpected field access");
                    }
                } else {
                    // nothing to do as the put field is dead
                }
            }
        }
        false
    }

    /**
     * f
     * Checks whether the object reference of a PutField does escape (except for being returned).
     */
    def referenceHasEscaped(
        ref:    V,
        stmts:  Array[Stmt[V]],
        method: Method
    )(implicit state: State): Boolean = {

        ref.definedBy.forall { defSite ⇒
            if (defSite < 0) true // Must be locally created
            else {
                val definition = stmts(defSite).asAssignment
                // Must either be null or freshly allocated
                if (definition.expr.isNullExpr) false
                else if (!definition.expr.isNew) true
                else {
                    val escape =
                        propertyStore(definitionSites(method, definition.pc), EscapeProperty.key)
                    handleEscapeProperty(escape)
                }
            }
        }
    }

    /**
     * Handles the influence of an escape property on the field mutability.
     * @return true if the object - on which a field write occurred - escapes, false otherwise.
     * @note (Re-)Adds dependees as necessary.
     */
    def handleEscapeProperty(
        ep: EOptionP[DefinitionSite, EscapeProperty]
    )(implicit state: State): Boolean = {
        ep match {
            case FinalP(NoEscape | EscapeInCallee | EscapeViaReturn) ⇒
                false

            case FinalP(AtMost(_)) ⇒
                true

            case _: FinalEP[DefinitionSite, EscapeProperty] ⇒
                true // Escape state is worse than via return

            case InterimUBP(NoEscape | EscapeInCallee | EscapeViaReturn) ⇒
                state.escapeDependees += ep
                false

            case InterimUBP(AtMost(_)) ⇒
                true

            case _: InterimEP[DefinitionSite, EscapeProperty] ⇒
                true // Escape state is worse than via return

            case _ ⇒
                state.escapeDependees += ep
                false
        }
    }

}

trait L0ReferenceImmutabilityAnalysisScheduler extends FPCFAnalysisScheduler {

    final override def uses: Set[PropertyBounds] = Set(
        PropertyBounds.lub(Purity),
        PropertyBounds.lub(FieldPrematurelyRead),
        PropertyBounds.finalP(TACAI),
        PropertyBounds.ub(EscapeProperty),
        PropertyBounds.ub(ReferenceImmutability)
    )

    final def derivedProperty: PropertyBounds = PropertyBounds.lub(ReferenceImmutability)

}

/**
 * Executor for the field mutability analysis.
 */
object EagerL0ReferenceImmutabilityAnalysis
    extends L0ReferenceImmutabilityAnalysisScheduler
    with BasicFPCFEagerAnalysisScheduler {

    final override def start(p: SomeProject, ps: PropertyStore, unused: Null): FPCFAnalysis = {
        val analysis = new L0ReferenceImmutabilityAnalysis(p)
        val fields = p.allProjectClassFiles.flatMap(_.fields)
        ps.scheduleEagerComputationsForEntities(fields)(analysis.determineReferenceImmutability)
        analysis
    }

    override def derivesEagerly: Set[PropertyBounds] = Set(derivedProperty)

    override def derivesCollaboratively: Set[PropertyBounds] = Set.empty
}

/**
 * Executor for the lazy field mutability analysis.
 */
object LazyL0ReferenceImmutabilityAnalysis
    extends L0ReferenceImmutabilityAnalysisScheduler
    with BasicFPCFLazyAnalysisScheduler {

    final override def register(
        p:      SomeProject,
        ps:     PropertyStore,
        unused: Null
    ): FPCFAnalysis = {
        val analysis = new L0ReferenceImmutabilityAnalysis(p)
        ps.registerLazyPropertyComputation(
            ReferenceImmutability.key,
            analysis.determineReferenceImmutability
        )
        analysis
    }

    override def derivesLazily: Some[PropertyBounds] = Some(derivedProperty)
}