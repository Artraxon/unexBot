package de.rtrx.a.flow

import kotlinx.coroutines.*
import java.util.concurrent.Executors

interface IsolationStrategy{
    /**
     * Executes a list of functions with the context of the flow.
     * @param data Some Object that is passed to the functions as an argument
     */
    fun <R> executeEach(data: R, flow: Flow, fns: List<suspend (R) -> Unit>)

    /**
     * Removes the current Context for the flow and does cleanup.
     * Calling [executeEach] with [flow] is possible and results in a new context beeing created.
     */
    fun removeFlow(flow: Flow)

    /**
     * Calls removeFlow on every flow that has a context assigned to it
     */
    fun removeAllFlows()

    /**
     * Stops all currently running jobs.
     * Note that [stop] does not imply [removeAllFlows]
     */
    fun stop()
}

class SingleFlowIsolation: IsolationStrategy {
    val flows = mutableMapOf<Flow, ExecutorCoroutineDispatcher>()
    val executorRoutine = CoroutineScope(Dispatchers.Default)
    override fun <R> executeEach(data: R, flow: Flow, fns: List<suspend (R) -> Unit>) {
        executorRoutine.launch(flows.getOrPut(flow) { Executors.newSingleThreadExecutor().asCoroutineDispatcher() }) {
             for (fn in fns) fn(data)
        }
    }

    override fun removeFlow(flow: Flow) {
        flows.remove(flow)?.close()
    }

    override fun removeAllFlows() {
        flows.keys.forEach(this::removeFlow)
    }


    override fun stop() {
        flows.forEach { it.value.cancel(SingleFlowIsolationStopped()) }
    }

    inner class SingleFlowIsolationStopped : CancellationException("Single Flow Isolation Strategy was stopped")

}