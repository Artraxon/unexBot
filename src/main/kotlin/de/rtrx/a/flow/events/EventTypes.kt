package de.rtrx.a.flow.events

import com.uchuhimo.konf.Config
import de.rtrx.a.RedditSpec
import de.rtrx.a.flow.Flow
import de.rtrx.a.jrawExtension.subscribe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.launch
import mu.KotlinLogging
import net.dean.jraw.RedditClient
import net.dean.jraw.models.Message
import net.dean.jraw.models.Submission
import net.dean.jraw.models.SubredditSort
import net.dean.jraw.references.SubmissionReference
import javax.inject.Inject
import javax.inject.Named
import kotlin.coroutines.CoroutineContext


/**
* Represents a real event on the Reddit API Side
 * @param R The Type of the data that is being produced
**/
interface EventType<out R: Any>

/**
 * Utility Class that can be used if it is desired to implement the ReceiveChannel here and not in the Factory
 */
abstract class EventStream<out R: Any>(_outReceiver: (() -> ReceiveChannel<R>) -> Unit) : EventType<R> {
    protected abstract val out: ReceiveChannel<R>
    private fun _getOut() = out

    init {
        _outReceiver { _getOut() }
    }
}

/**
 * @param E The type of the Event
 * @param R Type of [E]
 * @param I Type of the ID that can be used to get an Event
 */
interface EventTypeFactory<E: EventType<R>, R: Any, in I>{
    fun create(id: I): Pair<E, ReceiveChannel<R>>
}

fun <E: EventType<R>, R: Any, I: Any> UniversalEventTypeFactory(factoryFn: (I) -> Pair<E, ReceiveChannel<R>> ): EventTypeFactory<E, R, I> {
    return object : EventTypeFactory<E, R, I> {
        override fun create(id: I) = factoryFn(id)
    }
}

interface NewPostEventFactory: EventTypeFactory<NewPostEvent, Submission, String>

interface NewPostEvent : EventType<Submission>

class RedditNewPostEvent( private val out: ReceiveChannel<Submission> ): NewPostEvent
class RedditNewPostEventFactory @Inject constructor(
        private val config: Config,
        private val redditClient: RedditClient
): NewPostEventFactory{
    override fun create(id: String): Pair<NewPostEvent, ReceiveChannel<Submission>> {
        val (_, out) = redditClient
                .subreddit(id)
                .posts()
                .sorting(SubredditSort.NEW)
                .limit(config[RedditSpec.submissions.limit])
                .build()
                .subscribe(config[RedditSpec.submissions.waitIntervall], ageLimit = config[RedditSpec.submissions.maxTimeDistance])
        val eventType = RedditNewPostEvent(out)
        return eventType to out
    }
}

interface NewPostReferenceEvent: EventType<SubmissionReference>
interface NewPostReferenceFactory: EventTypeFactory<NewPostReferenceEvent, SubmissionReference, String>

class RedditNewPostReferenceEvent( private val out: ReceiveChannel<SubmissionReference>): NewPostReferenceEvent
class RedditNewPostReferenceFactory @Inject constructor(
        private val config: Config,
        private val redditClient: RedditClient
): NewPostReferenceFactory {
    override fun create(id: String): Pair<NewPostReferenceEvent, ReceiveChannel<SubmissionReference>> {
        val (_, out) = redditClient
                .subreddit(id)
                .posts()
                .sorting(SubredditSort.NEW)
                .limit(config[RedditSpec.submissions.limit])
                .build()
                .subscribe(config[RedditSpec.submissions.waitIntervall], ageLimit = config[RedditSpec.submissions.maxTimeDistance])
        val submissionReferences = CoroutineScope(Dispatchers.Default).produce(capacity = Channel.UNLIMITED) {
            for (submission in out) {
                KotlinLogging.logger { }.trace("Received new Submission ${submission.fullName}")
                send(submission.toReference(redditClient))
            }
        }
        val eventType = RedditNewPostReferenceEvent(submissionReferences)
        return eventType to submissionReferences
    }
}

interface MessageEvent: EventType<Message>
interface SentMessageEvent: MessageEvent
interface IncomingMessagesEvent: MessageEvent
interface MessageEventFactory<E: MessageEvent>: EventTypeFactory<E, Message, String>

/**
 * A Factory creating EventTypes representing the "unread" api Endpoint. For each [create] call a new instance will be created, but
 * interacting with it (like marking messages as read) might interfere with other instances.
 * Received messages will not be marked read automatically.
 * It is recommended that you do so in the Dispatcher, Flow or Multiplexer.
 */
interface IncomingMessageFactory: MessageEventFactory<IncomingMessagesEvent>

class InboxEventFactory<E: MessageEvent>(
        private val where: String,
        private val limit: Int,
        private val waitIntervall: Long,
        private val redditClient: RedditClient,
        private val creator: (ReceiveChannel<Message>) -> E
): MessageEventFactory<E>{
    override fun create(id: String): Pair<E, ReceiveChannel<Message>> {
        val (_, out) = redditClient
                .me()
                .inbox()
                .iterate(where)
                .limit(limit)
                .build()
                .subscribe(waitIntervall)

        val eventType = creator(out)
        return eventType to out
    }
}

interface SentMessageFactory: MessageEventFactory<SentMessageEvent>

class RedditSentMessageFactory @Inject constructor(
        private val config: Config,
        private val redditClient: RedditClient
): SentMessageFactory, MessageEventFactory<SentMessageEvent> by InboxEventFactory(
        "sent",
        config[RedditSpec.messages.sent.limit],
        config[RedditSpec.messages.sent.waitIntervall],
        redditClient,
        ::RedditSentMessageEvent )

class RedditIncomingMessageFactory @Inject constructor(
        private val config: Config,
        private val redditClient: RedditClient
): IncomingMessageFactory, MessageEventFactory<IncomingMessagesEvent> by InboxEventFactory(
        "unread",
        config[RedditSpec.messages.unread.limit],
        config[RedditSpec.messages.unread.waitIntervall],
        redditClient,
        ::RedditIncomingMessageEvent
)

class RedditSentMessageEvent(private val out: ReceiveChannel<Message>): SentMessageEvent
class RedditIncomingMessageEvent(private val out: ReceiveChannel<Message>): IncomingMessagesEvent

class MarkAsReadFlow @Inject constructor(private val redditClient: RedditClient) : Flow {
    override suspend fun start() { }

    suspend fun markAsRead(message: Message){
        redditClient.me().inbox().markRead(true, message.fullName)
    }

    override val coroutineContext: CoroutineContext = Dispatchers.Default
}

