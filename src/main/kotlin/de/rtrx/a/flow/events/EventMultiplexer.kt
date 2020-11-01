package de.rtrx.a.flow.events

import de.rtrx.a.flow.Flow
import de.rtrx.a.flow.IsolationStrategy
import de.rtrx.a.flow.SingleFlowIsolation
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {  }
interface EventMultiplexer<R: Any> {
    fun addListener(flow: Flow, fn: suspend (R) -> Unit)

    fun removeListeners(flow: Flow)
}

/**
 * @param R The Type that's going to be passed to the listeners
 * @param X The Produced Multiplexer
 * @param O The Origin of the data
 */
@JvmSuppressWildcards
interface EventMultiplexerBuilder<R: Any, out X: EventMultiplexer<R>, in O> {
    fun build() : X
    fun setOrigin(origin: O): EventMultiplexerBuilder<R, X, O>
    fun setIsolationStrategy(strategy: IsolationStrategy): EventMultiplexerBuilder<R, X, O>
    operator fun invoke(dsl: EventMultiplexerBuilder<R, X, O>.() -> Unit): EventMultiplexerBuilder<R, X, O> {
        this.dsl()
        return this
    }
}


@JvmSuppressWildcards
class SimpleMultiplexer<R: Any> @Inject constructor(private val origin: ReceiveChannel<@JvmSuppressWildcards R>, private val isolationStrategy: IsolationStrategy): EventMultiplexer<R> {
    private val listeners: MutableMap<Flow, ConcurrentLinkedQueue<suspend (R) -> Unit>> = ConcurrentHashMap()
    private val accessScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
    private val mutex: Mutex = Mutex()

    init {
        CoroutineScope(Dispatchers.Default).launch {
            for (element in origin) {
                mutex.withLock {
                    listeners.forEach { flow, list ->
                        try {
                            isolationStrategy.executeEach(element, flow, list)
                        } catch (e: Throwable){
                            logger.error { "flow threw exception: " + e.message }
                        }
                    }
                }
            }
        }
    }

    override fun addListener(flow: Flow, fn: suspend (R) -> Unit) {
        //It would probably necessary to synchronize with a mutex since the Reddit API is very slow
        accessScope.launch {
            mutex.withLock {
                listeners.getOrPut(flow, { ConcurrentLinkedQueue() }).add(fn)
            }
        }
    }

    override fun removeListeners(flow: Flow) {
        accessScope.launch {
            mutex.withLock {
                listeners.remove(flow)
                isolationStrategy.removeFlow(flow)
            }
        }
    }

    class SimpleMultiplexerBuilder<R: Any> : @kotlin.jvm.JvmSuppressWildcards EventMultiplexerBuilder<R, @kotlin.jvm.JvmSuppressWildcards SimpleMultiplexer<R>, @kotlin.jvm.JvmSuppressWildcards ReceiveChannel<R>>{
        @JvmSuppressWildcards
        private lateinit var _origin: ReceiveChannel<R>
        @JvmSuppressWildcards
        private var _isolationStrategy: IsolationStrategy = SingleFlowIsolation()

        @JvmSuppressWildcards
        override fun setOrigin(origin: ReceiveChannel<R>): SimpleMultiplexerBuilder<R>{
            this._origin = origin
            return this
        }

        override fun setIsolationStrategy(strategy: IsolationStrategy): SimpleMultiplexerBuilder<R> {
            this._isolationStrategy = strategy
            return this
        }

        override fun build() = SimpleMultiplexer(_origin, _isolationStrategy)

    }
}
