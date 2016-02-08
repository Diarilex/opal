/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2016
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
package fpcf

import org.junit.runner.RunWith
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.immutable
import scala.collection.{Set ⇒ SomeSet}
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers
import org.scalatest.FunSpec
import org.opalj.log.GlobalLogContext
import org.scalatest.BeforeAndAfterEach

/**
 * Tests the property store.
 *
 * @author Michael Eichberg
 */
@RunWith(classOf[JUnitRunner])
class PropertyStoreTest extends FunSpec with Matchers with BeforeAndAfterEach {

    //**********************************************************************************************
    //
    // TEST FIXTURE
    //

    final val stringEntities: List[String] = List(
        "a", "b", "c",
        "aa", "bb", "cc",
        "ab", "bc", "cd",
        "aaa",
        "aea",
        "aabbcbbaa",
        "aaaffffffaaa", "aaaffffffffffffffffaaa"
    )
    val psStrings: PropertyStore = {
        PropertyStore(stringEntities, () ⇒ false, debug = false)(GlobalLogContext)
    }

    final val PalindromeKey = {
        PropertyKey.create[PalindromeProperty](
            "Palindrome",
            (ps: PropertyStore, e: Entity) ⇒ ???,
            (ps: PropertyStore, epks: Iterable[SomeEPK]) ⇒ ???
        )
    }
    sealed trait PalindromeProperty extends Property {
        type Self = PalindromeProperty
        def key = PalindromeKey
        def isRefineable = false
    }
    // Multiple properties can share the same property instance
    case object Palindrome extends PalindromeProperty
    case object NoPalindrome extends PalindromeProperty

    final val BitsKey = {
        PropertyKey.create[BitsProperty](
            "Bits",
            (ps: PropertyStore, e: Entity) ⇒ ???,
            (ps: PropertyStore, epks: Iterable[SomeEPK]) ⇒ ???
        )
    }
    case class BitsProperty(bits: Int) extends Property {
        type Self = BitsProperty
        def key = BitsKey
        def isRefineable = false
    }

    // HERE: we consider a palindrome to be a super palindrome if the lead is itself a 
    //         a palindrom. E.g. aa => Lead: a => Palindrome => aa => SuperPalindrome
    final val SuperPalindromeKey = {
        PropertyKey.create[SuperPalindromeProperty](
            "SuperPalindrome",
            (ps: PropertyStore, e: Entity) ⇒ ???,
            (ps: PropertyStore, epks: Iterable[SomeEPK]) ⇒ ???
        )
    }
    sealed trait SuperPalindromeProperty extends Property {
        type Self = SuperPalindromeProperty
        def key = SuperPalindromeKey
        def isRefineable = false
    }
    // Multiple properties can share the same property instance
    case object SuperPalindrome extends SuperPalindromeProperty
    case object NoSuperPalindrome extends SuperPalindromeProperty

    final val StringLengthKey: PropertyKey[StringLength] = {
        PropertyKey.create(
            "StringLength",
            (ps: PropertyStore, e: Entity) ⇒ ???,
            (ps: PropertyStore, epks: Iterable[SomeEPK]) ⇒ ???
        )
    }
    case class StringLength(length: Int) extends Property {
        type Self = StringLength
        def key = StringLengthKey
        def isRefineable = false
    }

    object EvenNumberOfChars extends SetProperty[String]

    object StringsWithAtLeastTwoChars extends SetProperty[String]

    class Node(val name: String, val targets: mutable.Set[Node] = mutable.Set.empty) {
        override def hashCode: Int = name.hashCode()
        override def equals(other: Any): Boolean = other match {
            case that: Node ⇒ this.name equals that.name
            case _          ⇒ false
        }
        //override def toString: String = s"""$name->{${targets.map(_.name).mkString(",")}}"""
        override def toString: String = name
    }
    object Node { def apply(name: String) = new Node(name) }
    val nodeA = Node("a")
    val nodeB = Node("b")
    val nodeC = Node("c")
    val nodeD = Node("d")
    val nodeE = Node("e")
    val nodeR = Node("R")
    nodeA.targets += nodeB // the graph:
    nodeB.targets += nodeC // a -> b -> c
    nodeB.targets += nodeD //        ↘︎ d
    nodeD.targets += nodeD //           d ⟲
    nodeD.targets += nodeE //           d -> e
    nodeE.targets += nodeR //                e -> r
    nodeR.targets += nodeB //       ↖︎-----------↵︎
    val nodeEntities = List[Node](nodeA, nodeB, nodeC, nodeD, nodeE, nodeR)
    val psNodes: PropertyStore = {
        PropertyStore(nodeEntities, () ⇒ false, debug = true)(GlobalLogContext)
    }

