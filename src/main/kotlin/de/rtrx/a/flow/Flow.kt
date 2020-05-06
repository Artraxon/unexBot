package de.rtrx.a.flow

import de.rtrx.a.flow.events.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * @param S The type of the returned Result
 */
interface Flow : CoroutineScope{
    suspend fun start()
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
    fun <R: Any> setSubscribeAccess(access: (T, suspend (R) -> Unit, EventType<R>) -> Unit): FlowBuilder<T, M>

    /**
     * Sets a method that can be used by the flow to unsubscribe from event types
     */
    fun setUnsubscribeAccess(access: (T, EventType<*>) -> Unit): FlowBuilder<T, M>

    /**
     * Sets the Coroutine Scope for the Flow
     */
    fun setCoroutineScope(scope: CoroutineScope): FlowBuilder<T, M>

    /**
     * Creates the Flow
     */
    fun build(): T
}

abstract class FlowBuilderDSL <T, M: Any > : FlowBuilder<T, M>
        where T: IFlowStub<M>,
              T : Flow{
    protected var _initValue: M? = null
    protected var _callback: Callback<in FlowResult<T>, Unit> = Callback {_ -> Unit}
    protected var _subscribeAccess: (T, suspend (Any) -> Unit, EventType<*>) -> Unit = { _, _, _ -> Unit}
    protected var _unsubscribeAccess: (T, EventType<*>) -> Unit = { _, _ -> Unit}
    protected var _scope: CoroutineScope = CoroutineScope(Dispatchers.Default)

    override fun <R: Any> setSubscribeAccess(access: (T, suspend (R) -> Unit, EventType<R>) -> Unit): FlowBuilder<T, M> {
        _subscribeAccess = {flow, function, type: EventType<*> -> access(flow, function, type as EventType<R>)}
        return this
    }

    override fun setUnsubscribeAccess(access: (T, EventType<*>) -> Unit): FlowBuilder<T, M> {
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
    fun create(dispatcher: FlowDispatcherInterface<T>, initValue: M, callback: Callback<FlowResult<T>, Unit>): T
}


/**
 * @param M The type of the initial value
 */
interface IFlowStub<M> {
    val initValue: M
    val coroutineContext: CoroutineContext

    fun <R: Any> subscribe(function: suspend (R) -> Unit, type: EventType<R>)
    fun <R: Any> unsubscribe(type: EventType<R>)
}

/**
 * @param M the type of the initial Value being supplied
 */
class FlowStub <M, C: IFlowStub<M>> (
        override val initValue: M,
        private val _subscribeAccess: (C, suspend (Any) -> Unit, EventType<*>) -> Unit,
        private val _unsubscribeAccess: (C, EventType<*>) -> Unit,
        private val scope: CoroutineScope)
    : IFlowStub<M> {
    private var outer: C? = null

    fun setOuter(outer: C) = if(this.outer == null) this.outer = outer else Unit

    override fun <R: Any> subscribe(function: suspend (R) -> Unit, type: EventType<R>) {
        _subscribeAccess(outer!!, { event: Any -> function(event as R) } , type)
    }

    override fun <R: Any> unsubscribe(type: EventType<R>) {
        _unsubscribeAccess(outer!!, type)
    }

    override val coroutineContext: CoroutineContext
        get() = scope.coroutineContext
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


