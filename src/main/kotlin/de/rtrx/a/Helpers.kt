package de.rtrx.a

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import mu.KLogger
import java.util.concurrent.ConcurrentHashMap


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

val KLogger.logLevel: String get() {
    return if (isErrorEnabled) "ERROR"
    else if (isWarnEnabled) "WARN"
    else if (isInfoEnabled) "INFO"
    else if (isDebugEnabled) "DEBUG"
    else "TRACE"
}
