package de.rtrx.a.flow

import de.rtrx.a.flow.events.EventType
import kotlinx.coroutines.*
import javax.inject.Provider
import kotlin.coroutines.CoroutineContext

/**
 * @param S The type of the returned Result
 */
interface Flow : CoroutineScope{
    suspend fun start()

    fun startInScope(startFn: suspend () -> Unit): Job {
        return launch { startFn() }
    }
}

interface RelaunchableFlow : Flow {
    suspend fun relaunch()
}

/**
 * @param T The type of the flow created
 * @param M The type of the initial value
 */
interface FlowBuilder<T: Flow,M: Any> {

    /**
    Give the flow one (or multiple) values from the init trigger
     **/
    fun setInitValue(value: M?): FlowBuilder<T, M>
    /**
    Sets the callback that is called after the flow has run through
     **/
    fun setFinishCallback(callback: Callback<in FlowResult<T>, Unit>): FlowBuilder<T, M>

    /**
    * Sets a method that can be used by the flow to subscribe to event types that it is interested in
    * @param R The type of the item that should be passed later on
     * **/
    fun <R: Any> setSubscribeAccess(access: suspend (T, suspend (R) -> Unit, EventType<R>) -> Unit): FlowBuilder<T, M>

    /**
     * Sets a method that can be used by the flow to unsubscribe from event types
     */
    fun setUnsubscribeAccess(access: suspend (T, EventType<*>) -> Unit): FlowBuilder<T, M>

    /**
     * Sets the Coroutine Scope for the Flow
     */
    fun setCoroutineScope(scope: CoroutineScope): FlowBuilder<T, M>

    /**
     * Creates the Flow
     */
    fun build(): T
}

abstract class FlowBuilderDSL <T, M: Any> : FlowBuilder<T, M>
        where T: IFlowStub<M, T>,
              T : Flow{
    protected var _initValue: M? = null
    protected var _callback: Callback<in FlowResult<T>, Unit> = Callback {_ -> Unit}
    protected var _subscribeAccess: suspend (T, suspend (Any) -> Unit, EventType<*>) -> Unit = { _, _, _ -> Unit}
    protected var _unsubscribeAccess: suspend (T, EventType<*>) -> Unit = { _, _ -> Unit}
    protected var _scope: CoroutineScope = CoroutineScope(Dispatchers.Default)

    override fun <R: Any> setSubscribeAccess(access: suspend (T, suspend (R) -> Unit, EventType<R>) -> Unit): FlowBuilder<T, M> {
        _subscribeAccess = {flow, function, type: EventType<*> -> access(flow, function, type as EventType<R>)}
        return this
    }

    override fun setUnsubscribeAccess(access: suspend (T, EventType<*>) -> Unit): FlowBuilder<T, M> {
        _unsubscribeAccess = access
        return this
    }

    override fun setInitValue(value: M?): FlowBuilder<T, M> {
        _initValue = value
        return this
    }

    override fun setFinishCallback(callback: Callback<in FlowResult<T>, Unit>): FlowBuilder<T, M> {
        _callback = callback
        return this
    }

    override fun setCoroutineScope(scope: CoroutineScope): FlowBuilder<T, M> {
        this._scope = scope
        return this
    }

    open operator fun invoke(dsl: FlowBuilder<T, M>.() -> Unit): FlowBuilder<T, M>{
        (this).dsl()
        return this
    }

}

interface FlowFactory<T: Flow, M: Any>{
    suspend fun create(dispatcher: FlowDispatcherInterface<T>, initValue: M, callback: Callback<in FlowResult<T>, Unit>): T
}

interface RelaunchableFlowFactory<T: RelaunchableFlow, M: Any, D: Any?>: FlowFactory<T, M> {
    suspend fun recreateFlows(dispatcher: FlowDispatcherInterface<T>, callbackProvider: Provider<Callback<in FlowResult<T>, Unit>>, additionalData: D): Collection<T>
}


/**
 * @param M The type of the initial value
 */
interface IFlowStub<M, C: IFlowStub<M, C>> {
    val initValue: M
    val coroutineContext: CoroutineContext

