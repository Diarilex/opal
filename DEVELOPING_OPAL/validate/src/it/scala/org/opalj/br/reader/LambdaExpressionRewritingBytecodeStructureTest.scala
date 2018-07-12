/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package br
package reader

import org.junit.runner.RunWith

import org.scalatest.Matchers
import org.scalatest.FunSpec
import org.scalatest.junit.JUnitRunner

import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.config.ConfigValueFactory

import org.opalj.log.GlobalLogContext
import org.opalj.bi.TestResources.locateTestResources

import org.opalj.br.analyses.Project
import org.opalj.br.analyses.SomeProject
import org.opalj.br.instructions.INVOKESTATIC
import org.opalj.br.reader.LambdaExpressionsRewriting.LambdaNameRegEx
import org.opalj.ai.BaseAI
import org.opalj.ai.InterpretationFailedException
import org.opalj.ai.Domain
import org.opalj.ai.domain.l0.BaseDomain
import org.opalj.ai.domain.l1.DefaultDomainWithCFGAndDefUse

/**
 * Test that code with rewritten `invokedynamic` instructions is still valid bytecode.
 *
 * @author Arne Lottmann
 * @author Michael Eichberg
 */
@RunWith(classOf[JUnitRunner])
class LambdaExpressionRewritingBytecodeStructureTest extends FunSpec with Matchers {

    def verifyMethod(
        testProject:   SomeProject,
        method:        Method,
        domainFactory: (SomeProject, Method) ⇒ Domain
    ): Unit = {
        val classFile = method.classFile
        val code = method.body.get
        val instructions = code.instructions

        classFile.bootstrapMethodTable should be('empty)
        classFile.attributes.count(_.kindId == SynthesizedClassFiles.KindId) should be <= (1)

        val domain = domainFactory(testProject, method)
        try {
            val result = BaseAI(method, domain)
            // the abstract interpretation succeed
            result should not be ('wasAborted)
            // the layout of the instructions array is correct
            for { pc ← 0 until instructions.size; if instructions(pc) != null } {
                val nextPc = instructions(pc).indexOfNextInstruction(pc, false)
                instructions.slice(pc + 1, nextPc).foreach(_ should be(null))
            }
        } catch {
            case e: InterpretationFailedException ⇒
                val pc = e.pc
                val details =
                    if (pc == instructions.length) {
                        "post-processing failed"
                    } else {
                        e.operandsArray(pc).mkString(s"\tAt PC $pc\n\twith stack:\n", ", ", "")
                    }
                val msg = e.getMessage+"\n"+
                    (if (e.getCause != null) "\tcause: "+e.getCause.getMessage+"\n" else "") +
                    details+"\n"+
                    method.toJava +
                    instructions.zipWithIndex.map(_.swap).mkString("\n\t\t", "\n\t\t", "\n")
                Console.err.println(msg)
                fail(msg)
        }
    }

    def testProject(project: SomeProject, domainFactory: (SomeProject, Method) ⇒ Domain): Int = {
        val verifiedMethodsCounter = new AtomicInteger(0)
        for {
            classFile ← project.allProjectClassFiles
            method @ MethodWithBody(body) ← classFile.methods
            instructions = body.instructions
            if instructions.exists {
                case i: INVOKESTATIC ⇒ i.declaringClass.fqn.matches(LambdaNameRegEx)
                case _               ⇒ false
            }
        } {
            verifiedMethodsCounter.incrementAndGet()
            verifyMethod(project, method, domainFactory)
        }
        if (verifiedMethodsCounter.get == 0) {
            fail("didn't find any instance of a rewritten Java lambda expression")
        }

        verifiedMethodsCounter.get
    }

    describe("test interpretation of rewritten invokedynamic instructions") {

        import ClassFileBinding.DeleteSynthesizedClassFilesAttributesConfigKey
        val configValueFalse = ConfigValueFactory.fromAnyRef(false)

        describe("testing the rewritten methods of the lambdas test project") {
            val lambdasJarName = "lambdas-1.8-g-parameters-genericsignature.jar"
            val lambdasJar = locateTestResources(lambdasJarName, "bi")
            val config = LambdaExpressionsRewriting.defaultConfig(
                rewrite = true,
                logRewrites = false
            ).withValue(DeleteSynthesizedClassFilesAttributesConfigKey, configValueFalse)
            val lambdas = Project(lambdasJar, GlobalLogContext, config)
            info(lambdas.statistics.toList.map(_.toString).filter(_.startsWith("(Project")).mkString(","))

            it("should find rewritten Java lambda expressions in the lambdas test project") {
                val verifiedMethodsCount =
                    testProject(lambdas, (p, m) ⇒ BaseDomain(p, m)) +
                        testProject(lambdas, (p, m) ⇒ new DefaultDomainWithCFGAndDefUse(p, m))
                info(s"interpreted ${verifiedMethodsCount / 2} methods")
            }
        }

        if (org.opalj.bi.isCurrentJREAtLeastJava8) {
            describe("testing the rewritten methods of the rewritten JRE") {
                val jrePath = org.opalj.bytecode.JRELibraryFolder
                val config = LambdaExpressionsRewriting.defaultConfig(
                    rewrite = true,
                    logRewrites = false
                ).withValue(DeleteSynthesizedClassFilesAttributesConfigKey, configValueFalse)
                val jre = Project(jrePath, GlobalLogContext, config)
                info(jre.statistics.toList.map(_.toString).filter(_.startsWith("(Project")).mkString(","))
                it("should find rewritten Java lambda expressions in the JRE") {
                    val verifiedMethodsCount =
                        testProject(jre, (p, m) ⇒ BaseDomain(p, m)) +
                            testProject(jre, (p, m) ⇒ new DefaultDomainWithCFGAndDefUse(p, m))
                    info(s"successfully interpreted ${verifiedMethodsCount / 2} methods")
                }
            }
        }
    }
}
