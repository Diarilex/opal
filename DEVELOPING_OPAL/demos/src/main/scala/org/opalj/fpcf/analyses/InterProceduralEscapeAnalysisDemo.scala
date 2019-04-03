/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package fpcf
package analyses

import java.net.URL

import org.opalj.log.LogContext
import org.opalj.log.OPALLogger.info
import org.opalj.util.PerformanceEvaluation.time
import org.opalj.br.fpcf.cg.properties.ReflectionRelatedCallees
import org.opalj.br.fpcf.cg.properties.SerializationRelatedCallees
import org.opalj.br.fpcf.cg.properties.StandardInvokeCallees
import org.opalj.br.fpcf.cg.properties.ThreadRelatedIncompleteCallSites
import org.opalj.br.fpcf.properties.AtMost
import org.opalj.br.fpcf.properties.EscapeInCallee
import org.opalj.br.fpcf.properties.EscapeProperty
import org.opalj.br.fpcf.properties.EscapeViaAbnormalReturn
import org.opalj.br.fpcf.properties.EscapeViaHeapObject
import org.opalj.br.fpcf.properties.EscapeViaNormalAndAbnormalReturn
import org.opalj.br.fpcf.properties.EscapeViaParameter
import org.opalj.br.fpcf.properties.EscapeViaParameterAndAbnormalReturn
import org.opalj.br.fpcf.properties.EscapeViaParameterAndNormalAndAbnormalReturn
import org.opalj.br.fpcf.properties.EscapeViaParameterAndReturn
import org.opalj.br.fpcf.properties.EscapeViaReturn
import org.opalj.br.fpcf.properties.EscapeViaStaticField
import org.opalj.br.fpcf.properties.GlobalEscape
import org.opalj.br.fpcf.properties.NoEscape
import org.opalj.br.analyses.BasicReport
import org.opalj.br.analyses.DefaultOneStepAnalysis
import org.opalj.br.analyses.Project
import org.opalj.br.analyses.VirtualFormalParameter
import org.opalj.br.fpcf.FPCFAnalysesManagerKey
import org.opalj.br.fpcf.PropertyStoreKey
import org.opalj.ai.domain.l2.DefaultPerformInvocationsDomainWithCFGAndDefUse
import org.opalj.ai.fpcf.analyses.LazyL0BaseAIAnalysis
import org.opalj.ai.fpcf.properties.AIDomainFactoryKey
import org.opalj.tac.common.DefinitionSite
import org.opalj.tac.fpcf.analyses.cg.RTACallGraphAnalysisScheduler
import org.opalj.tac.fpcf.analyses.cg.TriggeredFinalizerAnalysisScheduler
import org.opalj.tac.fpcf.analyses.cg.TriggeredLoadedClassesAnalysis
import org.opalj.tac.fpcf.analyses.cg.TriggeredSerializationRelatedCallsAnalysis
import org.opalj.tac.fpcf.analyses.cg.TriggeredStaticInitializerAnalysis
import org.opalj.tac.fpcf.analyses.cg.TriggeredThreadRelatedCallsAnalysis
import org.opalj.tac.fpcf.analyses.cg.reflection.TriggeredReflectionRelatedCallsAnalysis
import org.opalj.tac.fpcf.analyses.cg.LazyCalleesAnalysis
import org.opalj.tac.fpcf.analyses.cg.TriggeredConfiguredNativeMethodsAnalysis
import org.opalj.tac.fpcf.analyses.cg.TriggeredInstantiatedTypesAnalysis
import org.opalj.tac.fpcf.analyses.TACAITransformer
import org.opalj.tac.fpcf.analyses.escape.EagerInterProceduralEscapeAnalysis
import org.opalj.tac.fpcf.analyses.TriggeredSystemPropertiesAnalysis

/**
 * A small demo that shows how to use the
 * [[org.opalj.tac.fpcf.analyses.escape.InterProceduralEscapeAnalysis]] and what are the results of it.
 *
 * @author Florian Kübler
 */
object InterProceduralEscapeAnalysisDemo extends DefaultOneStepAnalysis {

    override def title: String = "determines escape information"

    override def description: String = {
        "Determines escape information for every allocation site and every formal parameter"
    }

