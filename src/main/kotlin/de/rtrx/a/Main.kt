package de.rtrx.a

import com.google.inject.Guice
import com.google.inject.internal.cglib.proxy.`$Dispatcher`
import de.rtrx.a.database.DDL
import de.rtrx.a.flow.FlowDispatcherInterface
import de.rtrx.a.flow.FlowModule
import de.rtrx.a.unex.UnexFlowDispatcher
import kotlinx.coroutines.*
import mu.KotlinLogging
import kotlin.concurrent.thread

private val logger = KotlinLogging.logger {  }
@ExperimentalCoroutinesApi
fun main(args: Array<String>) {
    val options = parseOptions(args)
    val injector = Guice.createInjector(FlowModule(options))
    injector.getInstance(DDL::class.java).init(
            createDDL = (options.get("createDDL") as Boolean?) ?: true,
            createFunctions = (options.get("createDBFunctions") as Boolean?) ?: true
    )
    if(!((options.get("startDispatcher") as Boolean?) ?:true)) {
        logger.info { "Exiting before starting dispatcher" }
        return
    }

    val dispatcher: UnexFlowDispatcher = injector.getInstance(UnexFlowDispatcher::class.java)
    Runtime.getRuntime().addShutdownHook(thread(false) {
        runBlocking { dispatcher.stop() }
        logger.info { "Stopping Bot" }
    })
}

class ApplicationStoppedException: CancellationException("Application was stopped")