package de.rtrx.a.jrawExtension

import de.rtrx.a.RedditSpec
import de.rtrx.a.config
import de.rtrx.a.logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import net.dean.jraw.models.Created
import net.dean.jraw.models.UniquelyIdentifiable
import net.dean.jraw.pagination.BackoffStrategy
import net.dean.jraw.pagination.ConstantBackoffStrategy
import net.dean.jraw.pagination.Paginator
import net.dean.jraw.pagination.RedditIterable
import java.net.SocketTimeoutException
import java.time.Instant

class SuspendableStream<out T> @JvmOverloads constructor(
    private val dataSource: RedditIterable<T>,
    private val backoff: BackoffStrategy = ConstantBackoffStrategy(),
    historySize: Int = 200,
    val ageLimit: Long = config[RedditSpec.submissions.maxTimeDistance]
) : Iterator<T?> where T: UniquelyIdentifiable, T: Created {

    /** Keeps track of the uniqueIds we've seen recently */
    private val history = RotatingSearchList<String>(historySize)
    private var currentIterator: Iterator<T>? = null
    private var resumeTimeMillis = -1L

    override fun hasNext(): Boolean = currentIterator != null && currentIterator?.hasNext() ?: false

    override fun next(): T? {
        val it = currentIterator
        return if (it != null && it.hasNext()) {
            it.next()
        } else null
    }

    suspend fun waitForNext(): T {
        val cnt = next()
        if(cnt != null) return cnt
        val new = requestNew()
        currentIterator = new
        return new.next()
    }

    private suspend fun requestNew(): Iterator<T> {
        var newDataIterator: Iterator<T>? = null

        while (newDataIterator == null) {
            // Make sure to honor the backoff strategy
            if (resumeTimeMillis > System.currentTimeMillis()) {
                delay(resumeTimeMillis - System.currentTimeMillis())
            }

            dataSource.restart()

            val old = mutableListOf<T>()
            val new = mutableListOf<T>()
            do {
                lateinit var oldCnt: List<T>
                try {
                    oldCnt = dataSource.next().partition { history.contains(it.uniqueId) || Instant.now().toEpochMilli() - ageLimit > it.created.time }.second
                    oldCnt.forEach { history.add(it.uniqueId) }
                    old.addAll(oldCnt)
                } catch (ex: SocketTimeoutException) {
                    logger.error { ex.message }
                    break;
                }
            } while (oldCnt.isNotEmpty())

            // Calculate at which time to poll for new data
            val backoffMillis = backoff.delayRequest(old.size, new.size + old.size)
            require(backoffMillis >= 0) { "delayRequest must return a non-negative integer, was $backoffMillis" }
            resumeTimeMillis = System.currentTimeMillis() + backoff.delayRequest(old.size, new.size + old.size)

            // Yield in reverse order so that if more than one unseen item is present the older items are yielded first
            if (old.isNotEmpty())
                newDataIterator = old.asReversed().iterator()
        }

        return newDataIterator
    }
}

fun <T> Paginator<T>.subscribe(
    waitIntervall: Long,
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    ageLimit: Long = 1000 * 60 * 60 * 4
): Pair<Job, ReceiveChannel<T>> where T: UniquelyIdentifiable, T: Created  {
    val channel = Channel<T>(capacity = Channel.UNLIMITED)
    val job =  coroutineScope.launch {
        while(isActive) {
            try {
                val stream = SuspendableStream(this@subscribe, ConstantBackoffStrategy(waitIntervall), ageLimit = ageLimit)
                while (isActive) {
                    channel.send(stream.waitForNext())
                }
            } catch (e: Throwable){
                logger.error { "An exception was raised while fetching items from reddit:\n" +
                        e.message.toString()
                }
            }
        }
    }

    return job to channel
}