    final val ReachableNodesKey: PropertyKey[ReachableNodes] = {
        PropertyKey.create(
            "ReachableNodes",
            (ps: PropertyStore, e: Entity) ⇒ throw new UnknownError /*IDIOM IF NO FALLBACK IS EXPECTED/SUPPORTED*/ ,
            (ps: PropertyStore, epks: Iterable[SomeEPK]) ⇒ {
                //                val allReachableNodes = epks.foldLeft(Set.empty[Node]) { (c, epk) ⇒
                //                    c ++ ps(epk.e, ReachableNodesKey /* <=> epk.pk */ ).get.nodes
                //                }
                //                val epk = epks.head
                //                Iterable(Result(epk.e, ReachableNodes(allReachableNodes)))
                // Cycles are completely handled by the analysis;
                // i.e., we do not need to lift the results!
                Iterable.empty
            }
        )
    }
    case class ReachableNodes(nodes: scala.collection.Set[Node]) extends Property {
        type Self = ReachableNodes
        def key = ReachableNodesKey
        def isRefineable = true
    }
    object NoReachableNodes extends ReachableNodes(Set.empty) {
        override def toString: String = "NoReachableNodes"
    }

    if (NoReachableNodes != NoReachableNodes) fail("comparison of ReachableNodes properties failed")

    override def afterEach(): Unit = {
        psStrings.waitOnPropertyComputationCompletion(false)
        psStrings.reset()

        psNodes.waitOnPropertyComputationCompletion(false)
        psNodes.reset()
    }

    //**********************************************************************************************
    //
    // TESTS

