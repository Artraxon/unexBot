package de.rtrx.a.flow.events

import de.rtrx.a.flow.Flow
import de.rtrx.a.flow.IsolationStrategy
import de.rtrx.a.flow.SingleFlowIsolation
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import javax.inject.Inject

interface EventMultiplexer<R: Any> {
    fun addListener(flow: Flow, fn: suspend (R) -> Unit)

    fun removeListeners(flow: Flow)
}

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
    private val listeners: MutableMap<Flow, List<suspend (R) -> Unit>> = mutableMapOf()

    init {
        CoroutineScope(Dispatchers.Default).launch {
            for (element in origin) {
                listeners.forEach { flow, list ->
                    isolationStrategy.executeEach(element, flow, list)
                }
            }
        }
    }
    override fun addListener(flow: Flow, fn: suspend (R) -> Unit) {
        listeners[flow] = listeners.getOrDefault( flow, emptyList() ) + fn
    }

    override fun removeListeners(flow: Flow) {
        listeners.remove(flow)
        isolationStrategy.removeFlow(flow)
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
