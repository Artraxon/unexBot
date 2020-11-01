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
import javax.inject.Provider
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
    val launcherScope: CoroutineScope
    suspend fun setupAndStartFlows(startFn: suspend T.() -> Unit, flowProvider: suspend (Provider<Callback<in FlowResult<T>, Unit>>) -> Collection<T>): Collection<Job>
}

/**
 * Provides Basic Utilities for dispatching new flows, most Applications written using this library will probably
 * need this Class. Decorate it to add your own functionality.
 *
 * @param eventFactories Key is the Klass of the type, Value Consists of the corresponding Factory and the Type of the Key
 * @param starterEventChannel upon which events to create new Flows
 * @param flowFactory Factory for the Flows
 * @param flowLauncherScope the scope in which to process [starterEventChannel], create and send the flows
 * @param eventFactories Factories for all Events that should be available
 */
class FlowDispatcherStub<T: Flow, M: Any, F: FlowFactory<T, M>> @Inject constructor (
        private val starterEventChannel: ReceiveChannel<M>,
        override val flowFactory: F,
        @param:Named("launcherScope") override val launcherScope: CoroutineScope,
        private val eventFactories: EventFactories,
        internal val startAction: suspend T.() -> Unit = { start() }
): IFlowDispatcherStub<T, F> {
    private val flows: BroadcastChannel<T> = BroadcastChannel(Channel.CONFLATED)
    private val finishedFlows: BroadcastChannel<FlowResult<T>> = BroadcastChannel(Channel.CONFLATED)
    private val multiplexers = mutableMapOf<EventType<*>, EventMultiplexer<*>>()
    private val job: Job
    private val logger = KotlinLogging.logger {  }

    private val callbackProvider: Provider<Callback<in FlowResult<T>, Unit>> = Provider { Callback { flowResult -> launcherScope.launch { finishedFlows.send(flowResult) } } }

    private val runningEvents = ConcurrentHashMap<KClass<*>, ConcurrentHashMap<Any, Pair<EventType<*>, ReceiveChannel<*>>>>()
    private val eventCreationContext = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    init {
        eventFactories.forEach {(clazz, _) ->
            runningEvents.put(clazz, ConcurrentHashMap())
        }

        job = launcherScope.launch(start = CoroutineStart.LAZY) {
            while(isActive) {
                try {
                    for (event in starterEventChannel) {
                        flows.send(flowFactory.create(
                                this@FlowDispatcherStub,
                                event,
                                callbackProvider.get())
                                .apply { startInScope { startAction() } })
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

    /**
     * Allows the creation of new events during Runtime.
     * Keep in mind that if the event is not needed during the whole runtime, it should be unregistered using [unregisterEvent]
     * to avoid memory leaks.
     *
     * If an Event is created for an individual flow, an easy way to make sure that the event is unregistered is to put the
     * call in the callback (e.g. in the corresponding [FlowFactory] Implementation)
     */
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

    /**
     * Removes all references to the Event Identified by the arguments.
     * However it does not remove flows from the [EventMultiplexer]
     */
    override suspend fun <E : EventType<R>, R : Any, I : Any> unregisterEvent(clazz: KClass<E>, id: I) {
        CoroutineScope(eventCreationContext).launch {
            runningEvents.get(clazz)?.remove(id)?.run {
                multiplexers.remove(first)
            }
        }
    }

    override suspend fun setupAndStartFlows(startFn: suspend T.() -> Unit, flowProvider: suspend (Provider<Callback<in FlowResult<T>, Unit>>) -> Collection<T>): Collection<Job> {
        return launcherScope.async {
            val flows = flowProvider(callbackProvider)
            val jobs = flows.map { it.startInScope { it.startFn() } }
            flows.forEach { this@FlowDispatcherStub.flows.send(it)}
            return@async jobs
        }.await()
    }

}

class RelaunchableFlowDispatcherStub<T: RelaunchableFlow, M: Any, F: FlowFactory<T, M>> @Inject constructor (
        private val flowDispatcherStub: IFlowDispatcherStub<T, F>,
        private val flowsToLaunch: Collection<T>
): IFlowDispatcherStub<T, F> by flowDispatcherStub{
    private val relaunchedCompleted = CompletableDeferred<Unit>()
    override fun start() {
        flowDispatcherStub.launcherScope.launch {
            flowsToLaunch.map { it.startInScope(it::relaunch) }.joinAll()
            flowDispatcherStub.start()
        }
    }
}


