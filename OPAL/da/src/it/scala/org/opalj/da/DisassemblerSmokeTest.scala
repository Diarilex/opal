/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package da

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.FunSpec
import org.scalatest.Matchers
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

import org.opalj.bi.TestResources
import org.opalj.concurrent.OPALExecutionContextTaskSupport
import org.opalj.util.PerformanceEvaluation

/**
 * This test(suite) just loads a very large number of class files and creates
 * the xHTML representation of the classes. It basically tests if we can load and
 * process a large number of different classes without exceptions (smoke test).
 *
 * @author Michael Eichberg
 */
@RunWith(classOf[JUnitRunner])
class DisassemblerSmokeTest extends FunSpec with Matchers {

    describe("the Disassembler") {
        val jmodsZip = TestResources.locateTestResources(
            "classfiles/Java9-selected-jmod-module-info.classes.zip",
            "bi"
        )
        val jreLibraryFolder = bytecode.JRELibraryFolder
        val specialResources = Traversable(jmodsZip, jreLibraryFolder)
        for { file ← bi.TestResources.allBITestJARs ++ specialResources } {

            describe(s"(when processing $file)") {

                val classFiles: List[(ClassFile, URL)] = {
                    var exceptions: List[(AnyRef, Throwable)] = Nil

                    val classFiles = PerformanceEvaluation.time {
                        val Lock = new Object
                        val exceptionHandler = (source: AnyRef, throwable: Throwable) ⇒ {
                            Lock.synchronized {
                                exceptions ::= ((source, throwable))
                            }
                        }

                        val classFiles = ClassFileReader.ClassFiles(file, exceptionHandler)

                        // Check that we have something to process...
                        if (file.getName != "Empty.jar" && classFiles.isEmpty) {
                            throw new UnknownError(s"the file/folder $file is empty")
                        }

                        classFiles
                    } { t ⇒ info(s"reading took ${t.toSeconds}") }

                    info(s"loaded ${classFiles.size} class files")

                    it(s"reading should not result in exceptions") {
                        if (exceptions.nonEmpty) {
                            info(exceptions.mkString(s"exceptions while reading $file:\n", "\n", ""))
                            fail(s"reading of $file resulted in ${exceptions.size} exceptions")
                        }
                    }

                    classFiles
                }

                it(s"should be able to create the xHTML representation for every class") {

                    val classFilesGroupedByPackage = classFiles.groupBy { e ⇒
                        val (classFile, _ /*url*/ ) = e
                        val fqn = classFile.thisType.asJava
                        if (fqn.contains('.'))
                            fqn.substring(0, fqn.lastIndexOf('.'))
                        else
                            "<default>"
                    }
                    info(s"identified ${classFilesGroupedByPackage.size} packages")

                    val exceptions: Iterable[(URL, Exception)] =
                        (for { (packageName, classFiles) ← classFilesGroupedByPackage } yield {
                            val transformationCounter = new AtomicInteger(0)
                            info(s"processing $packageName")
                            val parClassFiles = classFiles.par
                            parClassFiles.tasksupport = OPALExecutionContextTaskSupport
                            PerformanceEvaluation.time {
                                val exceptions = (
                                    for { (classFile, url) ← parClassFiles } yield {
                                        var result: Option[(URL, Exception)] = None
                                        try {
                                            classFile.toXHTML(None).label should be("html")
                                            transformationCounter.incrementAndGet()
                                        } catch {
                                            case e: Exception ⇒
                                                e.printStackTrace()
                                                result = Some((url, e))
                                        }
                                        result
                                    }
                                ).seq.flatten
                                info(s"transformed ${transformationCounter.get} class files in $packageName")
                                exceptions
                            } { t ⇒ info(s"transformation (parallelized) took ${t.toSeconds}") }
                        }).flatten

                    if (exceptions.nonEmpty) {
                        info(exceptions.mkString(s"exceptions while reading $file:\n", "\n", ""))
                        fail(s"reading of $file resulted in ${exceptions.size} exceptions")
                    }
                }
            }
        }
    }
}
