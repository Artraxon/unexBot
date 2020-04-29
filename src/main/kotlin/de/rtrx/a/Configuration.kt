package de.rtrx.a

import com.uchuhimo.konf.Config
import com.uchuhimo.konf.ConfigSpec
import com.uchuhimo.konf.notEmptyOr
import com.uchuhimo.konf.source.yaml
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess


fun initConfig(path: String?): Config{
    return Config { addSpec(RedditSpec); addSpec(DBSpec); addSpec(LoggingSpec)}
            //Adding the config Sources
            .from.yaml.resource("config.yml")
            .run {
                if(path != null) {
                    from.yaml.watchFile(
                            run {
                                val filePath = path.notEmptyOr("${System.getProperty("user.dir")}/config.yml")
                                val file = File(filePath)
                                if (file.createNewFile() || Files.size(file.toPath()) == 0L) {
                                    println("Cloning Config...")
                                    val inputStream = RedditSpec::class.java.getResourceAsStream("/config.yml")
                                    Files.copy(inputStream, Paths.get(filePath), StandardCopyOption.REPLACE_EXISTING)
                                }
                                filePath
                            })
                } else this
            }
            .from.env()
}


object RedditSpec: ConfigSpec("reddit") {
    val subreddit by required<String>()

    object credentials: ConfigSpec("credentials"){
        val username by required<String>()
        val clientID by required<String>()
        val clientSecret by required<String>()
        val password by required<String>()
        val operatorUsername by required<String>()
        val appID by required<String>()
    }
    object submissions : ConfigSpec("submissions"){
        val maxTimeDistance by required<Long>()
        val limit by required<Int>()
        val waitIntervall by required<Long>()
    }

    object messages : ConfigSpec("messages") {
        object sent: ConfigSpec("sent") {
            val timeSaved by required<Long>()
            val maxTimeDistance by required<Long>()
            val limit by required<Int>()
            val waitIntervall by required<Long>()
            val subject by required<String>()
            val body by required<String>()
        }

        object unread: ConfigSpec("unread"){
            val maxTimeDistance by required<Long>()
            val waitIntervall by required<Long>()
            val limit by required<Int>()
            val answerMaxCharacters by required<Int>()
        }
    }


    object scoring: ConfigSpec("scoring"){
        val timeUntilRemoval by required<Long>()
        val commentBody by required<String>()
    }

    object checks: ConfigSpec("checks"){
        object DB: ConfigSpec("db"){
            val every by required<Long>()
            val forTimes by required<Int>()
            val comments_amount by required<Int>()
            val depth by required<Int>()
            val commentWaitIntervall by required<Long>()
        }
    }

}

object DBSpec: ConfigSpec("DB"){
    val username by required<String>()
    val password by required<String>()
    val address by required<String>()
    val db by required<String>()
}
object LoggingSpec: ConfigSpec("Logging") {
    val logLevel by required<String>()
}

private val verifyBoolean = { it: String -> if(it == "true" || it == "false") true else false}
private val convertBoolean = {it: String -> if(it == "true") true else false}
private val availableOptions: Map<String, Pair<(String)-> Boolean, (String) -> Any>> = mapOf(
        "createDDL" to (verifyBoolean to convertBoolean),
        "createDBFunctions" to (verifyBoolean to convertBoolean),
        "configPath" to ({it: String -> true} to {str: String -> str}),
        "useDB" to (verifyBoolean to convertBoolean),
        "startDispatcher" to (verifyBoolean to convertBoolean)
)

fun parseOptions(args: Array<String>): Map<String, Any>{
    var (pairs, invalids) = args.map { it.split("=") }.partition { it.size == 2 }
    for(pair in pairs){
        if(availableOptions.get(pair[0])?.first?.invoke(pair[1])?.not() ?: true){
            pairs = pairs.minusElement(pair)
            invalids = pairs.plusElement(pair)
        }
    }

    if(invalids.size != 0){
        for(invalidOption in invalids){
            println("Invalid Option or Value: $invalidOption")
        }
        exitProcess(1)
    }

    return pairs.map { it[0] to availableOptions[it[0]]!!.second(it[1]) }.toMap()
}


