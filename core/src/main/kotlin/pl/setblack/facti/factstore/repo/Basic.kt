package pl.setblack.facti.factstore.repo

import pl.setblack.facti.factstore.Fact
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Definition for every factstore.
 */
interface FactStore<ID, FACT: Fact <*>, IDFACT> {
    fun persist(id: ID, ev: FACT): Mono<SavedFact<IDFACT>>

    fun loadFacts(id: ID, offset: Long): Flux<FACT>

    fun roll(id: ID): Mono<Long>

    fun loadAll( lastFact : IDFACT) : Flux<LoadedFact<ID, FACT>>
}

/**
 * Definition for every snapshotstore.
 */
interface SnapshotStore<ID, STATE> {

    fun restore(id: ID, supplier: (ID) -> Mono<STATE>): Mono<SnapshotData<STATE>>

    fun snapshot(id: ID, state: SnapshotData<STATE>): Mono<SavedState<STATE>>
}

data class SavedFact<IDFACT>(val thisFactIndex: Long, val idFact :IDFACT)

data class SavedState<STATE>(val snapshotIndex: Long, val state: STATE)

data class SnapshotData<STATE>(val state: STATE, val nextFactSeq: Long = 0)

data class LoadedFact<ID,  FACT : Fact<*>> (val id: ID,  val fact : FACT)