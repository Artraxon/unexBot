package de.rtrx.a.flow

import com.uchuhimo.konf.Config
import de.rtrx.a.RedditSpec
import kotlinx.coroutines.*
import net.dean.jraw.RedditClient
import net.dean.jraw.models.Message
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentSkipListSet
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

class MarkAsReadFlow @Inject constructor(private val redditClient: RedditClient) : Flow {
    override suspend fun start() { }

    suspend fun markAsRead(message: Message){
        redditClient.me().inbox().markRead(true, message.fullName)
    }

    override val coroutineContext: CoroutineContext = Dispatchers.Default
}

class ArchivingFlow @Inject constructor(private val config: Config) : Flow {
    lateinit var startDate: Instant
    private val mutableMessages = ConcurrentSkipListSet<Message> { t1, t2 -> t1.created.compareTo(t2.created) }
    public val messages get() = LinkedList(mutableMessages)
    private val allMessagesSaved = CompletableDeferred<Unit>()
    public val finished: Deferred<Unit> = allMessagesSaved

    override suspend fun start() {
        startDate = Instant.now()
        //Complete archive when no messages come
        launch {
            delay(config[RedditSpec.messages.sent.waitIntervall] * 5)
            if(mutableMessages.isEmpty()){
                allMessagesSaved.complete(Unit)
            }
        }
        allMessagesSaved.await()
    }

    override val coroutineContext: CoroutineContext = Dispatchers.Default

    suspend fun saveMessage(message: Message){
        //Don't save new messages
        if(startDate < message.created.toInstant()) return
        if(!allMessagesSaved.isActive){
            throw IllegalStateException("Couldn't add message ${message.id}, Archive is already finished")
        }

        mutableMessages.add(message)

        //Complete archive when no messages come after this one anymore
        launch {
            delay(config[RedditSpec.messages.sent.waitIntervall] * 3)
            if(mutableMessages.last().created == message.created){
                allMessagesSaved.complete(Unit)
            }
        }
    }


}