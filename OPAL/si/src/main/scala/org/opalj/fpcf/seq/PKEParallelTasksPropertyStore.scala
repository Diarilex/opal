/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2017
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
package seq

import scala.reflect.runtime.universe.Type
import java.lang.System.identityHashCode
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConverters._
import org.opalj.graphs
import org.opalj.log.LogContext
import org.opalj.log.OPALLogger.info
import org.opalj.log.OPALLogger.{debug ⇒ trace}
import org.opalj.log.OPALLogger.error
import org.opalj.log.OPALLogger.warn
import org.opalj.fpcf.PropertyKey.fallbackPropertyBasedOnPkId
import org.opalj.concurrent.Tasks
import org.opalj.concurrent.OPALExecutionContext
import org.opalj.concurrent.ConcurrentExceptions

import scala.collection.mutable.ArrayBuffer

/**
 * A concurrent implementation of the property store which parallels the execution of the scheduled
 * tasks and on-update continuations.
 *
 * Entities are generally only stored on demand. I.e.,
 *  - they have a property OR
 *  - a computation is already scheduled that will compute the property OR
 *  - we have a `depender`.
 *
 * @author Michael Eichberg
 */
final class PKEParallelTasksPropertyStore private (
        val ctx: Map[Type, AnyRef]
)(
        implicit
        val logContext: LogContext
) extends PropertyStore { store ⇒

    protected[this] val scheduledTasksCounter: AtomicInteger = new AtomicInteger(0)
    final def scheduledTasks: Int = scheduledTasksCounter.get

    protected[this] val scheduledOnUpdateComputationsCounter = new AtomicInteger(0)
    final def scheduledOnUpdateComputations: Int = scheduledOnUpdateComputationsCounter.get

    protected[this] val eagerOnUpdateComputationsCounter = new AtomicInteger(0)
    final def eagerOnUpdateComputations: Int = eagerOnUpdateComputationsCounter.get

    protected[this] var fallbacksUsedForComputedPropertiesCounter = 0
    final def fallbacksUsedForComputedProperties: Int = fallbacksUsedForComputedPropertiesCounter

    protected[this] var resolvedCyclesCounter = 0
    final def resolvedCycles: Int = resolvedCyclesCounter

    private[this] var quiescenceCounter = 0

    // --------------------------------------------------------------------------------------------
    //
    // CORE DATA STRUCTURES
    //
    // --------------------------------------------------------------------------------------------

    // We have to use a concurrent hash to enable concurrent reads.
    private[this] val ps: Array[ConcurrentHashMap[Entity, PropertyValue]] = {
        Array.fill(PropertyKind.SupportedPropertyKinds) { new ConcurrentHashMap( /*1024*64)*/ ) }
    }

    // Those computations that will only be scheduled if the result is required
    private[this] var lazyComputations: Array[SomePropertyComputation] = {
        new Array(PropertyKind.SupportedPropertyKinds)
    }

    /* The number of property computations and results which have not been completely processed. */
    @volatile private[this] var exception: ConcurrentExceptions = null
    private[this] val openJobs = new AtomicInteger(0)
    private[this] def decOpenJobs(): Unit = openJobs.decrementAndGet()
    // has to be called before the job is actually processed; i.e., termination of the job
    // always has to precede termination of the job
    private[this] def incOpenJobs(): Unit = openJobs.incrementAndGet()

    /* The list of scheduled property computations  - they will be processed in parallel. */
    private[this] val tasks = {
        Tasks[QualifiedTask](
            (_, t) ⇒ {
                try {
                    // As a sideeffect, we may have (implicit) calls to schedule...
                    // and implicit calls handleResult calls
                    // both will increase openJobs.
                    t.apply()
                } catch {
                    case t: Throwable ⇒
                        error("analysis progress", "parallelized property computation failed", t)
                        interruptResultsProcessor()
                        store.synchronized {
                            if (exception == null) exception = new ConcurrentExceptions
                            exception.addSuppressed(t)
                        }
                        throw t;
                } finally {
                    decOpenJobs()
                }
            },
            abortOnExceptions = true,
            isInterrupted
        )(OPALExecutionContext)
    }

    private[this] val results = new LinkedBlockingDeque[(PropertyComputationResult, Boolean /*was lazily triggered*/ )]()
    private[this] val resultsProcessor = {
        val t = new Thread("OPAL - Property Computation Results Processor") {
            override def run(): Unit = {
                while (!isInterrupted) {
                    val nextResult = results.pollLast(1, TimeUnit.MINUTES)
                    if (nextResult != null) {
                        try {
                            val (r, wasLazilyTriggered) = nextResult
                            doHandleResult(r, wasLazilyTriggered)
                        } catch {
                            case t: Throwable ⇒
                                error("analysis progress", "concurrent processing of results failed", t)
                                tasks.abortDueToExternalException(t)
                                store.synchronized {
                                    if (exception == null) exception = new ConcurrentExceptions
                                    exception.addSuppressed(t)
                                }
                                throw t;
                        } finally {
                            decOpenJobs()
                        }
                    }
                }
                warn("analysis progress", "processing results was interrupted")
            }
        }
        t.setDaemon(true)
        t.setPriority(8)
        t.start()
        t
    }
    private def interruptResultsProcessor(): Unit = {
        resultsProcessor.interrupt()
    }

    private[this] var computedPropertyKinds: Array[Boolean] = null // has to be set before usage

    private[this] var delayedPropertyKinds: Array[Boolean] = null // has to be set before usage

    override def isKnown(e: Entity): Boolean = ps.contains(e)

    override def hasProperty(e: Entity, pk: PropertyKind): Boolean = {
        require(e ne null)
        val pValue = ps(pk.id).get(e)
        pValue != null && {
            val ub = pValue.ub
            ub != null && ub != PropertyIsLazilyComputed
        }
    }

    override def properties[E <: Entity](e: E): Iterator[EPS[E, Property]] = {
        require(e ne null)
        for {
            ePValue ← ps.iterator
            pValue = ePValue.get(e)
            if pValue != null
            eps ← pValue.toEPS(e)
        } yield {
            eps
        }
    }

    override def entities(propertyFilter: SomeEPS ⇒ Boolean): Iterator[Entity] = {
        val entities = ArrayBuffer.empty[Entity]
        val max = ps.length
        var i = 0
        while (i < max) {
            ps(i) forEach { (e, pValue) ⇒
                val eps = pValue.toEPS[Entity](e)
                if (eps.isDefined && propertyFilter(eps.get)) entities += e
            }
            i += 1
        }
        entities.toIterator
    }

    /**
     * Returns all entities which have the given property bounds based on an "==" (equals)
     * comparison.
     */
    override def entities[P <: Property](lb: P, ub: P): Iterator[Entity] = {
        require(lb ne null)
        require(ub ne null)
        entities((otherEPS: SomeEPS) ⇒ lb == otherEPS.lb && ub == otherEPS.ub)
    }

    override def entities[P <: Property](pk: PropertyKey[P]): Iterator[EPS[Entity, P]] = {
        ps(pk.id).entrySet().iterator().asScala flatMap { ePValue ⇒
            ePValue.getValue.toEPSUnsafe[Entity, P](ePValue.getKey)
        }
    }

    override def toString(printProperties: Boolean): String = {
        if (printProperties) {
            val properties = for {
                (ePValue, pkId) ← ps.iterator.zipWithIndex
                ePValue ← ePValue.entrySet().asScala.iterator
                e = ePValue.getKey
                pValue = ePValue.getValue
            } yield {
                val propertyKindName = PropertyKey.name(pkId)
                s"$e -> $propertyKindName[$pkId] = $pValue"
            }
            properties.mkString("PropertyStore(\n\t", "\n\t", "\n")
        } else {
            s"PropertyStore(properties=${ps.iterator.map(_.size).sum})"
        }
    }

    override def registerLazyPropertyComputation[E <: Entity, P <: Property](
        pk: PropertyKey[P],
        pc: PropertyComputation[E]
    ): Unit = {
        if (debug && openJobs.get() > 0) {
            throw new IllegalStateException(
                "lazy computations should only be registered while no analysis are scheduled"
            )
        }
        lazyComputations(pk.id) = pc.asInstanceOf[SomePropertyComputation]
    }

    override def scheduleEagerComputationForEntity[E <: Entity](
        e: E
    )(
        pc: PropertyComputation[E]
    ): Unit = handleExceptions {
        scheduledTasksCounter.incrementAndGet()
        incOpenJobs()
        tasks.submit(new EagerPropertyComputationTask(this, e, pc))
    }

    private[this] def scheduleLazyComputationForEntity[E <: Entity](
        e: E
    )(
        pc: PropertyComputation[E]
    ): Unit = handleExceptions {
        scheduledTasksCounter.incrementAndGet()
        incOpenJobs()
        tasks.submit(new LazyPropertyComputationTask(this, e, pc))
    }

    // triggers lazy property computations!
    override def apply[E <: Entity, P <: Property](e: E, pk: PropertyKey[P]): EOptionP[E, P] = {
        apply(EPK(e, pk), false)
    }

    // triggers lazy property computations!
    override def apply[E <: Entity, P <: Property](epk: EPK[E, P]): EOptionP[E, P] = {
        apply(epk, false)
    }

    private[this] def apply[E <: Entity, P <: Property](
        epk:   EPK[E, P],
        force: Boolean
    ): EOptionP[E, P] = {
        val e = epk.e
        val pk = epk.pk
        val pkId = pk.id

        ps(pkId).get(e) match {
            case null ⇒
                // the entity is unknown ...
                lazyComputations(pkId) match {
                    case null ⇒
                        if (debug && computedPropertyKinds == null) {
                            /*&& delayedPropertyKinds ne null (not necessary)*/
                            throw new IllegalStateException("setup phase was not called")
                        }
                        if (computedPropertyKinds(pkId) || delayedPropertyKinds(pkId)) {
                            epk
                        } else {
                            val p = PropertyKey.fallbackProperty(this, e, pk)
                            if (force) { set(e, p) }
                            FinalEP(e, p)
                        }

                    case lc: PropertyComputation[E] @unchecked ⇒
                        // create PropertyValue to ensure that we do not schedule
                        // multiple (lazy) computations => the entity is now known
                        ps(pkId).put(e, PropertyValue.lazilyComputed)
                        scheduleLazyComputationForEntity(e)(lc)
                        // return the "current" result
                        epk

                }

            case pValue ⇒
                val ub = pValue.ub // or lb... doesn't matter
                if (ub != null && ub != PropertyIsLazilyComputed)
                    // we have a property
                    EPS(e, pValue.lb.asInstanceOf[P], pValue.ub.asInstanceOf[P])
                else {
                    //... ub is null or is PropertyIsLazilyComputed...
                    // We do not (yet) have a value, but a lazy property
                    // computation is already scheduled (if available).
                    // Recall that it is a strict requirement that a
                    // dependee which is listed in the set of dependees
                    // of an IntermediateResult must have been queried;
                    // however the sequential store does not create the
                    // data-structure eagerly!
                    if (debug && ub == null && lazyComputations(pkId) != null) {
                        throw new IllegalStateException(
                            "registered lazy computation was not triggered; "+
                                "this happens, e.g., if the list of dependees contains EPKs "+
                                "that are instantiated by the client but never queried"
                        )
                    }
                    epk
                }
        }
    }

    override def force[E <: Entity, P <: Property](e: E, pk: PropertyKey[P]): EOptionP[E, P] = {
        apply[E, P](EPK(e, pk), force = true)
    }

    /**
     * Returns the `PropertyValue` associated with the given Entity / PropertyKey or `null`.
     */
    private[seq] final def getPropertyValue(e: Entity, pkId: Int): PropertyValue = ps(pkId).get(e)

    /**
     * Updates the entity; returns true if no property already existed and is also not computed;
     * i.e., setting the value was w.r.t. the current state of the property state OK.
     */
    // CONCURRENCY NOTE:
    // ONLY TO BE CALLED BY "doHandleResult"; I.E., FROM THE RESULTS PROCESSING THREAD
    private[this] def update(
        e: Entity,
        // Recall that ub != lb even though we have no new dependees ;
        // This is generally the case for collaboratively computed properties or
        // properties for which a computation was eagerly scheduled due to an
        // updated dependee.
        lb:           Property,
        ub:           Property,
        newDependees: Traversable[SomeEOptionP]
    ): Boolean = {
        if (debug && e == null) {
            throw new IllegalArgumentException("the entity must not be null")
        }
        val pkId = ub.key.id
        if (debug && ub.key != lb.key) {
            throw new IllegalArgumentException("property keys for lower and upper bound don't match")
        }
        /*user level*/ assert(
            !lb.isOrderedProperty || {
                val ubAsOP = ub.asOrderedProperty
                ubAsOP.checkIsEqualOrBetterThan(e, lb.asInstanceOf[ubAsOP.Self]); true
            }
        )
        ps(pkId).get(e) match {
            case null ⇒
                // The entity is unknown (=> there are no dependers/dependees):
                ps(pkId).put(e, PropertyValue(lb, ub, newDependees))
                // registration with the new dependees is done when processing IntermediateResult
                true

            case pValue: IntermediatePropertyValue ⇒
                // The entity is known and we have a property value for the respective
                // kind; i.e., we may have (old) dependees and/or also dependers.
                val oldIsFinal = pValue.isFinal
                val oldLB = pValue.lb
                val oldUB = pValue.ub

                // 1. Check and update property:
                if (debug) {
                    if (oldIsFinal) {
                        throw new IllegalStateException(
                            s"already final: $e@${identityHashCode(e).toHexString}/$ub"
                        )
                    }
                    if (lb.isOrderedProperty) {
                        try {
                            val lbAsOP = lb.asOrderedProperty
                            if (oldLB != null && oldLB != PropertyIsLazilyComputed) {
                                val oldLBWithUBType = oldLB.asInstanceOf[lbAsOP.Self]
                                lbAsOP.checkIsEqualOrBetterThan(e, oldLBWithUBType)
                                val pValueUBAsOP = oldUB.asOrderedProperty
                                val ubWithOldUBType = ub.asInstanceOf[pValueUBAsOP.Self]
                                pValueUBAsOP.checkIsEqualOrBetterThan(e, ubWithOldUBType)
                            }
                        } catch {
                            case t: Throwable ⇒
                                throw new IllegalArgumentException(
                                    s"entity=$e illegal update to: lb=$lb; ub=$ub; "+
                                        newDependees.mkString("newDependees={", ", ", "}")+
                                        "; cause="+t.getMessage,
                                    t
                                )
                        }
                    }
                }
                pValue.lb = lb
                pValue.ub = ub
                // Updating lb and/or ub MAY CHANGE the PropertyValue's isFinal property!
                val newPValueIsFinal = pValue.isFinal

                // 2. Clear old dependees (remove onUpdateContinuation from dependees)
                //    and then update dependees.
                val epk = EPK(e, ub /*or lb*/ )
                for {
                    eOptP @ EOptionP(oldDependeeE, oldDependeePK) ← pValue.dependees // <= the old ones
                } {
                    val oldDependeePKId = oldDependeePK.id
                    // Please recall, that we don't create support data-structures
                    // (i.e., PropertyValue) eagerly... but they should have been
                    // created by now or the dependees should be empty!

                    val dependeePValue = ps(oldDependeePKId).get(oldDependeeE)
                    val dependeeIntermediatePValue = dependeePValue.asIntermediate
                    val dependersOfDependee = dependeeIntermediatePValue.dependers
                    dependeeIntermediatePValue.dependers = dependersOfDependee - epk
                }
                if (newPValueIsFinal)
                    ps(pkId).put(e, new FinalPropertyValue(ub))
                else
                    pValue.dependees = newDependees

                // 3. Notify dependers if necessary
                if (lb != oldLB || ub != oldUB || newPValueIsFinal) {
                    pValue.dependers foreach { depender ⇒
                        val (dependerEPK, onUpdateContinuation) = depender
                        val t: QualifiedTask =
                            if (newPValueIsFinal) {
                                // TODO....
                                new EagerOnFinalUpdateComputationTask(
                                    this,
                                    FinalEP(e, ub),
                                    onUpdateContinuation
                                )
                            } else {
                                new EagerOnUpdateComputationTask(
                                    this,
                                    epk,
                                    onUpdateContinuation
                                )
                            }
                        scheduledOnUpdateComputationsCounter.incrementAndGet()
                        incOpenJobs(); tasks.submit(t)
                        // Clear depender => dependee lists.
                        // Given that we have triggered the depender, we now have
                        // to remove the respective onUpdateContinuation from all
                        // dependees of the respective depender to avoid that the
                        // onUpdateContinuation is triggered multiple times!
                        val dependerPKId = dependerEPK.pk.id
                        val dependerPValue = ps(dependerPKId).get(dependerEPK.e).asIntermediate
                        dependerPValue.dependees foreach { epkOfDependeeOfDepender ⇒
                            if (epkOfDependeeOfDepender.toEPK != epk) {
                                // We have to avoid checking against the "current" dependee
                                // because it is already final!
                                val dependeePKIdOfDepender = epkOfDependeeOfDepender.pk.id
                                val pValueOfDependeeOfDepender =
                                    ps(dependeePKIdOfDepender).get(epkOfDependeeOfDepender.e)
                                pValueOfDependeeOfDepender.asIntermediate.dependers -= dependerEPK
                            }
                        }
                        dependerPValue.dependees = Nil
                    }
                    pValue.dependers = Map.empty
                }

                oldLB == null /*AND/OR oldUB == null*/

            case finalPValue ⇒
                throw new IllegalStateException(s"$e: update of $finalPValue")
        }
    }

    override def set(e: Entity, p: Property): Unit = handleExceptions {
        val key = p.key
        val pkId = key.id

        if (debug && lazyComputations(pkId) != null) {
            throw new IllegalArgumentException(
                s"$e: setting $p is not supported; lazy computation is scheduled for $key"
            )
        }

        handleResult(ExternalResult(e, p))
    }

    override def handleResult(
        r:                  PropertyComputationResult,
        wasLazilyTriggered: Boolean
    ): Unit = handleExceptions {
        incOpenJobs()
        results.addFirst((r, wasLazilyTriggered))
    }

    // CONCURRENCY NOTE:
    // ONLY TO BE CALLED BY THE RESULTS PROCESSING THREAD!
    private[this] def doHandleResult(
        r:                  PropertyComputationResult,
        wasLazilyTriggered: Boolean
    ): Unit = handleExceptions {

        r.id match {

            case NoResult.id ⇒ {
                // A computation reported no result; i.e., it is not possible to
                // compute a/some property/properties for a given entity.
            }

            case Results.id ⇒
                val Results(results) = r
                results.foreach(handleResult)

            case MultiResult.id ⇒
                val MultiResult(results) = r
                results foreach { ep ⇒ update(ep.e, ep.p, ep.p, newDependees = Nil) }

            case IncrementalResult.id ⇒
                val IncrementalResult(ir, npcs /*: Traversable[(PropertyComputation[e],e)]*/ ) = r
                handleResult(ir)
                npcs foreach { npc ⇒ val (pc, e) = npc; scheduleEagerComputationForEntity(e)(pc) }

            //
            // Methods which actually store results...
            //

            case ExternalResult.id ⇒
                val ExternalResult(e, p) = r
                if (!update(e, p, p, Nil)) {
                    throw new IllegalStateException(s"$e: setting $p failed due to existing property")
                }

            case Result.id ⇒
                val Result(e, p) = r
                update(e, p, p, Nil)

            case PartialResult.id ⇒
                val PartialResult(e, pk, u) = r
                type E = e.type
                type P = Property
                val eOptionP = apply[E, P](e: E, pk: PropertyKey[P])
                val newEPSOption = u.asInstanceOf[EOptionP[E, P] ⇒ Option[EPS[E, P]]](eOptionP)
                newEPSOption foreach { newEPS ⇒ update(e, newEPS.lb, newEPS.ub, Nil) }

            case IntermediateResult.id ⇒
                val IntermediateResult(e, lb, ub, newDependees, c) = r

                def checkNonFinal(dependee: SomeEOptionP): Unit = {
                    if (dependee.isFinal) {
                        throw new IllegalStateException(
                            s"$e (lb=$lb, ub=$ub): dependency to final property: $dependee"
                        )
                    }
                }

                def isDependeeUpdated(
                    dependeePValue: PropertyValue, // may contains newer info than "newDependee"
                    newDependee:    SomeEOptionP
                ): Boolean = {
                    dependeePValue != null && dependeePValue.ub != null &&
                        dependeePValue.ub != PropertyIsLazilyComputed && (
                            // ... we have some property
                            // 1) check that (implicitly)  the state of
                            // the current value must have been changed
                            dependeePValue.isFinal ||
                            // 2) check if the given dependee did not yet have a property
                            newDependee.hasNoProperty ||
                            // 3) the properties are different
                            newDependee.ub != dependeePValue.ub ||
                            newDependee.lb != dependeePValue.lb
                        )
                }

                // 1. let's check if a new dependee is already updated...
                //    If so, we directly schedule a task again to compute the property.
                val theDependeesIterator = newDependees.toIterator
                while (theDependeesIterator.hasNext) {
                    val newDependee = theDependeesIterator.next()
                    if (debug) checkNonFinal(newDependee)

                    val dependeeE = newDependee.e
                    val dependeePKId = newDependee.pk.id
                    val dependeePValue = getPropertyValue(dependeeE, dependeePKId)
                    if (isDependeeUpdated(dependeePValue, newDependee)) {
                        eagerOnUpdateComputationsCounter.incrementAndGet()
                        val newEP =
                            if (dependeePValue.isFinal) {
                                FinalEP(dependeeE, dependeePValue.ub)
                            } else {
                                EPS(dependeeE, dependeePValue.lb, dependeePValue.ub)
                            }
                        doHandleResult(c(newEP), wasLazilyTriggered)
                        return ;
                    }
                }
                // all updates are handled; otherwise we have an early return

                // 2.1. update the value (trigger dependers/clear old dependees)
                update(e, lb, ub, newDependees)

                // 2.2 The most current value of every dependee was taken into account
                //     register with new (!) dependees.
                val dependerEPK = EPK(e, ub)
                val dependency = (dependerEPK, c)

                newDependees foreach { dependee ⇒
                    val dependeeE = dependee.e
                    val dependeePKId = dependee.pk.id

                    ps(dependeePKId).get(dependeeE) match {
                        case null ⇒
                            // the dependee is not known
                            ps(dependeePKId).put(
                                dependeeE,
                                new IntermediatePropertyValue(dependerEPK, c)
                            )

                        case dependeePValue: IntermediatePropertyValue ⇒
                            val dependeeDependers = dependeePValue.dependers
                            dependeePValue.dependers = dependeeDependers + dependency

                        case dependeePValue ⇒
                            throw new UnknownError(
                                "fatal internal error; "+
                                    "can't update dependees of final property"
                            )
                    }
                }

        }
    }

    override def setupPhase(
        computedPropertyKinds: Set[PropertyKind],
        delayedPropertyKinds:  Set[PropertyKind]
    ): Unit = {
        assert(openJobs.get == 0, "setup phase can only be called before tasks are scheduled")

        // this.computedPropertyKinds = IntTrieSet.empty ++ computedPropertyKinds.iterator.map(_.id)
        this.computedPropertyKinds = new Array[Boolean](PropertyKind.SupportedPropertyKinds)
        computedPropertyKinds foreach { pk ⇒ this.computedPropertyKinds(pk.id) = true }

        // this.delayedPropertyKinds = IntTrieSet.empty ++ delayedPropertyKinds.iterator.map(_.id)
        this.delayedPropertyKinds = new Array[Boolean](PropertyKind.SupportedPropertyKinds)
        delayedPropertyKinds foreach { pk ⇒ this.delayedPropertyKinds(pk.id) = true }
    }

    override def waitOnPhaseCompletion(): Unit = handleExceptions {
        var continueComputation: Boolean = false
        // We need a consistent interrupt state for fallback and cycle resolution:
        var isInterrupted: Boolean = this.isInterrupted()
        do {
            continueComputation = false
            while (!isInterrupted && openJobs.get != 0 && exception != null) {
                Thread.sleep(250) // we simply check every 250 milliseconds if we are done..

                if (tasks.hasExceptions) {
                    throw tasks.currentExceptions;
                }

                isInterrupted = this.isInterrupted()
            }
            if (exception != null) throw exception;
            if (openJobs.get == 0) quiescenceCounter += 1

            if (!isInterrupted) {
                assert(openJobs.get == 0)
                // We have reached quiescence. let's check if we have to
                // fill in fallbacks or if we have to resolve cyclic computations.

                // 1. Let's search all EPKs for which we have no analyses scheduled in the
                //    future and use the fall back for them.
                //    (Recall that we return fallback properties eagerly if no analysis is
                //     scheduled or will be scheduled; but it is still possible that we will
                //     not have a property for a specific entity, if the underlying analysis
                //     doesn't compute one; in that case we need to put in fallback values.)
                val maxPKIndex = ps.length
                var pkId = 0
                while (pkId < maxPKIndex) {
                    ps(pkId).forEach { (e, pValue) ⇒
                        // Check that we have no running computations and that the
                        // property will not be computed later on.
                        if (pValue.ub == null && !delayedPropertyKinds(pkId)) {
                            // assert(pv.dependers.isEmpty)

                            val fallbackProperty = fallbackPropertyBasedOnPkId(this, e, pkId)
                            if (traceFallbacks) {
                                trace(
                                    "analysis progress",
                                    s"used fallback $fallbackProperty for $e "+
                                        "(though an analysis was supposedly scheduled)"
                                )
                            }
                            fallbacksUsedForComputedPropertiesCounter += 1
                            handleResult(Result(e, fallbackProperty))

                            continueComputation = true
                        }
                    }
                    pkId += 1
                }

                // 2. let's search for cSCCs that only consist of properties which will not be
                //    updated later on
                if (!continueComputation) {
                    val epks = ArrayBuffer.empty[SomeEOptionP]
                    val maxPKIndex = ps.length
                    var pkId = 0
                    while (pkId < maxPKIndex) {
                        ps(pkId).forEach { (e, pValue) ⇒
                            val ub = pValue.ub
                            if (ub != null // analyses must always commit some value; hence,
                                // Both of the following tests are necessary because we may have
                                // properties which are only computed in a later phase; in that
                                // case we may have EPKs related to entities which are not used.
                                && pValue.dependees.nonEmpty // Can this node can be part of a cycle?
                                && pValue.dependers.nonEmpty // Can this node can be part of a cycle?
                                ) {
                                assert(ub != PropertyIsLazilyComputed)
                                epks += (EPK(e, ub.key): SomeEOptionP)
                            }
                        }
                        pkId += 1
                    }

                    val cSCCs = graphs.closedSCCs(
                        epks,
                        (epk: SomeEOptionP) ⇒ ps(epk.pk.id).get(epk.e).dependees
                    )
                    for { cSCC ← cSCCs } {
                        val headEPK = cSCC.head
                        val e = headEPK.e
                        val pkId = headEPK.pk.id
                        val pValue = ps(pkId).get(e)
                        val lb = pValue.lb
                        val ub = pValue.ub
                        val headEPS = IntermediateEP(e, lb, ub)
                        val newEP = PropertyKey.resolveCycle(this, headEPS)
                        val cycleAsText =
                            if (cSCC.size > 10)
                                cSCC.take(10).mkString("", ",", "...")
                            else
                                cSCC.mkString(",")
                        if (traceCycleResolutions) {
                            info(
                                "analysis progress",
                                s"resolving cycle(iteration:$quiescenceCounter): $cycleAsText ⇒ $newEP"
                            )
                        }
                        resolvedCyclesCounter += 1
                        handleResult(Result(newEP.e, newEP.p))
                        continueComputation = true
                    }
                }

                if (!continueComputation) {
                    // We used no fallbacks and found no cycles, but we may still have
                    // (collaboratively computed) properties (e.g. CallGraph) which are
                    // not yet final; let's finalize them!
                    val maxPKIndex = ps.length
                    var pkId = 0
                    while (pkId < maxPKIndex) {
                        ps(pkId).forEach { (e, pValue) ⇒
                            val lb = pValue.lb
                            val ub = pValue.ub
                            val isFinal = pValue.isFinal
                            // Check that we have no running computations and that the
                            // property will not be computed later on.
                            if (!isFinal && lb != ub && !delayedPropertyKinds.contains(pkId)) {
                                handleResult(Result(e, ub)) // commit as Final value
                                continueComputation = true
                            }
                        }
                        pkId += 1
                    }
                }
            }
        } while (continueComputation)

        if (debug && !isInterrupted) {
            // let's search for "unsatisfied computations" related to "forced properties"
            // TODO support forced properties
            val maxPKIndex = ps.length
            var pkId = 0
            while (pkId < maxPKIndex) {
                ps(pkId).forEach { (e, pValue) ⇒
                    if (!pValue.isFinal) {
                        error(
                            "analysis progress",
                            s"intermediate property state: $e ⇒ $pValue"
                        )
                    }
                }
                pkId += 1
            }
        }
    }
}

/**
 * Factory for creating `PKEParallelTasksPropertyStore`s.
 *
 * @author Michael Eichberg
 */
object PKEParallelTasksPropertyStore extends PropertyStoreFactory {

    def apply(
        context: PropertyStoreContext[_ <: AnyRef]*
    )(
        implicit
        logContext: LogContext
    ): PKEParallelTasksPropertyStore = {
        val contextMap: Map[Type, AnyRef] = context.map(_.asTuple).toMap
        new PKEParallelTasksPropertyStore(contextMap)
    }
}