package de.rtrx.a

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import net.dean.jraw.models.Submission
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess


fun getSubmissionJson(fullname: String): JsonObject?  {
    val response = reddit.request {
        it.url("https://oauth.reddit.com/api/info.json?id=$fullname")
    }
    return if(response.body.isNotEmpty()) {
        JsonParser().parse(response.body).asJsonObject["data"].asJsonObject["children"].let {
            if (it.asJsonArray.size() == 0) null
            else it.asJsonArray[0].asJsonObject["data"].asJsonObject
        }
    } else null
}

fun unignoreReports(fullname: String){
    val response = reddit.request {
        it.url("https://oauth.reddit.com/api/unignore_reports").post(
            mapOf( "id" to fullname )
        )
    }
    if(response.successful.not()){
        logger.warn { "couldn't unignore reports from post ${fullname}" }
    }
}

fun <R, T> ConcurrentHashMap<R, CompletableDeferred<T>>.access(key: R): CompletableDeferred<T>{
    return getOrPut(key, { CompletableDeferred() })
}

private val availableOptions: Map<String, Pair<(String)-> Boolean, (String) -> Any>> = mapOf(
    "createDDL" to ({it: String -> listOf("true", "false").contains(it)} to {str: String -> if(str == "true") true else false}),
    "createDBFunctions" to ({it: String -> listOf("true", "false").contains(it) } to {str: String -> if(str == "true") true else false}),
        "configPath" to ({it: String -> true} to {str: String -> str})
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