    describe("the property store") {

        it("should be in the deault state after calling reset") {
            val ps = psStrings

            // let's fill the property store with:
            //  - an entity based property and 
            //  - a set property and
            //  - an on property derivation function
            ps.onPropertyChange(PalindromeKey) { (e, p) ⇒
                if (p == Palindrome && e.toString().size % 2 == 0)
                    ps.add(EvenNumberOfChars)(e.toString())
            }
            ps << { e: Entity ⇒
                val property = if (e.toString.reverse == e.toString) Palindrome else NoPalindrome
                ImmediateResult(e, property)
            }
            ps.onPropertyDerivation(EvenNumberOfChars)((e) ⇒ {})
            ps.waitOnPropertyComputationCompletion(true)

            // let's test the reset method
            ps.entities(EvenNumberOfChars) should not be ('isEmpty)
            ps.entities { x ⇒ true } should not be ('isEmpty)
            ps.reset()
            ps.entities(EvenNumberOfChars) should be('isEmpty)
            ps.entities { x ⇒ true } should be('isEmpty)

        }
    }

    it("should be possible to interrupt the computations") {
        val EntitiesCount = 100000
        val entities: List[java.lang.Integer] = (1 to EntitiesCount).map(Integer.valueOf).toList

        val triggeredComputations = new java.util.concurrent.atomic.AtomicInteger(0)
        @volatile var doInterrupt = false
        val ps = PropertyStore(entities, () ⇒ doInterrupt, debug = false)(GlobalLogContext)
        ps << { e: Entity ⇒
            triggeredComputations.incrementAndGet()
            Thread.sleep(50)
            ImmediateResult(e, BitsProperty(Integer.bitCount(e.asInstanceOf[Integer])))
        }
        doInterrupt = true
        ps.waitOnPropertyComputationCompletion(false)

        if (triggeredComputations.get == EntitiesCount) fail("interrupting the computations failed")
    }

    describe("set properties") {

        it("an onPropertyDerivation function should be called if entities are associated with the property after the registration of the function") {
            val ps = psStrings
            val results = new java.util.concurrent.ConcurrentLinkedQueue[String]()

            ps.onPropertyDerivation(EvenNumberOfChars)(results.add(_))

            for (e ← stringEntities if (e.size % 2) == 1) { ps.add(EvenNumberOfChars)(e) }
            ps.waitOnPropertyComputationCompletion(true)

            val expected = Set("aabbcbbaa", "a", "b", "c", "aaa", "aea")
            ps.entities(EvenNumberOfChars).asScala should be(expected)
            results.asScala.toSet should be(expected)
        }

        it("an onPropertyDerivation function should be called for all entities that already have a respective property when the function is registered") {
            val ps = psStrings
            val results = new java.util.concurrent.ConcurrentLinkedQueue[String]()

            for (e ← stringEntities if (e.size % 2) == 1) { ps.add(EvenNumberOfChars)(e) }

            ps.onPropertyDerivation(EvenNumberOfChars)(results.add(_))

            ps.waitOnPropertyComputationCompletion(true)

            val expected = Set("aabbcbbaa", "a", "b", "c", "aaa", "aea")
            ps.entities(EvenNumberOfChars).asScala should be(expected)
            results.asScala.toSet should be(expected)
        }

        it("an onPropertyDerivation function should be called for all entities that already have a respective property and also for those that are associated with it afterwards") {
            val ps = psStrings
            val results = new java.util.concurrent.ConcurrentLinkedQueue[String]()

            for (e ← stringEntities if e.size == 1) { ps.add(EvenNumberOfChars)(e) }
            ps.onPropertyDerivation(EvenNumberOfChars)(results.add(_))
            for (e ← stringEntities if e.size % 2 == 1 && e.size != 1) { ps.add(EvenNumberOfChars)(e) }

            ps.waitOnPropertyComputationCompletion(true)

            val expected = Set("aabbcbbaa", "a", "b", "c", "aaa", "aea")
            ps.entities(EvenNumberOfChars).asScala should be(expected)
            results.asScala.toSet should be(expected)
        }

        it("deriving the same property multiple times should have no effect") {
            val ps = psStrings
            val results = new java.util.concurrent.ConcurrentLinkedQueue[String]()

            for (e ← stringEntities if (e.size % 2) == 1) { ps.add(EvenNumberOfChars)(e) }
            for (e ← stringEntities if (e.size % 2) == 1) { ps.add(EvenNumberOfChars)(e) }

            ps.onPropertyDerivation(EvenNumberOfChars)(results.add(_))

            ps.waitOnPropertyComputationCompletion(true)

            val expected = Set("aabbcbbaa", "a", "b", "c", "aaa", "aea")
            ps.entities(EvenNumberOfChars).asScala should be(expected)
            results.asScala.toSet should be(expected)
        }

        it("should be possible to implement properties that are calculated when the base information becomes available") {
            val ps = psStrings

            // In this scenario we only associate the palindrome property with elements
            // that contain at least two chars

            ps.onPropertyDerivation(StringsWithAtLeastTwoChars) { (s: String) ⇒
                ps.handleResult(ImmediateResult(
                    s,
                    if (s.reverse == s) Palindrome else NoPalindrome
                ))
            }

            // "run the initial analysis"
            for (e ← stringEntities if (e.size > 1)) { ps.add(StringsWithAtLeastTwoChars)(e) }

            ps.waitOnPropertyComputationCompletion(true)

            ps.entities(p ⇒ p == NoPalindrome).toSet should be(Set("ab", "bc", "cd"))
            ps.entities(p ⇒ p == Palindrome).toSet should be(
                Set("aa", "bb", "cc", "aaa", "aea", "aabbcbbaa",
                    "aaaffffffaaa", "aaaffffffffffffffffaaa")
            )
        }

        it("should be possible to chain property computations") {
            val ps = psStrings

            // In this scenario we only associate the palindrome property with elements
            // that contain at least two chars
            ps.onPropertyDerivation(StringsWithAtLeastTwoChars) { (s: String) ⇒
                if (s.size % 2 == 0) ps.add(EvenNumberOfChars)(s)
            }
            ps.onPropertyDerivation(EvenNumberOfChars) { (s: String) ⇒
                ps.handleResult(ImmediateResult(
                    s,
                    if (s.reverse == s) Palindrome else NoPalindrome
                ))
            }

            // "run the initial analysis"
            for (e ← stringEntities if (e.size > 1)) { ps.add(StringsWithAtLeastTwoChars)(e) }

            ps.waitOnPropertyComputationCompletion(true)

            ps.entities(p ⇒ p == NoPalindrome).toSet should be(Set("ab", "bc", "cd"))
            ps.entities(p ⇒ p == Palindrome).toSet should be(
                Set("aa", "bb", "cc", "aaaffffffaaa", "aaaffffffffffffffffaaa")
            )
        }
    }

    describe("per entity properties") {

        describe("properties") {

            it("every element can have an individual property instance") {
                val ps = psStrings

                ps << { e: Entity ⇒ ImmediateResult(e, StringLength(e.toString.length())) }

                ps.waitOnPropertyComputationCompletion(true)

                stringEntities.foreach { e ⇒ ps(e, StringLengthKey).get.length should be(e.length()) }
            }
        }

        describe("computations for groups of entities") {

            it("should be executed for each group in parallel") {
                import scala.collection.mutable

                val ps = psStrings

                ps.execute({ case s: String ⇒ s }, { (s: String) ⇒ s.length }) { (k, es) ⇒
                    es.map(e ⇒ EP(e, StringLength(k)))
                }

                ps.waitOnPropertyComputationCompletion(true)

                stringEntities.foreach { e ⇒
                    ps(e, StringLengthKey).get.length should be(e.length())
                }
            }

        }

        describe("computations depending on a specific property") {

            it("should be triggered for every entity that already has the respective property") {
                import scala.collection.mutable
                val ps = psStrings
                val results = mutable.Map.empty[Property, mutable.Set[String]]

                ps << { e: Entity ⇒
                    if (e.toString.reverse == e.toString())
                        ImmediateResult(e, Palindrome)
                    else
                        ImmediateResult(e, NoPalindrome)
                }

                ps.onPropertyChange(PalindromeKey)((e, p) ⇒ results.synchronized {
                    results.getOrElseUpdate(p, mutable.Set.empty[String]).add(e.toString())
                })

                ps.waitOnPropertyComputationCompletion(true)

                val expectedResult = Set(
                    "aabbcbbaa", "aa",
                    "c", "aea", "aaa",
                    "aaaffffffaaa",
                    "aaaffffffffffffffffaaa",
                    "cc", "a", "bb", "b"
                )
                results(Palindrome) should be(expectedResult)
            }

            it("should be triggered for every entity that is associated with the respective property after registering the onPropertyChange function") {
                import scala.collection.mutable
                val ps = psStrings
                val results = mutable.Map.empty[Property, mutable.Set[String]]

                ps.onPropertyChange(PalindromeKey)((e, p) ⇒ results.synchronized {
                    results.getOrElseUpdate(p, mutable.Set.empty).add(e.toString())
                })

                ps << { e: Entity ⇒
                    if (e.toString.reverse == e.toString())
                        ImmediateResult(e, Palindrome)
                    else
                        ImmediateResult(e, NoPalindrome)
                }

                ps.waitOnPropertyComputationCompletion(true)

                val expectedResult = Set(
                    "aabbcbbaa", "aa", "c", "aea",
                    "aaa", "aaaffffffaaa",
                    "aaaffffffffffffffffaaa", "cc", "a", "bb", "b"
                )
                results(Palindrome) should be(expectedResult)
            }
        }

        describe("computations waiting on a specific property") {

            it("should be triggered for externally computed and then added properties") {
                import scala.collection.mutable
                val ps = psStrings
                val results = mutable.Map.empty[Property, mutable.Set[String]]

                ps.onPropertyChange(PalindromeKey)((e, p) ⇒ results.synchronized {
                    results.getOrElseUpdate(p, mutable.Set.empty).add(e.toString())
                })

                val palindromeProperties = stringEntities.map { e ⇒
                    EP(e, if (e == e.reverse) Palindrome else NoPalindrome)
                }
                ps.set(palindromeProperties) // <= externally computed

                ps.waitOnPropertyComputationCompletion(true)

                val expectedPalindromes = Set(
                    "aabbcbbaa", "aa", "c", "aea",
                    "aaa", "aaaffffffaaa",
                    "aaaffffffffffffffffaaa", "cc", "a", "bb", "b"
                )
                results(Palindrome) should be(expectedPalindromes)
                results(NoPalindrome) should be(Set("ab", "bc", "cd"))
            }

            it("should be triggered whenever the property is updated and supports an incremental update based on the given property") {
                import scala.collection.mutable
                val ps = psNodes

                var runs = 0
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 5 * 60 * 1000) {
                    runs += 1
                    /* The following analysis only uses the new information given to it and updates
                 	 * the set of observed dependees.
                 	 */
                    def analysis(n: Node): PropertyComputationResult = {
                        val nTargets = n.targets
                        if (nTargets.isEmpty)
                            return ImmediateResult(n, NoReachableNodes);

                        val allDependees: mutable.Set[Node] = nTargets.clone
                        var dependeePs: Traversable[EOptionP[_ <: ReachableNodes]] = ps(allDependees, ReachableNodesKey)

                        // incremental computation
                        def c(
                            dependeeE:  Entity,
                            dependeeP:  Property,
                            updateType: UpdateType
                        ): PropertyComputationResult = {
                            // Get the set of currently reachable nodes:
                            val alreadyReachableNodes: SomeSet[Node] =
                                ps(n, ReachableNodesKey) match {
                                    case Some(ReachableNodes(reachableNodes)) ⇒ reachableNodes
                                    case None                                 ⇒ Set.empty
                                }
                            // Get the set of nodes reached by the dependee:
                            val ReachableNodes(depeendeeReachableNodes) = dependeeP

                            // Compute the new set of reachable nodes:
                            val newReachableNodes = alreadyReachableNodes ++ depeendeeReachableNodes
                            val newP = ReachableNodes(newReachableNodes)

                            // Adapt the set of dependeePs to ensure termination
                            dependeePs = dependeePs.filter { _.e ne dependeeE }
                            if (updateType != FinalUpdate) {
                                dependeePs = dependeePs ++ Traversable(EP(dependeeE, dependeeP.asInstanceOf[ReachableNodes]))
                            }
                            if (dependeePs.nonEmpty)
                                IntermediateResult(n, newP, dependeePs, c)
                            else
                                Result(n, newP)
                        }

                        // initial computation
                        val reachableNodes =
                            dependeePs.foldLeft(allDependees.clone) { (reachableNodes, dependee) ⇒
                                if (dependee.hasProperty)
                                    reachableNodes ++ dependee.p.nodes
                                else
                                    reachableNodes
                            }
                        val intermediateP = ReachableNodes(reachableNodes)
                        IntermediateResult(n, intermediateP, dependeePs, c)
                    }

                    ps <||< ({ case n: Node ⇒ n }, analysis)
                    ps.waitOnPropertyComputationCompletion(true)

                    println(ps.toString(true))
                    // the graph:
                    // a -> b -> c
                    //      b -> d
                    //           d ⟲
                    //           d -> e
                    //                e -> r
                    //       ↖︎----------< r
                    ps(nodeA, ReachableNodesKey) should be(Some(ReachableNodes(Set(nodeB, nodeC, nodeD, nodeE, nodeR))))
                    ps(nodeB, ReachableNodesKey) should be(Some(ReachableNodes(Set(nodeB, nodeC, nodeD, nodeE, nodeR))))
                    ps(nodeC, ReachableNodesKey) should be(Some(ReachableNodes(Set())))
                    ps(nodeD, ReachableNodesKey) should be(Some(ReachableNodes(Set(nodeB, nodeC, nodeD, nodeE, nodeR))))
                    ps(nodeE, ReachableNodesKey) should be(Some(ReachableNodes(Set(nodeB, nodeC, nodeD, nodeE, nodeR))))
                    ps(nodeR, ReachableNodesKey) should be(Some(ReachableNodes(Set(nodeB, nodeC, nodeD, nodeE, nodeR))))

                    psNodes.waitOnPropertyComputationCompletion(false)
                    psNodes.reset()
                }
                info(s"executed the test $runs times")
            }

            it("should be triggered whenever the property is updated and supports a complete update based on a query of the store's value") {
                import scala.collection.mutable
                val ps = psNodes

                /* The following analysis collects all nodes a node is connected with (i.e.,
                 * it computes the transitive closure).
                 */
                def analysis(n: Node): PropertyComputationResult = {
                    val nTargets = n.targets
                    if (nTargets.isEmpty)
                        return ImmediateResult(n, NoReachableNodes);

                    val remainingDependees: mutable.Set[Node] = nTargets.clone
                    def c(
                        dependeeE:  Entity,
                        dependeeP:  Property,
                        updateType: UpdateType
                    ): PropertyComputationResult = {
                        // Get the set of currently reachable nodes.
                        val alreadyReachableNodes: SomeSet[Node] =
                            ps(n, ReachableNodesKey) match {
                                case Some(ReachableNodes(reachableNodes)) ⇒ reachableNodes
                                case None                                 ⇒ Set.empty
                            }
                        // Whenever we continue a computation we have have to query
                        // all relevant entities about their "current" properties.
                        // This is strictly necessary to ensure termination because the
                        // property store uses this information to decide whether to immediately
                        // continue the computation or not.
                        val dependeePs = ps(remainingDependees, ReachableNodesKey)
                        val dependeesReachableNodes =
                            dependeePs.foldLeft(remainingDependees.clone) { (reachableNodes, dependee) ⇒
                                if (dependee.hasProperty)
                                    reachableNodes ++ dependee.p.nodes
                                else
                                    reachableNodes
                            }

                        val newReachableNodes = alreadyReachableNodes ++ dependeesReachableNodes
                        val newP = ReachableNodes(newReachableNodes)

                        if (updateType == FinalUpdate) {
                            remainingDependees -= dependeeE.asInstanceOf[Node]
                            val filteredDependeePs = dependeePs.filter { _.e ne dependeeE }
                            if (filteredDependeePs.nonEmpty)
                                IntermediateResult(n, newP, filteredDependeePs, c)
                            else
                                Result(n, newP)
                        } else {
                            IntermediateResult(n, newP, dependeePs, c)
                        }
                    }

                    // initial computation
                    val dependeePs = ps(remainingDependees, ReachableNodesKey)
                    val reachableNodes =
                        dependeePs.foldLeft(remainingDependees.clone) { (reachableNodes, dependee) ⇒
                            if (dependee.hasProperty)
                                reachableNodes ++ dependee.p.nodes
                            else
                                reachableNodes
                        }
                    val intermediateP = ReachableNodes(reachableNodes)
                    IntermediateResult(n, intermediateP, dependeePs, c)
                }

                ps <||< ({ case n: Node ⇒ n }, analysis)
                ps.waitOnPropertyComputationCompletion(true)

                println(ps.toString(true))
                // the graph:
                // a -> b -> c
                //      b -> d
                //           d ⟲
                //           d -> e
                //                e -> r
                //       ↖︎----------< r
                ps(nodeA, ReachableNodesKey) should be(Some(ReachableNodes(Set(nodeB, nodeC, nodeD, nodeE, nodeR))))
                ps(nodeB, ReachableNodesKey) should be(Some(ReachableNodes(Set(nodeB, nodeC, nodeD, nodeE, nodeR))))
                ps(nodeC, ReachableNodesKey) should be(Some(ReachableNodes(Set())))
                ps(nodeD, ReachableNodesKey) should be(Some(ReachableNodes(Set(nodeB, nodeC, nodeD, nodeE, nodeR))))
                ps(nodeE, ReachableNodesKey) should be(Some(ReachableNodes(Set(nodeB, nodeC, nodeD, nodeE, nodeR))))
                ps(nodeR, ReachableNodesKey) should be(Some(ReachableNodes(Set(nodeB, nodeC, nodeD, nodeE, nodeR))))
            }

        }

        describe("lazy computations of an entity's property") {

            it("should not be executed immediately") {
                import scala.collection.mutable

                val ps = psStrings
                @volatile var stringLengthTriggered = false
                val stringLengthPC: PropertyComputation[String] = (e: String) ⇒ {
                    stringLengthTriggered = true
                    ImmediateResult(e, StringLength(e.size))
                }
                ps <<? (StringLengthKey, stringLengthPC)

                if (stringLengthTriggered) fail("computation is already triggered")
                ps.waitOnPropertyComputationCompletion(true)
                if (stringLengthTriggered) fail("computation is already triggered")
            }

            it("should be executed (at most once) when the property is requested") {
                import scala.collection.mutable

                val ps = psStrings
                @volatile var stringLengthTriggered = false
                val stringLengthPC: PropertyComputation[String] = (e: String) ⇒ {
                    if (stringLengthTriggered) fail("computation is already triggered")
                    stringLengthTriggered = true
                    ImmediateResult(e, StringLength(e.size))
                }
                ps <<? (StringLengthKey, stringLengthPC)

                val palindromePC: PropertyComputation[String] = (e: String) ⇒ {
                    val s = e.toString
                    ImmediateResult(e, if (s.reverse == s) Palindrome else NoPalindrome)
                }
                ps <<? (PalindromeKey, palindromePC)

                stringLengthTriggered should be(false)
                ps.waitOnPropertyComputationCompletion(true)

                ps.properties("a") should be(Nil)

                ps("a", StringLengthKey) should be(None) // this should trigger the computation
                ps("a", StringLengthKey) // but hopefully only once (tested using "triggered")

                @volatile var superPalindromeCompleted = false
                // triggers the computation of "PalindromeProperty
                val pcr = ps.allHaveProperty(
                    "aa", SuperPalindromeKey,
                    List("a"), Palindrome
                ) { aIsPalindrome ⇒
                        superPalindromeCompleted = true
                        Result("aa", if (aIsPalindrome) SuperPalindrome else NoSuperPalindrome)
                    }
                pcr shouldBe a[SuspendedPC[_]]

                // We can explicitly add results though this is generally not required in a well
                // written analysis.
                ps.handleResult(pcr)

                ps.waitOnPropertyComputationCompletion(true)

                ps("a", StringLengthKey) should be(Some(StringLength(1)))
                ps("a", PalindromeKey) should be(Some(Palindrome))
                ps("aa", SuperPalindromeKey) should be(Some(SuperPalindrome))
                superPalindromeCompleted should be(true)
            }

            it("should be triggered for all that are queried using \"allHaveProperty\"") {
                val ps = psStrings

                val palindromePC: PropertyComputation[String] = (e: String) ⇒ {
                    val s = e.toString
                    ImmediateResult(e, if (s.reverse == s) Palindrome else NoPalindrome)
                }
                ps <<? (PalindromeKey, palindromePC)

                // triggers the computation of "PalindromeProperty
                val pcr =
                    ps.allHaveProperty(
                        "aaa", SuperPalindromeKey,
                        List("a", "aa"), Palindrome
                    ) { arePalindromes ⇒
                            Result("aaa", if (arePalindromes) SuperPalindrome else throw new UnknownError)
                        }
                pcr shouldBe a[SuspendedPC[_]]
                ps.handleResult(pcr)
                ps.waitOnPropertyComputationCompletion(true)

                ps("a", PalindromeKey) should be(Some(Palindrome))
                ps("aa", PalindromeKey) should be(Some(Palindrome))
                ps("aaa", SuperPalindromeKey) should be(Some(SuperPalindrome))
            }

            it("should be triggered in case of a \"require\" dependency") {
                val ps = psStrings

                val palindromePC: PropertyComputation[String] = (e: String) ⇒ {
                    val s = e.toString
                    ImmediateResult(e, if (s.reverse == s) Palindrome else NoPalindrome)
                }
                ps <<? (PalindromeKey, palindromePC)

                // triggers the computation of "PalindromeProperty
                val pcr = ps.require("aaa", SuperPalindromeKey, "a", PalindromeKey) { (e, p) ⇒
                    Result("aaa", if (p == Palindrome) SuperPalindrome else NoSuperPalindrome)
                }
                pcr shouldBe a[SuspendedPC[_]]
                ps.handleResult(pcr)
                ps.waitOnPropertyComputationCompletion(true)

                ps("a", PalindromeKey) should be(Some(Palindrome))
                ps("aaa", SuperPalindromeKey) should be(Some(SuperPalindrome))
            }

        }

        describe("direct computations of an entity's property") {

            it("should not be executed if they are not explicitly queried") {
                import scala.collection.mutable

                val ps = psStrings
                val stringLengthPC = (e: Entity) ⇒ { StringLength(e.toString.size) }
                ps <<! (StringLengthKey, stringLengthPC)

                ps.entities { p ⇒ true } should be('empty)
                ps.waitOnPropertyComputationCompletion(true)
                ps.entities { p ⇒ true } should be('empty)
            }

            it("should return the cached value") {
                import scala.collection.mutable

                val ps = psStrings
                val stringLengthPC = (e: Entity) ⇒ { StringLength(e.toString.size) }
                ps <<! (StringLengthKey, stringLengthPC)

                val first = ps("a", StringLengthKey).get
                val second = ps("a", StringLengthKey).get
                first should be theSameInstanceAs (second)
            }

            it("should be immediately executed and returned when the property is requested") {
                import scala.collection.mutable

                val ps = psStrings
                val stringLengthPC = (e: Entity) ⇒ { StringLength(e.toString.size) }
                ps <<! (StringLengthKey, stringLengthPC)

                ps("a", StringLengthKey) should be(Some(StringLength(1)))
                ps("aea", StringLengthKey) should be(Some(StringLength(3)))

                // test that the other computations are not immediately executed were executed
                ps.entities(p ⇒ true).toSet should be(Set("a", "aea"))
            }

            it("should not be triggered for those that are queried using \"allHaveProperty\" if the query fails early") {
                val ps = psStrings

                val palindromePC = (e: Entity) ⇒ {
                    val s = e.toString
                    if (s.reverse == s) Palindrome else NoPalindrome
                }
                ps <<! (PalindromeKey, palindromePC)

                // triggers the computation of PalindromeProperty for bc...
                val pcr = ps.allHaveProperty("aaa", SuperPalindromeKey, List("bc", "a", "aa"), Palindrome) { arePalindromes ⇒
                    Result("aaa", if (arePalindromes) SuperPalindrome else NoSuperPalindrome)
                }
                pcr shouldBe a[Result]
                ps.handleResult(pcr)
                ps.waitOnPropertyComputationCompletion(true)

                ps.properties("bc") should be(List(NoPalindrome))
                ps("aaa", SuperPalindromeKey) should be(Some(NoSuperPalindrome))
                ps.properties("a") should be(Nil)
                ps.properties("aa") should be(Nil)
            }

            it("can depend on other direct property computations (chaining of direct property computations)") {
                import scala.collection.mutable

                val ps = psStrings
                val stringLengthPC = (e: Entity) ⇒ { StringLength(e.toString.size) }
                ps <<! (StringLengthKey, stringLengthPC)

                val palindromePC = (e: Entity) ⇒ {
                    // here we assume that a palindrome must have more than one char
                    if (ps(e, StringLengthKey).get.length > 1 &&
                        e.toString == e.toString().reverse)
                        Palindrome
                    else
                        NoPalindrome
                }
                ps <<! (PalindromeKey, palindromePC)

                ps("a", StringLengthKey) should be(Some(StringLength(1)))
                ps("a", PalindromeKey) should be(Some(NoPalindrome))
                ps("aea", StringLengthKey) should be(Some(StringLength(3)))
                ps("aea", PalindromeKey) should be(Some(Palindrome))

                // test that the other computations are not immediately executed/were executed
                ps.entities(p ⇒ true).toSet should be(Set("a", "aea"))
            }

            it("block other computations that request the property until the computation is complete") {
                import scala.collection.mutable

                val ps = psStrings
                val stringLengthPC = (e: Entity) ⇒ {
                    Thread.sleep(250) // to make it "take long"
                    StringLength(e.toString.size)
                }
                ps <<! (StringLengthKey, stringLengthPC)

                @volatile var executed = false
                val t = new Thread(new Runnable {
                    def run: Unit = {
                        Thread.sleep(75)
                        ps("a", StringLengthKey) should be(Some(StringLength(1)))
                        executed = true
                    }
                })
                t.start()
                // calling ".get" is safe because the property is computed using a direct
                // property computation
                ps("a", StringLengthKey).get should be(StringLength(1))
                t.join()
                executed should be(true)
            }
        }
    }
}

