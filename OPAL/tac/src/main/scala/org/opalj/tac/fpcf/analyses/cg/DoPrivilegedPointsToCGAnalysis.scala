/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package tac
package fpcf
package analyses
package cg

import org.opalj.collection.immutable.RefArray
import org.opalj.fpcf.EOptionP
import org.opalj.fpcf.EPK
import org.opalj.fpcf.EPS
import org.opalj.fpcf.EUBP
import org.opalj.fpcf.InterimEUBP
import org.opalj.fpcf.InterimPartialResult
import org.opalj.fpcf.PartialResult
import org.opalj.fpcf.ProperPropertyComputationResult
import org.opalj.fpcf.PropertyBounds
import org.opalj.fpcf.PropertyComputationResult
import org.opalj.fpcf.PropertyStore
import org.opalj.fpcf.Results
import org.opalj.fpcf.SomeEPS
import org.opalj.fpcf.UBP
import org.opalj.br.fpcf.FPCFAnalysis
import org.opalj.br.DeclaredMethod
import org.opalj.br.analyses.DeclaredMethodsKey
import org.opalj.br.analyses.SomeProject
import org.opalj.br.analyses.VirtualFormalParameter
import org.opalj.br.analyses.VirtualFormalParametersKey
import org.opalj.br.fpcf.pointsto.properties.PointsTo
import org.opalj.br.fpcf.BasicFPCFEagerAnalysisScheduler
import org.opalj.br.fpcf.cg.properties.Callees
import org.opalj.br.fpcf.cg.properties.Callers
import org.opalj.br.ArrayType
import org.opalj.br.MethodDescriptor
import org.opalj.br.ObjectType

/**
 * On each call of the [[sourceMethod*]] it will call the [[declaredTargetMethod*]] upon its first
 * parameter and returns the result of this call.
 * This analysis manages the entries for [[org.opalj.br.fpcf.cg.properties.Callees]] and
 * [[org.opalj.br.fpcf.cg.properties.Callers]] as well as the
 * [[org.opalj.br.fpcf.pointsto.properties.PointsTo]] mappings.
 *
 * TODO: This analysis is very specific to the points-to analysis. It should also work for the other
 * analyses.
 *
 * @author Florian Kuebler
 */
