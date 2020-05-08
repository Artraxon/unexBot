package de.rtrx.a.flow

import de.rtrx.a.ApplicationStoppedException
import de.rtrx.a.flow.events.EventMultiplexer
import de.rtrx.a.flow.events.EventMultiplexerBuilder
import de.rtrx.a.flow.events.EventType
import de.rtrx.a.flow.events.EventTypeFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Named
import kotlin.reflect.KClass

typealias EventFactories = Map<KClass<*>, Pair<EventTypeFactory<*, *, *>, KClass<*>>>

/**
 * @param S The type of the result that a flow returns
 * @param T The type of the flow created
 */

interface FlowDispatcherInterface<T: Flow> {
    suspend fun <R: Any> subscribe(flow: T, callback: suspend (R) -> Unit, type: EventType<R>)
    suspend fun unsubscribe(flow: T, type: EventType<*>)

    fun getCreatedFlows(): ReceiveChannel<T>
    suspend fun stop()
    suspend fun join()
    suspend fun <E : EventType<R>, R : Any, I : Any> createNewEvent(
            clazz: KClass<E>,
            id: I,
            multiplexerBuilder: EventMultiplexerBuilder<R, *, ReceiveChannel<R>>
    ): E

    suspend fun <E: EventType<R>, R: Any, I: Any> unregisterEvent(
            clazz: KClass<E>,
            id: I
    )
}

interface IFlowDispatcherStub<T: Flow, F: FlowFactory<T, *>>: FlowDispatcherInterface<T>{
    suspend fun <E: EventType<R>, R: Any> registerMultiplexer(type: E, multiplexer: EventMultiplexer<R>)
    fun start()
    val flowFactory: F
}

/**
 * @param eventFactories Key is the Klass of the type, Value Consists of the corresponding Factory and the Type of the Key
 */
class FlowDispatcherStub<T: Flow, M: Any, F: FlowFactory<T, M>> @Inject constructor (
        private val starterEventChannel: ReceiveChannel<M>,
        override val flowFactory: F,
        @param:Named("launcherScope") private val flowLauncherScope: CoroutineScope,
        private val eventFactories: EventFactories
): IFlowDispatcherStub<T, F> {
    private val flows: BroadcastChannel<T>
    private val finishedFlows: BroadcastChannel<FlowResult<T>>
    private val multiplexers = mutableMapOf<EventType<*>, EventMultiplexer<*>>()
    private val job: Job
    private val logger = KotlinLogging.logger {  }

    private val runningEvents = ConcurrentHashMap<KClass<*>, ConcurrentHashMap<Any, Pair<EventType<*>, ReceiveChannel<*>>>>()
    private val eventCreationContext = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    init {
        eventFactories.forEach {(clazz, _) ->
            runningEvents.put(clazz, ConcurrentHashMap())
        }

        flows = BroadcastChannel(Channel.CONFLATED)
        finishedFlows = BroadcastChannel(Channel.CONFLATED)
        job = flowLauncherScope.launch(start = CoroutineStart.LAZY) {
            while(isActive) {
                try {
                    for (event in starterEventChannel) {
                        flows.send(flowFactory.create(
                                this@FlowDispatcherStub,
                                event,
                                Callback { flowResult -> flowLauncherScope.launch { finishedFlows.send(flowResult) } })
                                .apply { start() })
                    }
                } catch (e: Throwable) {
                    logger.warn { e.message }
                }
            }
        }
    }

    override suspend fun <R : Any> subscribe(flow: T, callback: suspend (R) -> Unit, type: EventType<R>) {
        CoroutineScope(eventCreationContext).launch { multiplexers[type]?.addListener(flow) { callback(it as R)} }.join()
    }

    override suspend fun unsubscribe(flow: T, type: EventType<*>) {
        CoroutineScope(eventCreationContext).launch { multiplexers[type]?.removeListeners(flow) }.join()
    }

    override fun getCreatedFlows(): ReceiveChannel<T> {
        return flows.openSubscription()
    }

    override suspend fun <E: EventType<R>, R : Any> registerMultiplexer(type: E, multiplexer: EventMultiplexer<R>) {
        CoroutineScope(eventCreationContext).launch { multiplexers.put(type, multiplexer) }.join()
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

    override suspend fun <E : EventType<R>, R : Any, I : Any> createNewEvent(
            clazz: KClass<E>,
            id: I,
            multiplexerBuilder: EventMultiplexerBuilder<R, *, ReceiveChannel<R>>
    ): E {
        val event = CoroutineScope(eventCreationContext).async {

            var (factory, idType) = eventFactories.get(clazz)
                    ?: throw IllegalArgumentException("No Factory Present for given Type")
            if(idType.isInstance(id).not()) throw IllegalArgumentException("Given Key does not match required Key for Factory")
            factory = factory as EventTypeFactory<E, R, in I>

            val (event, out) = runningEvents.get(clazz)!!.getOrPut(
                    id,
                    {
                        val (event, out) = factory.create(id)
                        val multiplexer = multiplexerBuilder.setOrigin(out).build()
                        registerMultiplexer(event, multiplexer as EventMultiplexer<R>)
                        event to out
                    }
            )

            event
        }
        return event.await() as E
    }

    override suspend fun <E : EventType<R>, R : Any, I : Any> unregisterEvent(clazz: KClass<E>, id: I) {
        CoroutineScope(eventCreationContext).launch {
            runningEvents.get(clazz)?.get(id)?.run {
                multiplexers.remove(first)
            }
        }
    }
}


