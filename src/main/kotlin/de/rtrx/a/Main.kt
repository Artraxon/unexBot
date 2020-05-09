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
    val configPath = options.get("configPath") as String? ?: ""
    val useDB = options.get("useDB") as Boolean? ?: true
    val injector = Guice.createInjector(
            CoreModule(initConfig(configPath, RedditSpec, DBSpec), useDB),
            UnexFlowModule())

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