class AbstractDoPrivilegedPointsToCGAnalysis private[cg] (
        final val sourceMethod:         DeclaredMethod,
        final val declaredTargetMethod: DeclaredMethod,
        final val project:              SomeProject
) extends FPCFAnalysis {
    private[this] val formalParameters = p.get(VirtualFormalParametersKey)
    private[this] val declaredMethods = p.get(DeclaredMethodsKey)

    def analyze(): ProperPropertyComputationResult = {

        // take the first parameter
        val fps = formalParameters(sourceMethod)
        // this happens e.g. for java <= 7
        if (fps == null)
            return Results();
        val fp = fps(1)
        val pointsToParam = ps(fp, PointsTo.key)

        Results(methodMapping(pointsToParam, 0))
    }

    def continuationForParameterValue(
        seenTypes: Int
    )(eps: SomeEPS): ProperPropertyComputationResult = eps match {
        case EUBP(_: VirtualFormalParameter, _: PointsTo) ⇒
            methodMapping(eps.asInstanceOf[EPS[VirtualFormalParameter, PointsTo]], seenTypes)
        case _ ⇒
            throw new IllegalStateException(s"unexpected update $eps")
    }

    def methodMapping(
        pointsTo: EOptionP[VirtualFormalParameter, PointsTo], seenTypes: Int
    ): ProperPropertyComputationResult = {

        val calls = new DirectCalls()
        var results: List[ProperPropertyComputationResult] = Nil

        val newSeenTypes = if (pointsTo.hasUBP) {
            for (t ← pointsTo.ub.dropOldest(seenTypes)) {
                val callR = p.instanceCall(
                    sourceMethod.declaringClassType.asObjectType,
                    t,
                    declaredTargetMethod.name,
                    declaredTargetMethod.descriptor
                )
                if (callR.hasValue) {
                    // 1. Add the call to the specified method.
                    val tgtMethod = declaredMethods(callR.value)
                    calls.addCall(sourceMethod, tgtMethod, 0)

                    // 2. The points-to set of *this* of the target method should contain all information
                    // from the points-to set of the first parameter of the source method.
                    val tgtThis = formalParameters(tgtMethod)(0)
                    results ::= PartialResult[VirtualFormalParameter, PointsTo](tgtThis, PointsTo.key, {
                        case UBP(ub: PointsTo) ⇒
                            Some(InterimEUBP(
                                tgtThis,
                                ub.updated(pointsTo.ub.dropOldest(seenTypes))
                            ))

                        case _: EPK[VirtualFormalParameter, PointsTo] ⇒
                            Some(InterimEUBP(tgtThis, pointsTo.ub))
                    })

                    // 3. Map the return value back to the source method
                    val returnPointsTo = ps(tgtMethod, PointsTo.key)
                    results ::= returnMapping(returnPointsTo, 0)

                } else {
                    calls.addIncompleteCallSite(0)
                }
            }
            pointsTo.ub.numElements
        } else {
            0
        }

        if (pointsTo.isRefinable) {
            results ::= InterimPartialResult(
                Some(pointsTo), continuationForParameterValue(newSeenTypes)
            )
        }
        results ++= calls.partialResults(sourceMethod)

        Results(results)
    }

    def returnMapping(
        returnPointsTo: EOptionP[DeclaredMethod, PointsTo], numSeenTypes: Int
    ): ProperPropertyComputationResult = {
        var results: List[ProperPropertyComputationResult] = Nil
        val newNumSeenTypes = if (returnPointsTo.hasUBP) {
            results ::= PartialResult[DeclaredMethod, PointsTo](sourceMethod, PointsTo.key, {
                case UBP(ub: PointsTo) ⇒
                    Some(InterimEUBP(sourceMethod, ub.updated(returnPointsTo.ub.dropOldest(numSeenTypes))))

                case _: EPK[_, _] ⇒
                    Some(InterimEUBP(sourceMethod, returnPointsTo.ub))
            })
            returnPointsTo.ub.numElements
        } else {
            0
        }

        if (returnPointsTo.isRefinable) {
            results ::= InterimPartialResult(
                Some(returnPointsTo), continuationForReturnValue(newNumSeenTypes)
            )
        }

        Results(results)
    }

    def continuationForReturnValue(
        seenTypes: Int
    )(eps: SomeEPS): ProperPropertyComputationResult = eps match {
        // join the return values of all invoked methods
        case EUBP(_: DeclaredMethod, _: PointsTo) ⇒
            returnMapping(eps.asInstanceOf[EPS[DeclaredMethod, PointsTo]], seenTypes)

        case _ ⇒
            throw new IllegalStateException(s"unexpected update $eps")
    }
}

