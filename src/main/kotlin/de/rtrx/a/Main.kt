package de.rtrx.a

import com.google.inject.Guice
import de.rtrx.a.database.DDL
import de.rtrx.a.unex.UnexFlowModule
import de.rtrx.a.unex.UnexFlowDispatcher
import kotlinx.coroutines.*
import mu.KotlinLogging
import kotlin.concurrent.thread

private val logger = KotlinLogging.logger {  }
@ExperimentalCoroutinesApi
fun main(args: Array<String>) {
    val options = parseOptions(args)
    val injector = Guice.createInjector(UnexFlowModule(options))
    injector.getInstance(DDL::class.java).init(
            createDDL = (options.get("createDDL") as Boolean?) ?: true,
            createFunctions = (options.get("createDBFunctions") as Boolean?) ?: true
    )
    if(!((options.get("startDispatcher") as Boolean?) ?:true)) {
        logger.info { "Exiting before starting dispatcher" }
        System.exit(2)
    }

    val dispatcher: UnexFlowDispatcher = injector.getInstance(UnexFlowDispatcher::class.java)

    Runtime.getRuntime().addShutdownHook(thread(false) {
        runBlocking { dispatcher.stop() }
        logger.info { "Stopping Bot" }
    })

    runBlocking {
        dispatcher.join()
    }
}

class ApplicationStoppedException: CancellationException("Application was stopped")