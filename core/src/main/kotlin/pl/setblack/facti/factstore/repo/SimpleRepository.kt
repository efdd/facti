package pl.setblack.facti.factstore.repo


import io.vavr.control.Option
import pl.setblack.facti.factstore.*
import pl.setblack.facti.factstore.util.TasksHandler
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SimpleRepository<ID, STATE, FACT : Fact<STATE>>(
        private val creator: (ID) -> STATE,
        private val factStore: FactStore<ID, FACT>,
        private val snapshotStore: SnapshotStore<ID, STATE>,
        private val tasksHandler: TasksHandler
) : Repository<ID, STATE, FACT>, IOManager {

    private val objects = ConcurrentHashMap<ID, Aggregate<STATE>>()

    override fun <R> execute(id: ID, command: Command<STATE, FACT, R>): Flux<R> {

        val beforeState = loadAggregate(id)

        return beforeState.flatMapMany {aggregate ->
            assert (aggregate.loaded)
            val commandResult = command.apply(aggregate.state)
            val resultProcessor = commandResult._1
            val facts = commandResult._2
            val newState = updateAggregate(id, facts, aggregate)
            newState.flatMapMany { state ->
                resultProcessor.invoke(state.state)
            }
        }
    }

    override fun <R> query(id: ID, q: Query<STATE, FACT, R>): Flux<R> =
            loadAggregate(id).flatMapMany {
                q.query(it.state)
            }

    override fun snapshot(id: ID): Mono<STATE> {
        val aggregateOp = loadAggregate(id)
        //TODO this operation is sync
        return aggregateOp.flatMap { aggregate ->
            val dataToSave = SnapshotData(aggregate.state)

            tasksHandler.putIOTask<STATE> (java.lang.String.valueOf(id)){ completableFuture ->
                val locked = aggregate.rollLock.tryLock()
                if ( locked) {
                    try {
                        val nextEvent = this.factStore.roll(id)
                        nextEvent.map {
                            SnapshotData(aggregate.state, nextFactSeq = it)
                        }.flatMap {
                            this.snapshotStore.snapshot(id, it)
                        }.subscribe( {
                            completableFuture.complete(dataToSave.state)
                        }, {completableFuture.completeExceptionally(it)})
                    } finally {
                        aggregate.rollLock.unlock()
                    }
                } else {
                    snapshot(id).subscribe ({ completableFuture.complete(it)} ,{completableFuture.completeExceptionally(it)})
                }
            }

        }


    }

    private fun loadStoredFacts(id: ID, offset : Long): Flux<FACT>  {
        return Mono.defer { ->
            Mono.just(1)
        }.flatMapMany {
            if (!isLoaded(id)) {
                this.factStore.loadFacts(id, offset)
            } else {
                Flux.empty()
            }
        }
    }

    private fun isLoaded(id : ID) : Boolean{
        return this.objects[id]?.loaded ?: false
    }


    private fun loadAggregate(id: ID): Mono<Aggregate<STATE>> {
        if (this.objects.containsKey(id)) {
            return Mono.just(this.objects[id]!!)
        } else {
            val snapshot = snapshotStore.restore(id){ anId ->
                Mono.just(creator(anId))
            }
            return snapshot.flatMap {saved ->
                val state = Aggregate(saved.state)
                val before = this.objects.putIfAbsent(id, state )
                if (before == null) {
                    val facts = loadStoredFacts(id, saved.nextFactSeq)
                    val newState = updateTransientAggregate(id, facts, state)
                    newState.map {
                        markLoaded(id, it)

                    }
                } else {

                    Mono.just(before)
                }
            }
        }
    }

    private fun markLoaded(id: ID, oldState: Aggregate<STATE>) : Aggregate<STATE> {
        val loaded = oldState.copy(loaded = true)
        if (!this.objects.replace(id, oldState, loaded)) {
            TODO("handling evil changes!")
        }
        return loaded
    }


    override
    fun deleteAll() {
        if (factStore is IOManager) {
            factStore.deleteAll()
        }
        if (snapshotStore is IOManager) {
            snapshotStore.deleteAll()
        }
        shutdown()
    }

    override fun restart() {
        this.shutdown()
    }

    override
    fun shutdown() {
        if (factStore is IOManager) {
            factStore.shutdown()
        }
        if (snapshotStore is IOManager) {
            snapshotStore.shutdown()
        }
        this.objects.clear()
    }

    private fun processSingleFact(id: ID, fact: FACT, beforeState : Aggregate<STATE>): Mono<Option<FACT>> {

        return factStore.persist(id, fact)
                .map {
                    processSingleTransientFact(id, fact, beforeState)
                }
    }

    private fun updateAggregate(id: ID, facts: Flux<FACT>, beforeState : Aggregate<STATE>): Mono<Aggregate<STATE>> =
            facts.flatMap {
                processSingleFact(id, it, beforeState)
            }.last(Option.none()).flatMap {
                val newState = getAggregate(id ,beforeState)
                Mono.just(newState)
            }


    private fun getAggregate(id: ID, beforeState : Aggregate<STATE>)
            = this.objects.computeIfAbsent(id){ beforeState}


    private fun processSingleTransientFact(id: ID, fact: FACT, defaultState : Aggregate<STATE>): Option<FACT> {
        val before = getAggregate(id, defaultState)
        before.rollLock.withLock {
            val newState = fact.apply(before.state)

            val toStore = before.withState(newState)
            //assert (before.rollLock == toStore.rollLock)
            val replaced = this.objects.replace(id, before, toStore)
            return if (replaced) {
                //processSingleTransientFact(id, fact)
               Option.of(fact)
            } else {
               processSingleTransientFact(id, fact, defaultState)
            }
        }

    }

    private fun updateTransientAggregate(id: ID, facts: Flux<FACT>, defaultState : Aggregate<STATE>): Mono<Aggregate<STATE>> =
            facts.map {
                processSingleTransientFact(id, it, defaultState)
            }.last(Option.none()).flatMap {
                val before = getAggregate(id, defaultState)
                Mono.just(before)
            }


}


data class AggregateSaved<STATE>(val saved: STATE, val nextEvent: Long = 0)
//causes error
data class Aggregate<STATE>(
        val state: STATE,
        val loaded: Boolean = false,
        val rollLock: ReentrantLock = ReentrantLock()) {
  //  fun withState(newState: STATE) = this.copy( state = newState)
    fun withState(newState: STATE)  : Aggregate<STATE> {
        val res = this.copy(state = newState)
        return res
    }
}