    fun setOuter(outer: C)

    @Deprecated("use withSubscription")
    suspend fun <R: Any> subscribe(function: suspend (R) -> Unit, type: EventType<R>)
    @Deprecated("use withSubscription")
    suspend fun <R: Any> unsubscribe(type: EventType<R>)


    suspend fun <R : Any, T> withSubscription(subscription: Subscription<R>, block: suspend CoroutineScope.() -> T): T
    suspend fun <T> withSubscriptions(subscriptions: Collection<Subscription<*>>, block: suspend CoroutineScope.() -> T): T
}

/**
 * Typesafe Representation of a subscription
 */
interface Subscription <R: Any> {
    val hook: suspend (R) -> Unit
    val type: EventType<R>
    companion object {
        private class SubscriptionImplementation<R: Any>(
                override val hook: suspend (R) -> Unit,
                override val type: EventType<R>
        ): Subscription<R>

        fun <R: Any> create(hook: suspend (R) -> Unit, type: EventType<R>): Subscription<R> = SubscriptionImplementation(hook, type)
    }
}




/**
 * @param M the type of the initial Value being supplied
 */
class FlowStub <M, C: IFlowStub<M, C>> (
        override val initValue: M,
        private val _subscribeAccess: suspend (C, suspend (Any) -> Unit, EventType<*>) -> Unit,
        private val _unsubscribeAccess: suspend (C, EventType<*>) -> Unit,
        private val scope: CoroutineScope)
    : IFlowStub<M, C> {
    private var outer: C? = null

    override fun setOuter(outer: C) = if(this.outer == null) this.outer = outer else Unit

    override suspend fun <R: Any> subscribe(function: suspend (R) -> Unit, type: EventType<R>) {
        _subscribeAccess(outer!!, { event: Any -> function(event as R) } , type)
    }

    override suspend fun <R: Any> unsubscribe(type: EventType<R>) {
        _unsubscribeAccess(outer!!, type)
    }

    override val coroutineContext: CoroutineContext
        get() = scope.coroutineContext

    override suspend fun <R: Any, T> withSubscription(subscription: Subscription<R>, block: suspend CoroutineScope.() -> T): T{
        _subscribeAccess(outer!!, { event: Any -> subscription.hook(event as R) }, subscription.type)
        //Wrappers are needed (according to Intellij) to make sure the receiver is unambiguous
        val result = block(scope)
        _unsubscribeAccess(outer!!, subscription.type)
        return result
    }

    override suspend fun <T> withSubscriptions(subscriptions: Collection<Subscription<*>>, block: suspend CoroutineScope.() -> T): T {
        subscriptions.forEach { subscription -> with(subscription.hook as suspend (Any) -> Unit) {
            _subscribeAccess(outer!!, {event: Any -> this(event) }, subscription.type) }
        }

        val result = block(scope)
        subscriptions.forEach { subscription ->  _unsubscribeAccess(outer!!, subscription.type)}
        return result
    }
}

sealed class FlowResult <T: Flow> (val finishedFlow: T){

    abstract val isFailed: Boolean

    abstract class NotFailedEnd<T: Flow>(finishedFlow: T) : FlowResult<T>(finishedFlow) {
        override val isFailed: Boolean = false

        class RegularEnd<T: Flow>(finishedFlow: T) : NotFailedEnd<T>(finishedFlow)
    }

    abstract class FailedEnd<T: Flow>(finishedFlow: T): FlowResult<T>(finishedFlow) {
        override val isFailed = true
        abstract val errorMessage: String

        class LogicFailed<T: Flow>(finishedFlow: T) : FailedEnd<T>(finishedFlow) {
            override val errorMessage = "Logic failed"
        }

        class NetworkFailed<T: Flow>(finishedFlow: T): FailedEnd<T>(finishedFlow) {
            override val errorMessage = "Network Failed"
        }

        class Cancelled<T: Flow>(unfinishedFlow: T): FailedEnd<T>(unfinishedFlow){
            override val errorMessage = "Flow was cancelled"
        }

    }

}


