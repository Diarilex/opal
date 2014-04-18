/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2014
 * Software Technology Group
 * Department of Computer Science
 * Technische Universität Darmstadt
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  - Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package de.tud.cs.st
package bat
package findrealbugs
package analyses

import resolved._
import resolved.analyses._
import resolved.instructions._
import ai._
import ai.domain._

/**
 * A domain specifically for BitNops: only tracks individual integer values, but not
 * floats, references, or ranged integer values.
 *
 * @author Daniel Klauer
 */
private class BitNopsDomain[T](override val identifier: T)
        extends Domain[T]
        with DefaultDomainValueBinding[T]
        with Configuration
        with PredefinedClassHierarchy
        with l1.DefaultPreciseIntegerValues[T]
        with l0.DefaultTypeLevelFloatValues[T]
        with l0.DefaultTypeLevelDoubleValues[T]
        with l0.TypeLevelFieldAccessInstructions
        with l0.TypeLevelInvokeInstructions
        with l0.DefaultTypeLevelLongValues[T]
        with l0.DefaultReferenceValuesBinding[T]
        with IgnoreMethodResults
        with IgnoreSynchronization {

    /**
     * We're only interested in certain specific values (0 and -1). Thus, we don't need
     * to track values that are known to be within some range a..b at all.
     */
    override def maxSpreadInteger: Int = 0

    /**
     * DomainValue matcher to allow matching IntegerValues.
     */
    object IntValue {
        /**
         * unapply() method to allowing this object to be used as pattern matcher.
         *
         * @param value The `DomainValue` to match on.
         * @return Optional integer value stored in the `DomainValue`.
         */
        def unapply(value: DomainValue): Option[Int] = {
            getIntValue[Option[Int]](value)(Some(_))(None)
        }
    }

    def checkForNop(insn: Instruction, l: DomainValue, r: DomainValue): Option[String] = {
        (insn, l, r) match {
            case (IOR, IntValue(0), _) ⇒
                Some("0 | x: bit or operation with 0 left operand is useless")
            case (IOR, IntValue(-1), _) ⇒
                Some("-1 | x: bit or operation with -1 left operand always returns -1")
            case (IOR, _, IntValue(0)) ⇒
                Some("x | 0: bit or operation with 0 right operand is useless")
            case (IOR, _, IntValue(-1)) ⇒
                Some("x | -1: bit or operation with -1 right operand always returns -1")
            case (IAND, IntValue(0), _) ⇒
                Some("0 & x: bit and operation with 0 left operand always returns 0")
            case (IAND, IntValue(-1), _) ⇒
                Some("-1 & x: bit and operation with -1 left operand is useless")
            case (IAND, _, IntValue(0)) ⇒
                Some("x & 0: bit and operation with 0 right operand always returns 0")
            case (IAND, _, IntValue(-1)) ⇒
                Some("x & -1: bit and operation with -1 right operand is useless")
            case _ ⇒
                None
        }
    }
}

/**
 * This analysis reports various useless bit operations, for example `x | 0` (is always
 * `x`) or `x | -1` (is always `-1`).
 *
 * @author Daniel Klauer
 */
class BitNops[S]
        extends MultipleResultsAnalysis[S, LineAndColumnBasedReport[S]] {

    def description: String = "Reports various useless bit operations."

    /**
     * Runs this analysis on the given project.
     *
     * @param project The project to analyze.
     * @param parameters Options for the analysis. Currently unused.
     * @return A list of reports, or an empty list.
     */
    def analyze(
        project: Project[S],
        parameters: Seq[String] = List.empty): Iterable[LineAndColumnBasedReport[S]] = {

        var reports: List[LineAndColumnBasedReport[S]] = List.empty

        for {
            classFile ← project.classFiles
            if !project.isLibraryType(classFile)
            method @ MethodWithBody(body) ← classFile.methods

            // Only run AI if the method body contains the instructions we're looking for.
            //
            // This increases performance when analyzing systems from the Qualitas Corpus,
            // with BitNops, for example:
            //
            // with this check:
            //     ant:    8s
            //   antlr:    7s
            //     aoi:   22s
            // argouml:   44s
            // aspectj: 1m30s
            //   axion:    2s
            //
            // without this check:
            //     ant:  1m30s
            //   antlr:  1m18s
            //     aoi: 15m36s
            // argouml: 21m46s
            // aspectj: 20m24s
            //   axion:    21s
            //
            if body.instructions.exists {
                case IOR | IAND ⇒ true
                case _          ⇒ false
            }
        } {
            val domain = new BitNopsDomain(method)
            val results = BaseAI(classFile, method, domain)

            for {
                pcWithInsn @ ((_, IOR) | (_, IAND)) ← body.associateWithIndex
                pc = pcWithInsn._1
                operands = results.operandsArray(pc)
                if operands != null // can be null for unreached instructions (dead code from AI+Domain's POV)
                l = operands.tail.head
                r = operands.head
                report = domain.checkForNop(pcWithInsn._2, l, r)
                if report.isDefined
            } {
                reports =
                    new LineAndColumnBasedReport(
                        project.source(classFile.thisType),
                        Severity.Info,
                        classFile.thisType,
                        method.descriptor,
                        method.name,
                        body.lineNumber(pc),
                        None,
                        report.get
                    ) :: reports
            }
        }

        reports
    }
}
