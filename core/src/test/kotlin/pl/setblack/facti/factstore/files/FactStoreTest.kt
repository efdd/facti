package pl.setblack.facti.factstore.files


import io.kotlintest.shouldBe
import io.kotlintest.specs.DescribeSpec
import io.vavr.collection.Array
import pl.setblack.facti.factstore.bank.simplified.MoneyTransfered
import pl.setblack.facti.factstore.bank.simplified.AccountFact
import pl.setblack.facti.factstore.file.FileFactStore
import pl.setblack.facti.factstore.util.SimpleTaskHandler
import reactor.core.publisher.Flux
import reactor.core.publisher.toFlux
import reactor.test.StepVerifier
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Clock
import java.time.LocalDateTime
import java.util.*


class FactStoreTest : DescribeSpec({

    describe("for an fact store in a temp folder ") {
        val timeZone = TimeZone.getTimeZone("GMT+0:00")
        val initialTime = LocalDateTime.parse("2018-10-01T10:00")
        val clock = Clock.fixed(initialTime.atZone(timeZone.toZoneId()).toInstant(), timeZone.toZoneId())
        val tmpDir = Files.createTempDirectory("facti-filestore-test")
        val tasksHandler = SimpleTaskHandler()
        val factStore = FileFactStore<String, AccountFact>(tmpDir, clock, tasksHandler)

        context("persist event") {
            factStore.deleteAll()
            val persisted = factStore.persist(mainAccountId, MoneyTransfered(BigDecimal.ONE, otherAccountId))

            it("should be saved") {
                StepVerifier.create(persisted).assertNext {
                    it.thisFactIndex > 0
                }.verifyComplete()
            }
        }


        context("restore") {
            factStore.deleteAll()
            val events = Array.range(0, 3)
                    .map { MoneyTransfered(BigDecimal.valueOf(it.toLong()), otherAccountId) }
                    .map { factStore.persist(mainAccountId, it) }
                    .map { it.toFlux() }
            val persisted = Flux.concat(events)
            persisted.blockLast()
            factStore.restart()
            val restoredFacts = factStore.loadFacts(mainAccountId, 0)
            it("should restore all  events") {
                StepVerifier.create(restoredFacts).expectNextCount(3).verifyComplete()
            }
            it("should have correct last  event") {
                StepVerifier.create(restoredFacts.last()).assertNext {
                    (it as MoneyTransfered).amountDiff shouldBe (BigDecimal.valueOf(2))
                }.verifyComplete()

            }
        }
       context("persist several events") {

            val events = Array.range(0, 10)
                    .map { MoneyTransfered(BigDecimal.valueOf(it.toLong()), otherAccountId) }
                    .map { factStore.persist(mainAccountId, it) }
                    .map { it.toFlux() }

            val persisted = Flux.concat(events)

            it ("should process 10 events") {
                factStore.deleteAll()
                StepVerifier.create(persisted)
                        .expectNextCount(10)
                        .verifyComplete()
            }
            it ("should  have 10 trasactions at the end ") {
                factStore.deleteAll()
                StepVerifier.create(persisted.last())
                        .assertNext{ it.thisFactIndex shouldBe(9)}
                        .verifyComplete()
            }
        }

        context ("events after roll") {
            factStore.deleteAll()
            val events1 = Array.range(0, 10)
                    .map { MoneyTransfered(BigDecimal.valueOf(it.toLong()), otherAccountId) }
                    .map { factStore.persist(mainAccountId, it) }
                    .map { it.toFlux() }
            val events2 = Array.range(0, 2)
                    .map { MoneyTransfered(BigDecimal.valueOf(it.toLong()), otherAccountId) }
                    .map { factStore.persist(mainAccountId, it) }
                    .map { it.toFlux() }

            val fullStory = Flux.concat(events1).collectList().flatMap {
                factStore.roll(mainAccountId).flatMap {
                    Flux.concat(events2).collectList()
                            .flatMap { factStore.roll(mainAccountId) }

                }
            }
            fullStory.block()

            it ("3 stored events are loaded") {
                factStore.shutdown()
                val restored = factStore.loadFacts(mainAccountId, 10)
                StepVerifier.create(restored).expectNextCount(2).verifyComplete()
            }
            it ("all events are restored") {
                factStore.shutdown()
                val restored = factStore.loadFacts(mainAccountId, 0)
                StepVerifier.create(restored).expectNextCount(12).verifyComplete()
            }
        }

    }
})

