package de.rtrx.a

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.uchuhimo.konf.source.asTree
import de.rtrx.a.database.Linkage
import kotlinx.coroutines.CompletableDeferred
import mu.KLogger
import mu.KotlinLogging
import net.dean.jraw.RedditClient
import net.dean.jraw.models.Comment
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentHashMap

class ResponseBodyEmptyException(fullname: String): Exception("Response body from fetching informations about $fullname is empty")
fun RedditClient.getSubmissionJson(fullname: String): JsonObject  {
    val response = request {
        it.url("https://oauth.reddit.com/api/info.json?id=$fullname")
    }
    val body = response.body
    response.raw.close()

    return if(body.isNotEmpty()) {
        JsonParser().parse(body).asJsonObject["data"].asJsonObject["children"].let {
            if (it.asJsonArray.size() == 0) JsonObject()
            else it.asJsonArray[0].asJsonObject["data"].asJsonObject
        }
    } else throw ResponseBodyEmptyException(fullname)
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

fun Throwable.getStackTraceString(): String{
    val sw = StringWriter()
    this.printStackTrace(PrintWriter(sw))
    return sw.toString()
}


