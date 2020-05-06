package de.rtrx.a.flow

import de.rtrx.a.ApplicationStoppedException
import de.rtrx.a.flow.events.EventMultiplexer
import de.rtrx.a.flow.events.EventType
import de.rtrx.a.unex.UnexFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.broadcast
import mu.KotlinLogging
import javax.inject.Inject
import javax.inject.Named


/**
 * @param S The type of the result that a flow returns
 * @param T The type of the flow created
 */

interface FlowDispatcherInterface<T: Flow> {
    fun <R: Any> subscribe(flow: T, callback: suspend (R) -> Unit, type: EventType<R>)
    fun unsubscribe(flow: T, type: EventType<*>)
    fun getCreatedFlows(): ReceiveChannel<T>
    suspend fun stop()
    suspend fun join()
}

interface IFlowDispatcherStub<T: Flow, F: FlowFactory<T, *>>: FlowDispatcherInterface<T>{
    fun <E: EventType<R>, R: Any> registerMultiplexer(type: E, multiplexer: EventMultiplexer<R>)
    fun start()
    val flowFactory: F
}

class FlowDispatcherStub<T: Flow, M: Any, F: FlowFactory<T, M>> @Inject constructor (
        private val starterEventChannel: ReceiveChannel<M>,
        override val flowFactory: F,
        @param:Named("launcherScope") private val flowLauncherScope: CoroutineScope
): IFlowDispatcherStub<T, F> {
    private val flows: BroadcastChannel<T>
    private val finishedFlows: BroadcastChannel<FlowResult<T>>
    private val multiplexers = mutableMapOf<EventType<*>, EventMultiplexer<*>>()
    private val job: Job
    private val logger = KotlinLogging.logger {  }

    init {
        flows = BroadcastChannel(Channel.CONFLATED)
        finishedFlows = BroadcastChannel(Channel.CONFLATED)
        job = flowLauncherScope.launch(start = CoroutineStart.LAZY) {
            try {
                for (event in starterEventChannel) {
                    flows.send(flowFactory.create(
                            this@FlowDispatcherStub,
                            event,
                            Callback {flowResult -> flowLauncherScope.launch { finishedFlows.send(flowResult) }} )
                            .apply { start() })
                }
            } catch (e: Throwable){
                logger.warn { e.message }
            }
        }
    }

    override fun <R : Any> subscribe(flow: T, callback: suspend (R) -> Unit, type: EventType<R>) {
        multiplexers[type]?.addListener(flow) { callback(it as R)}
    }

    override fun unsubscribe(flow: T, type: EventType<*>) {
        multiplexers[type]?.removeListeners(flow)
    }

    override fun getCreatedFlows(): ReceiveChannel<T> {
        return flows.openSubscription()
    }

    override fun <E: EventType<R>, R : Any> registerMultiplexer(type: E, multiplexer: EventMultiplexer<R>) {
        multiplexers.put(type, multiplexer)
    }

    override fun start() {
        job.start()
    }

    override suspend fun stop() {
        job.cancel(ApplicationStoppedException())
        job.join()
    }

    override suspend fun join() {
        job.join()
    }

}

