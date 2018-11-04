package pl.setblack.factstore.util

import io.vavr.Tuple
import io.vavr.Tuple2
import reactor.core.publisher.Mono
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import io.vavr.collection.List
import io.vavr.kotlin.getOrNull
import java.lang.IllegalStateException

interface TasksHandler {
    fun <T> putIOTask(id : String, task : (CompletableFuture<T>)->Unit) : Mono<T>
}

class SimpleTaskHandler(private val threads : Int = 1)  : TasksHandler {
    private val ioExecutors = initExecutors()
    override
      fun <T> putIOTask(id : String, task : (CompletableFuture<T>)->Unit) : Mono<T> {
        val promise = CompletableFuture<T>()
        val executor = calcExecutor(id)
        this.ioExecutors[executor].getOrElseThrow{IllegalStateException("$executor")}.submit{
            try {
                task(promise)
            } catch (e : Exception) {
                promise.completeExceptionally(e)
            }
        }
        return Mono.fromFuture(promise)
    }

    private fun calcExecutor(id:String) : Int  {
        val rem =    id.hashCode() % ( threads)
        return if ( rem >= 0) {rem} else {rem + threads}
    }

    private fun initExecutors() = List.range(0, threads).toMap { index ->  Tuple.of(index, Executors.newSingleThreadExecutor()) }

}