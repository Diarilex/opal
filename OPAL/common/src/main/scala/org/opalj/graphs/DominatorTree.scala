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
package graphs

import scala.collection.mutable
import scala.annotation.tailrec
import org.opalj.collection.mutable.IntArrayStack

/**
 * The dominator tree of a control flow graph.
 *
 * @param idom The array contains for each node its immediate dominator.
 * 			If not all unique ids are used then the array is a sparse array and external
 * 			knowledge is necessary to determine which elements of the array contain useful
 * 			information.
 */
class DominatorTree private (idom: Array[Int], startNode: Int) {

    /**
     * Returns the immediate dominator of the node with the given id.
     *
     * @note The root node does not have an immediate dominator!
     */
    final def dom(n: Int): Int = {
        if (n == startNode) {
            val errorMessage = "the root node does not have an immediate dominator"
            throw new IllegalArgumentException(errorMessage)
        }

        idom(n)
    }

    /**
     * Iterates over all dominator nodes of the given node. Iteration starts with the immediate
     * dominator of the given node if reflexive is `false` and starts with the node itself
     * if reflexive is `true`.
     */
    final def foreachDom[U](n: Int, reflexive: Boolean = false)(f: Int ⇒ U): Unit = {
        if (n != startNode || reflexive) {
            var c = if (reflexive) n else idom(n)
            while (c != startNode) {
                f(c)
                c = idom(c)
            }
            f(startNode)
        }
    }

    def immediateDominators: IndexedSeq[Int] = idom

    def toDot: String = {
        val g = Graph.empty[Int]
        idom.zipWithIndex.foreach { e ⇒
            val (t, s) = e
            g += (s, t)
        }
        g.toDot
    }

    // THE FOLLOWING FUNCTION IS REALLY EXPENSIVE (DUE TO (UN)BOXING) 
    // AND THEREFORE NO LONGER SUPPORTED    
    // def toMap(nodes: Traversable[Int]): mutable.Map[Int, List[Int]] = {
    //     val dominators = mutable.Map(0 → List(0))
    //
    //     for (n ← nodes if !dominators.contains(n)) {
    //         // Since we traverse the dom tree no "visited" checks are necessary.
    //
    //         // The method needs to be tail recursive to be able to handle "larger graphs" 
    //         // which are,e.g., generated by large methods.
    //         @tailrec def traverseDomTree(path: List[Int]): List[Int] = {
    //             val node = path.head
    //             dominators.get(node) match {
    //                case Some(nodeDoms) ⇒
    //                     // we have found a node for which we already have the list of dominators
    //                     var accDoms = nodeDoms
    //                     path.tail foreach { n ⇒
    //                         accDoms ::= n
    //                         // let's also update the map to speed up overall processing
    //                         dominators(n) = accDoms
    //                     }
    //                     accDoms
    //
    //                 case None ⇒
    //                     traverseDomTree(dom(node) :: path)
    //             }
    //         }
    //         dominators(n) = traverseDomTree(List(n))
    //     }
    //
    //     dominators
    // }

}

/**
 * Factory to compute[[DominatorTree]]s.
 *
 * @author Stephan Neumann
 * @author Michael Eichberg
 */
object DominatorTree {

    /**
     * Computes the immediate dominators for each node of a given graph where each node
     * is identified using a unique int value (e.g. the pc of an instruction) in the range
     * [0..maxNodeId], although not all ids need to be used.
     *
     * @param foreachSuccessorOf A function that given a node executes the given function for
     * 			each direct successor.
     * @param foreachPredecessorOf A function that given a node executes the given function for
     * 			each direct predecessor. The signature of a function that can directly passed
     * 			as a parameter is:
     * 			{{{
     *  		final def foreachPredecessorOf(pc: PC)(f: PC ⇒ Unit): Unit
     *  		}}}
     * @param maxNodeId The largest unique int id that identifies a node. (E.g., in case of
     * 			the analysis of some code it is equivalent ot the length of the code.)
     *
     * @return The computed dominator tree.
     *
     * @note 	This is an implementation of the "fast dominators" algorithm
     * 			presented by T. Lengauaer and R. Tarjan in
     * 			A Fast Algorithm for Finding Dominators in a Flowgraph
     * 			ACM Transactions on Programming Languages and Systems (TOPLAS) 1.1 (1979): 121-141
     */
    def apply(
        startNode:            Int,
        foreachSuccessorOf:   Int ⇒ ((Int ⇒ Unit) ⇒ Unit),
        foreachPredecessorOf: Int ⇒ ((Int ⇒ Unit) ⇒ Unit),
        maxNodeId:            Int
    ): DominatorTree = {
        val max = maxNodeId + 1

        var n = 0;
        val parent = new Array[Int](max)
        val semi = new Array[Int](max)
        val vertex = new Array[Int](max + 1)
        //val bucket = new Array[mutable.Set[Int]](max)
        val bucket = new Array[Set[Int]](max)
        val dom = new Array[Int](max)
        val ancestor = new Array[Int](max)
        val label = new Array[Int](max)

        // Step 1 (assign dfsnum)
        val nodes = new IntArrayStack((maxNodeId + 1) / 4)
        nodes.push(startNode)
        while (nodes.nonEmpty) {
            val v = nodes.pull

            label(v) = v
            dom(v) = v

            n = n + 1
            semi(v) = n
            vertex(n) = v
            foreachSuccessorOf(v) { w ⇒
                if (semi(w) == 0) {
                    parent(w) = v
                    nodes.push(w)
                }
            }
        }

        // Steps 2 & 3
        def eval(v: Int): Int = {
            if (ancestor(v) == 0) {
                v
            } else {
                compress(v)
                label(v)
            }
        }

        def compress(v: Int): Unit = {
            val theAncestor = ancestor(v)
            if (ancestor(theAncestor) != 0) {
                compress(theAncestor)
                val ancestorLabel = label(theAncestor)
                if (semi(ancestorLabel) < semi(label(v))) {
                    label(v) = ancestorLabel
                }
                ancestor(v) = ancestor(theAncestor)
            }
        }

        var i = n
        while (i >= 2) {
            val w = vertex(i)

            // Step 2
            foreachPredecessorOf(w) { (v: Int) ⇒
                //for (v ← predecessors(w)) {
                val u = eval(v)
                val uSemi = semi(u)
                if (uSemi < semi(w)) {
                    semi(w) = uSemi
                }
            }

            val v = vertex(semi(w))
            val b = bucket(v)
            if (b ne null) {
                bucket(v) = b + w
            } else {
                //bucket(v) = mutable.Set(w)
                bucket(v) = Set(w)
            }
            // def link(v: Int, w: Int): Unit =    ancestor(w) = v
            // link(parent(w), w)
            ancestor(w) = parent(w)

            // Step 3
            val wParent = parent(w)
            val wParentBucket = bucket(wParent)
            if (wParentBucket != null) {
                for (v ← wParentBucket) {
                    val u = eval(v)
                    dom(v) = if (semi(u) < semi(v)) u else wParent;
                }
                bucket(wParent) = null
            }
            i = i - 1
        }

        // Step 4
        var j = 2;
        while (j <= n) {
            val w = vertex(j)
            if (dom(w) != vertex(semi(w))) {
                dom(w) = dom(dom(w))
            }
            j = j + 1
        }

        new DominatorTree(dom, startNode)
    }

}