class DoPrivilegedPointsToCGAnalysis private[cg] (
        final val project: SomeProject
) extends FPCFAnalysis {
    def analyze(p: SomeProject): PropertyComputationResult = {
        var results: List[ProperPropertyComputationResult] = Nil

        val accessControllerType = ObjectType("java/security/AccessController")
        val privilegedActionType = ObjectType("java/security/PrivilegedAction")
        val privilegedExceptionActionType = ObjectType("java/security/PrivilegedExceptionAction")
        val accessControlContextType = ObjectType("java/security/AccessControlContext")
        val permissionType = ObjectType("java/security/Permission")
        val permissionsArray = ArrayType(permissionType)

        val declaredMethods = p.get(DeclaredMethodsKey)
        val runMethod = declaredMethods(
            privilegedActionType,
            "",
            privilegedActionType,
            "run",
            MethodDescriptor.JustReturnsObject
        )

        val doPrivileged1 = declaredMethods(
            accessControllerType,
            "",
            accessControllerType,
            "doPrivileged",
            MethodDescriptor(privilegedActionType, ObjectType.Object)
        )
        results ::= new AbstractDoPrivilegedPointsToCGAnalysis(doPrivileged1, runMethod, p).analyze()

        val doPrivileged2 = declaredMethods(
            accessControllerType,
            "",
            accessControllerType,
            "doPrivileged",
            MethodDescriptor(
                RefArray(privilegedActionType, accessControlContextType),
                ObjectType.Object
            )
        )
        results ::= new AbstractDoPrivilegedPointsToCGAnalysis(doPrivileged2, runMethod, p).analyze()

        val doPrivileged3 = declaredMethods(
            accessControllerType,
            "",
            accessControllerType,
            "doPrivileged",
            MethodDescriptor(
                RefArray(privilegedActionType, accessControlContextType, permissionsArray),
                ObjectType.Object
            )
        )
        results ::= new AbstractDoPrivilegedPointsToCGAnalysis(doPrivileged3, runMethod, p).analyze()

        val doPrivileged4 = declaredMethods(
            accessControllerType,
            "",
            accessControllerType,
            "doPrivileged",
            MethodDescriptor(privilegedExceptionActionType, ObjectType.Object)
        )
        results ::= new AbstractDoPrivilegedPointsToCGAnalysis(doPrivileged4, runMethod, p).analyze()

        val doPrivileged5 = declaredMethods(
            accessControllerType,
            "",
            accessControllerType,
            "doPrivileged",
            MethodDescriptor(
                RefArray(privilegedExceptionActionType, accessControlContextType),
                ObjectType.Object
            )
        )
        results ::= new AbstractDoPrivilegedPointsToCGAnalysis(doPrivileged5, runMethod, p).analyze()

        val doPrivileged6 = declaredMethods(
            accessControllerType,
            "",
            accessControllerType,
            "doPrivileged",
            MethodDescriptor(
                RefArray(privilegedExceptionActionType, accessControlContextType, permissionsArray),
                ObjectType.Object
            )
        )
        results ::= new AbstractDoPrivilegedPointsToCGAnalysis(doPrivileged6, runMethod, p).analyze()

        val doPrivilegedWithCombiner1 = declaredMethods(
            accessControllerType,
            "",
            accessControllerType,
            "doPrivilegedWithCombiner",
            MethodDescriptor(privilegedActionType, ObjectType.Object)
        )
        results ::= new AbstractDoPrivilegedPointsToCGAnalysis(doPrivilegedWithCombiner1, runMethod, p).analyze()

        val doPrivilegedWithCombiner2 = declaredMethods(
            accessControllerType,
            "",
            accessControllerType,
            "doPrivilegedWithCombiner",
            MethodDescriptor(
                RefArray(privilegedActionType, accessControlContextType, permissionsArray),
                ObjectType.Object
            )
        )
        results ::= new AbstractDoPrivilegedPointsToCGAnalysis(doPrivilegedWithCombiner2, runMethod, p).analyze()

        val doPrivilegedWithCombiner3 = declaredMethods(
            accessControllerType,
            "",
            accessControllerType,
            "doPrivilegedWithCombiner",
            MethodDescriptor(
                RefArray(privilegedExceptionActionType),
                ObjectType.Object
            )
        )
        results ::= new AbstractDoPrivilegedPointsToCGAnalysis(doPrivilegedWithCombiner3, runMethod, p).analyze()

        val doPrivilegedWithCombiner4 = declaredMethods(
            accessControllerType,
            "",
            accessControllerType,
            "doPrivilegedWithCombiner",
            MethodDescriptor(
                RefArray(privilegedExceptionActionType, accessControlContextType, permissionsArray),
                ObjectType.Object
            )
        )
        results ::= new AbstractDoPrivilegedPointsToCGAnalysis(doPrivilegedWithCombiner4, runMethod, p).analyze()

        Results(results)
    }
}

object DoPrivilegedPointsToCGAnalysisScheduler extends BasicFPCFEagerAnalysisScheduler {

    override def uses: Set[PropertyBounds] = Set(PropertyBounds.ub(PointsTo))

    override def derivesCollaboratively: Set[PropertyBounds] =
        PropertyBounds.ubs(PointsTo, Callers, Callees)

    override def derivesEagerly: Set[PropertyBounds] = Set.empty

    override def start(
        p: SomeProject, ps: PropertyStore, unused: Null
    ): DoPrivilegedPointsToCGAnalysis = {
        val analysis = new DoPrivilegedPointsToCGAnalysis(p)
        ps.scheduleEagerComputationForEntity(p)(analysis.analyze)
        analysis
    }
}
