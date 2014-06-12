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
package org.opalj
package ai
package domain
package l1

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.FunSpec
import org.scalatest.Matchers
import org.scalatest.ParallelTestExecution

import org.opalj.util.{ Answer, Yes, No, Unknown }
import org.opalj.br.{ ObjectType, ArrayType, IntegerType }
import org.opalj.br.analyses.Project

/**
 * Tests the IntegerRanges Domain.
 *
 * @author Michael Eichberg
 */
@RunWith(classOf[JUnitRunner])
class DefaultIntegerRangesTest
        extends FunSpec
        with Matchers
        with ParallelTestExecution {

    class IntegerRangesTestDomain
            extends Domain
            with DefaultDomainValueBinding
            with ThrowAllPotentialExceptionsConfiguration
            with l0.DefaultTypeLevelLongValues
            with l0.DefaultTypeLevelFloatValues
            with l0.DefaultTypeLevelDoubleValues
            with l0.DefaultReferenceValuesBinding
            with l0.TypeLevelFieldAccessInstructions
            with l0.TypeLevelInvokeInstructions
            with DefaultIntegerRangeValues
            with DefaultHandlingOfMethodResults
            with IgnoreSynchronization
            with PredefinedClassHierarchy
            with RecordLastReturnedValues {

        type Id = String

        def id = "TestDomain"

        override def maxSizeOfIntegerRanges: Long = -(Int.MinValue.toLong) + Int.MaxValue
    }

    describe("central properties of domains that use IntegerRange values") {

        val theDomain = new IntegerRangesTestDomain
        import theDomain._

        it("the representation of the constant integer value 0 should be an IntegerRange value") {
            theDomain.IntegerConstant0 should be(IntegerRange(0, 0))
        }
    }

    describe("operations involving IntegerRange values") {

        val theDomain = new IntegerRangesTestDomain
        import theDomain._

        describe("the behavior of the join operation") {

            it("(join with itself) val ir = IntegerRange(...); ir join ir => \"NoUpdate\"") {
                val v = IntegerRange(0, 0)
                v.join(-1, v) should be(NoUpdate)
            }

            it("(join of disjoint ranges) [Int.MinValue,-1] join [1,Int.MaxValue] => [Int.MinValue,Int.MaxValue]") {
                val v1 = IntegerRange(Int.MinValue, -1)
                val v2 = IntegerRange(1, Int.MaxValue)

                v1.join(-1, v2) should be(StructuralUpdate(IntegerRange(Int.MinValue, Int.MaxValue)))
                v2.join(-1, v1) should be(StructuralUpdate(IntegerRange(Int.MinValue, Int.MaxValue)))
            }

            it("(join of overlapping IntegerRange values) [-1,1] join [0,2] => [-1,2]") {
                val v1 = IntegerRange(-1, 1)
                val v2 = IntegerRange(0, 2)

                v1.join(-1, v2) should be(StructuralUpdate(IntegerRange(-1, 2)))
                v2.join(-1, v1) should be(StructuralUpdate(IntegerRange(-1, 2)))
            }

            it("(join of an IntegerRange value and an IntegerRange value that describes a sub-range) [-1,3] join [0,2] => \"NoUpdate\";  [0,2] join l @ [-1,3] => l ") {
                val v1 = IntegerRange(-1, 3)
                val v2 = IntegerRange(0, 2)

                v1.join(-1, v2) should be(NoUpdate)

                val result = v2.join(-1, v1)
                result should be(StructuralUpdate(v1))
                assert(result.value eq v1)
            }

            it("(join of a point range with a non-overlapping range) [0,0] join [1,Int.MaxValue]") {
                val v1 = IntegerRange(lb = 0, ub = 0)
                val v2 = IntegerRange(lb = 1, ub = 2147483647)
                v1.join(-1, v2) should be(StructuralUpdate(IntegerRange(0, 2147483647)))
                v1.join(-1, v2) should be(StructuralUpdate(IntegerRange(0, 2147483647)))
            }
        }

        describe("the behavior of the summary function") {

            it("it should be able to handle overlapping values") {
                val v1 = IntegerRange(-1, 3)
                val v2 = IntegerRange(0, 2)

                summarize(-1, Iterable(v1, v2)) should be(IntegerRange(-1, 3))
                summarize(-1, Iterable(v2, v1)) should be(IntegerRange(-1, 3))
            }

            it("it should calculate the maximum range for non-overlapping values") {
                val v1 = IntegerRange(2, Int.MaxValue)
                val v2 = IntegerRange(-1, 2)

                summarize(-1, Iterable(v1, v2)) should be(IntegerRange(-1, Int.MaxValue))
                summarize(-1, Iterable(v2, v1)) should be(IntegerRange(-1, Int.MaxValue))
            }

            it("a summary involving some IntegerValue should result in AnIntegerValue") {
                val v1 = IntegerRange(2, Int.MaxValue)
                val v2 = IntegerValue(-1 /*PC*/ )

                summarize(-1, Iterable(v1, v2)) should be(AnIntegerValue())
                summarize(-1, Iterable(v2, v1)) should be(AnIntegerValue())
            }

            it("should calculate the correct summary if Int.MaxValue is involved") {
                val v1 = IntegerRange(lb = 0, ub = 0)
                val v2 = IntegerRange(lb = 1, ub = 2147483647)
                summarize(-1, Iterable(v1, v2)) should be(IntegerRange(0, 2147483647))
                summarize(-1, Iterable(v2, v1)) should be(IntegerRange(0, 2147483647))
            }

            it("should calculate the correct summary if Int.MinValue is involved") {
                val v1 = IntegerRange(lb = Int.MinValue, ub = 0)
                val v2 = IntegerRange(lb = 1, ub = 2)
                summarize(-1, Iterable(v1, v2)) should be(IntegerRange(Int.MinValue, 2))
                summarize(-1, Iterable(v2, v1)) should be(IntegerRange(Int.MinValue, 2))
            }
        }

        describe("the behavior of the relational operators") {

            describe("the behavior of the greater or equal than (>=) operator") {
                it("[3,3] >= [0,2] => Yes") {
                    val p1 = IntegerRange(lb = 3, ub = 3)
                    val i2 = IntegerRange(lb = 0, ub = 2)
                    intIsGreaterThanOrEqualTo(p1, i2) should be(Yes)
                }

                it("[3,3] >= [0,3] => Yes") {
                    val p1 = IntegerRange(lb = 3, ub = 3)
                    val i2 = IntegerRange(lb = 0, ub = 3)
                    intIsGreaterThanOrEqualTo(p1, i2) should be(Yes)
                }

                it("[0,3] >= [3,3] => Unknown") {
                    val p1 = IntegerRange(lb = 3, ub = 3)
                    val i2 = IntegerRange(lb = 0, ub = 3)
                    intIsGreaterThanOrEqualTo(i2, p1) should be(Unknown)
                }

                it("[2,3] >= [1,4] => Unknown") {
                    val i1 = IntegerRange(lb = 2, ub = 3)
                    val i2 = IntegerRange(lb = 1, ub = 4)
                    intIsGreaterThanOrEqualTo(i1, i2) should be(Unknown)
                }

                it("[3,3] >= [4,4] => No") {
                    val p1 = IntegerRange(lb = 3, ub = 3)
                    val p2 = IntegerRange(lb = 4, ub = 4)
                    intIsGreaterThanOrEqualTo(p1, p2) should be(No)
                }

                it("[1,4] >= [2,3] => Unknown") {
                    val i1 = IntegerRange(lb = 1, ub = 4)
                    val i2 = IntegerRange(lb = 2, ub = 3)
                    intIsGreaterThanOrEqualTo(i1, i2) should be(Unknown)
                }
            }

            describe("the behavior of the greater than (>) operator") {

                it("[3,3] > [0,2] => Yes") {
                    val p1 = IntegerRange(lb = 3, ub = 3)
                    val i2 = IntegerRange(lb = 0, ub = 2)
                    intIsGreaterThan(p1, i2) should be(Yes)
                }

                it("[3,300] > [0,2] => Yes") {
                    val p1 = IntegerRange(lb = 3, ub = 300)
                    val i2 = IntegerRange(lb = 0, ub = 2)
                    intIsGreaterThan(p1, i2) should be(Yes)
                }

                it("[3,3] > [0,3] => Unknown") {
                    val p1 = IntegerRange(lb = 3, ub = 3)
                    val i2 = IntegerRange(lb = 0, ub = 3)
                    intIsGreaterThan(p1, i2) should be(Unknown)
                }

                it("[0,3] > [3,3] => Unknown") {
                    val p1 = IntegerRange(lb = 3, ub = 3)
                    val i2 = IntegerRange(lb = 0, ub = 3)
                    intIsGreaterThan(i2, p1) should be(Unknown)
                }

                it("[2,3] > [1,4] => Unknown") {
                    val i1 = IntegerRange(lb = 2, ub = 3)
                    val i2 = IntegerRange(lb = 1, ub = 4)
                    intIsGreaterThan(i1, i2) should be(Unknown)
                }

                it("[3,3] > [4,4] => No") {
                    val p1 = IntegerRange(lb = 3, ub = 3)
                    val p2 = IntegerRange(lb = 4, ub = 4)
                    intIsGreaterThan(p1, p2) should be(No)
                }

                it("[3,3] > [3,3] => No") {
                    val p1 = IntegerRange(lb = 3, ub = 3)
                    val p2 = IntegerRange(lb = 3, ub = 3)
                    intIsGreaterThan(p1, p2) should be(No)
                    intIsGreaterThan(p1, p1) should be(No)
                }

            }

            describe("the behavior of the equals (==) operator") {

                it("[3,3] == [3,3]") {
                    val p1 = IntegerRange(lb = 3, ub = 3)
                    val p2 = IntegerRange(lb = 3, ub = 3)
                    intAreEqual(p1, p2) should be(Yes)
                    intAreEqual(p2, p1) should be(Yes)
                    intAreEqual(p1, p1) should be(Yes) // reflexive
                }

                it("[2,2] == [3,3]") {
                    val p1 = IntegerRange(lb = 2, ub = 2)
                    val p2 = IntegerRange(lb = 3, ub = 3)
                    intAreEqual(p1, p2) should be(No)
                    intAreEqual(p2, p1) should be(No) // reflexive
                }

                it("[0,3] == [3,3]") {
                    val p1 = IntegerRange(lb = 0, ub = 3)
                    val p2 = IntegerRange(lb = 3, ub = 3)
                    intAreEqual(p1, p2) should be(Unknown)
                    intAreEqual(p2, p1) should be(Unknown) // reflexive
                }

                it("[0,3] == [4,10]") {
                    val p1 = IntegerRange(lb = 0, ub = 3)
                    val p2 = IntegerRange(lb = 4, ub = 10)
                    intAreEqual(p1, p2) should be(No)
                    intAreEqual(p2, p1) should be(No) // reflexive
                }
            }
        }

    }

    describe("Using IntegerRange values") {

        val testJAR = "classfiles/ai.jar"
        val testFolder = org.opalj.br.TestSupport.locateTestResources(testJAR, "ai")
        val testProject = org.opalj.br.analyses.Project(testFolder)
        val IntegerValues = testProject.classFile(ObjectType("ai/domain/IntegerValuesFrenzy")).get

        it("should be able to adapt (<) the bounds of an IntegerRange value in the presences of aliasing") {
            val domain = new IntegerRangesTestDomain
            val method = IntegerValues.findMethod("aliasingMax5").get
            val result = BaseAI(IntegerValues, method, domain)
            if (domain.allReturnedValues.size != 2)
                fail("expected two results; found: "+domain.allReturnedValues)

            val summary = domain.summarize(-1, domain.allReturnedValues.toIterable.map(_._2))
            summary should be(domain.IntegerRange(Int.MinValue, 5))
        }

        it("should be able to adapt (<=)the bounds of an IntegerRange value in the presences of aliasing") {
            val domain = new IntegerRangesTestDomain
            val method = IntegerValues.findMethod("aliasingMax6").get
            val result = BaseAI(IntegerValues, method, domain)
            if (domain.allReturnedValues.size != 2)
                fail("expected two results; found: "+domain.allReturnedValues)

            val summary = domain.summarize(-1, domain.allReturnedValues.toIterable.map(_._2))
            summary should be(domain.IntegerRange(Int.MinValue, 6))
        }

        it("should be able to adapt (>=)the bounds of an IntegerRange value in the presences of aliasing") {
            val domain = new IntegerRangesTestDomain
            val method = IntegerValues.findMethod("aliasingMinM1").get
            val result = BaseAI(IntegerValues, method, domain)
            if (domain.allReturnedValues.size != 2)
                fail("expected two results; found: "+domain.allReturnedValues)

            val summary = domain.summarize(-1, domain.allReturnedValues.toIterable.map(_._2))
            summary should be(domain.IntegerRange(-1, Int.MaxValue))
        }

        it("should be able to adapt (>)the bounds of an IntegerRange value in the presences of aliasing") {
            val domain = new IntegerRangesTestDomain
            val method = IntegerValues.findMethod("aliasingMin0").get
            val result = BaseAI(IntegerValues, method, domain)
            if (domain.allReturnedValues.size != 2)
                fail("expected two results; found: "+domain.allReturnedValues)

            val summary = domain.summarize(-1, domain.allReturnedValues.toIterable.map(_._2))
            summary should be(domain.IntegerRange(0, Int.MaxValue))
        }

        ignore("should be able to collect a switch statement's cases and use that information to calculate a result") {
            val domain = new IntegerRangesTestDomain
            val method = IntegerValues.findMethod("someSwitch").get
            val result = BaseAI(IntegerValues, method, domain)
            if (domain.allReturnedValues.size != 1)
                fail("expected one result; found: "+domain.allReturnedValues)

            domain.allReturnedValues.head._2 should be(domain.IntegerRange(0, 8))
        }

        it("should be able to detect contradicting conditions") {
            val domain = new IntegerRangesTestDomain
            val method = IntegerValues.findMethod("someComparisonThatReturns5").get
            val result = BaseAI(IntegerValues, method, domain)
            if (domain.allReturnedValues.size != 2)
                fail("expected one result; found: "+domain.allReturnedValues)

            val summary = domain.summarize(-1, domain.allReturnedValues.toIterable.map(_._2))
            summary should be(domain.IntegerRange(5, 5))
        }

        it("should be able to track integer values such that it is possible to potentially identify an array index out of bounds exception") {
            val domain = new IntegerRangesTestDomain
            val method = IntegerValues.findMethod("array10").get
            val result = BaseAI(IntegerValues, method, domain)
            if (domain.allReturnedValues.size != 1)
                fail("expected one result; found: "+domain.allReturnedValues)

            domain.allReturnedValues.head._2 abstractsOver (
                domain.InitializedArrayValue(2, List(10), ArrayType(IntegerType))
            ) should be(true)

            // get the loop counter at the "icmple instruction" which controls the 
            // loops that initializes the array
            result.operandsArray(20).tail.head should be(domain.IntegerRange(0, 11))
        }
    }
}