    override def doAnalyze(
        project:       Project[URL],
        parameters:    Seq[String],
        isInterrupted: () ⇒ Boolean
    ): BasicReport = {
        implicit val logContext: LogContext = project.logContext

        val propertyStore = time {
            val performInvocationsDomain = classOf[DefaultPerformInvocationsDomainWithCFGAndDefUse[_]]

            project.updateProjectInformationKeyInitializationData(
                AIDomainFactoryKey,
                (i: Option[Set[Class[_ <: AnyRef]]]) ⇒ (i match {
                    case None               ⇒ Set(performInvocationsDomain)
                    case Some(requirements) ⇒ requirements + performInvocationsDomain
                }): Set[Class[_ <: AnyRef]]
            )
            project.get(PropertyStoreKey)
        } { t ⇒ info("progress", s"initialization of property store took ${t.toSeconds}") }

        val manager = project.get(FPCFAnalysesManagerKey)
        time {
            manager.runAll(
                RTACallGraphAnalysisScheduler,
                TriggeredStaticInitializerAnalysis,
                TriggeredLoadedClassesAnalysis,
                TriggeredFinalizerAnalysisScheduler,
                TriggeredThreadRelatedCallsAnalysis,
                TriggeredSerializationRelatedCallsAnalysis,
                TriggeredReflectionRelatedCallsAnalysis,
                TriggeredInstantiatedTypesAnalysis,
                TriggeredConfiguredNativeMethodsAnalysis,
                TriggeredSystemPropertiesAnalysis,
                LazyCalleesAnalysis(
                    Set(
                        StandardInvokeCallees,
                        SerializationRelatedCallees,
                        ReflectionRelatedCallees,
                        ThreadRelatedIncompleteCallSites
                    )
                ),
                LazyL0BaseAIAnalysis,
                TACAITransformer
            )
        } { t ⇒ info("progress", s"computing call graph and tac took ${t.toSeconds}") }
        time {
            manager.runAll(EagerInterProceduralEscapeAnalysis)
        } { t ⇒ info("progress", s"escape analysis took ${t.toSeconds}") }

        /*for (e ← propertyStore.finalEntities(AtMost(EscapeViaAbnormalReturn))) {
            println(s"$e : AtMostEscapeViaAbnormalReturn")
        }
        for (e ← propertyStore.finalEntities(AtMost(EscapeInCallee))) {
            println(s"$e : AtMostEscapeInCallee")
        }*/

        def countAS(entities: Iterator[Entity]) = entities.count(_.isInstanceOf[DefinitionSite])
        def countFP(entities: Iterator[Entity]) = entities.count(_.isInstanceOf[VirtualFormalParameter])

        val message =
            s"""|ALLOCATION SITES:
                |# of local objects: ${countAS(propertyStore.finalEntities(NoEscape))}
                |# of objects escaping in a callee: ${countAS(propertyStore.finalEntities(EscapeInCallee))}
                |# of escaping objects via return: ${countAS(propertyStore.finalEntities(EscapeViaReturn))}
                |# of escaping objects via abnormal return: ${countAS(propertyStore.finalEntities(EscapeViaAbnormalReturn))}
                |# of escaping objects via parameter: ${countAS(propertyStore.finalEntities(EscapeViaParameter))}
                |# of escaping objects via normal and abnormal return: ${countAS(propertyStore.finalEntities(EscapeViaNormalAndAbnormalReturn))}
                |# of escaping objects via parameter and normal return: ${countAS(propertyStore.finalEntities(EscapeViaParameterAndReturn))}
                |# of escaping objects via parameter and abnormal return: ${countAS(propertyStore.finalEntities(EscapeViaParameterAndAbnormalReturn))}
                |# of escaping objects via parameter and normal and abnormal return: ${countAS(propertyStore.finalEntities(EscapeViaParameterAndNormalAndAbnormalReturn))}
                |# of escaping objects via static field: ${countAS(propertyStore.finalEntities(EscapeViaStaticField))}
                |# of escaping objects via heap objects: ${countAS(propertyStore.finalEntities(EscapeViaHeapObject))}
                |# of global escaping objects: ${countAS(propertyStore.finalEntities(GlobalEscape))}
                |# of at most local object: ${countAS(propertyStore.entities(EscapeProperty.key).collect { case FinalEP(e, AtMost(NoEscape)) ⇒ e })}
                |# of escaping object at most in callee: ${countAS(propertyStore.entities(EscapeProperty.key).collect { case FinalEP(e, AtMost(EscapeInCallee)) ⇒ e })}
                |# of escaping object at most via return: ${countAS(propertyStore.entities(EscapeProperty.key).collect { case FinalEP(e, AtMost(EscapeViaReturn)) ⇒ e })}
                |# of escaping object at most via abnormal return: ${countAS(propertyStore.entities(EscapeProperty.key).collect { case FinalEP(e, AtMost(EscapeViaAbnormalReturn)) ⇒ e })}
                |# of escaping object at most via parameter: ${countAS(propertyStore.entities(EscapeProperty.key).collect { case FinalEP(e, AtMost(EscapeViaParameter)) ⇒ e })}
                |# of escaping object at most via normal and abnormal return: ${countAS(propertyStore.entities(EscapeProperty.key).collect { case FinalEP(e, AtMost(EscapeViaNormalAndAbnormalReturn)) ⇒ e })}
                |# of escaping object at most via parameter and normal return: ${countAS(propertyStore.entities(EscapeProperty.key).collect { case FinalEP(e, AtMost(EscapeViaParameterAndReturn)) ⇒ e })}
                |# of escaping object at most via parameter and abnormal return: ${countAS(propertyStore.entities(EscapeProperty.key).collect { case FinalEP(e, AtMost(EscapeViaParameterAndAbnormalReturn)) ⇒ e })}
                |# of escaping object at most via parameter and normal and abnormal return: ${countAS(propertyStore.entities(EscapeProperty.key).collect { case FinalEP(e, AtMost(EscapeViaParameterAndNormalAndAbnormalReturn)) ⇒ e })}
                |
                |
                |FORMAL PARAMETERS:
                |# of local objects: ${countFP(propertyStore.finalEntities(NoEscape))}
                |# of objects escaping in a callee: ${countFP(propertyStore.finalEntities(EscapeInCallee))}
                |# of escaping objects via return: ${countFP(propertyStore.finalEntities(EscapeViaReturn))}
                |# of escaping objects via abnormal return: ${countFP(propertyStore.finalEntities(EscapeViaAbnormalReturn))}
                |# of escaping objects via parameter: ${countFP(propertyStore.finalEntities(EscapeViaParameter))}
                |# of escaping objects via normal and abnormal return: ${countFP(propertyStore.finalEntities(EscapeViaNormalAndAbnormalReturn))}
                |# of escaping objects via parameter and normal return: ${countFP(propertyStore.finalEntities(EscapeViaParameterAndReturn))}
                |# of escaping objects via parameter and abnormal return: ${countFP(propertyStore.finalEntities(EscapeViaParameterAndAbnormalReturn))}
                |# of escaping objects via parameter and normal and abnormal return: ${countFP(propertyStore.finalEntities(EscapeViaParameterAndNormalAndAbnormalReturn))}
                |# of escaping objects via static field: ${countFP(propertyStore.finalEntities(EscapeViaStaticField))}
                |# of escaping objects via heap objects: ${countFP(propertyStore.finalEntities(EscapeViaHeapObject))}
                |# of global escaping objects: ${countFP(propertyStore.finalEntities(GlobalEscape))}
                |# of at most local object: ${countFP(propertyStore.entities(EscapeProperty.key).collect { case FinalEP(e, AtMost(NoEscape)) ⇒ e })}
                |# of escaping object at most in callee: ${countFP(propertyStore.entities(EscapeProperty.key).collect { case FinalEP(e, AtMost(EscapeInCallee)) ⇒ e })}
                |# of escaping object at most via return: ${countFP(propertyStore.entities(EscapeProperty.key).collect { case FinalEP(e, AtMost(EscapeViaReturn)) ⇒ e })}
                |# of escaping object at most via abnormal return: ${countFP(propertyStore.entities(EscapeProperty.key).collect { case FinalEP(e, AtMost(EscapeViaAbnormalReturn)) ⇒ e })}
                |# of escaping object at most via parameter: ${countFP(propertyStore.entities(EscapeProperty.key).collect { case FinalEP(e, AtMost(EscapeViaParameter)) ⇒ e })}
                |# of escaping object at most via normal and abnormal return: ${countFP(propertyStore.entities(EscapeProperty.key).collect { case FinalEP(e, AtMost(EscapeViaNormalAndAbnormalReturn)) ⇒ e })}
                |# of escaping object at most via parameter and normal return: ${countFP(propertyStore.entities(EscapeProperty.key).collect { case FinalEP(e, AtMost(EscapeViaParameterAndReturn)) ⇒ e })}
                |# of escaping object at most via parameter and abnormal return: ${countFP(propertyStore.entities(EscapeProperty.key).collect { case FinalEP(e, AtMost(EscapeViaParameterAndAbnormalReturn)) ⇒ e })}
                |# of escaping object at most via parameter and normal and abnormal return: ${countFP(propertyStore.entities(EscapeProperty.key).collect { case FinalEP(e, AtMost(EscapeViaParameterAndNormalAndAbnormalReturn)) ⇒ e })}"""

        BasicReport(message.stripMargin('|'))
    }

}