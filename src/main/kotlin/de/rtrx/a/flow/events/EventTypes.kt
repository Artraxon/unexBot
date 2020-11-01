package de.rtrx.a.flow.events

import com.uchuhimo.konf.Config
import de.rtrx.a.RedditSpec
import de.rtrx.a.jrawExtension.subscribe
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import mu.KotlinLogging
import net.dean.jraw.RedditClient
import net.dean.jraw.models.Message
import net.dean.jraw.models.Submission
import net.dean.jraw.models.SubredditSort
import net.dean.jraw.references.SubmissionReference
import javax.inject.Inject
import javax.inject.Named


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
private val logger = KotlinLogging.logger {  }
class RedditNewPostReferenceFactory @Inject constructor(
        private val config: Config,
        private val redditClient: RedditClient
): NewPostReferenceFactory {
    override fun create(id: String): Pair<NewPostReferenceEvent, ReceiveChannel<SubmissionReference>> {
        logger.debug { "Creating a RedditNewPostReference Event for id $id" }
        val (_, out) = redditClient
                .subreddit(id)
                .posts()
                .sorting(SubredditSort.NEW)
                .limit(config[RedditSpec.submissions.limit])
                .build()
                .subscribe(config[RedditSpec.submissions.waitIntervall], ageLimit = config[RedditSpec.submissions.maxTimeDistance])
        val submissionReferences = CoroutineScope(Dispatchers.Default).produce(capacity = Channel.UNLIMITED) {
            for (submission in out) {
                KotlinLogging.logger { }.trace("Received new Submission ${submission.fullName} in RedditNewPostReferenceEvent with id $id")
                send(submission.toReference(redditClient))
            }
        }
        val eventType = RedditNewPostReferenceEvent(submissionReferences)
        return eventType to submissionReferences
    }
}

interface MessageEvent: EventType<Message>{
    /**
     * Starts the Event if not started yet.
     * @return whether this call made this event start. Returning false means that this event was already running
     */
    fun start(): Boolean
}
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
        private val ageLimit: Long,
        private val redditClient: RedditClient,
        private val creator: (ReceiveChannel<Message>, Job) -> E,
        private val channelStart: CoroutineStart
): MessageEventFactory<E>{
    override fun create(id: String): Pair<E, ReceiveChannel<Message>> {
        val (job, out) = redditClient
                .me()
                .inbox()
                .iterate(where)
                .limit(limit)
                .build()
                .subscribe(waitIntervall, ageLimit = ageLimit, channelStart = channelStart)

        val eventType = creator(out, job)
        return eventType to out
    }
}

interface SentMessageFactory: MessageEventFactory<SentMessageEvent>

class RedditSentMessageFactory @Inject constructor(
        private val config: Config,
        private val redditClient: RedditClient,
        @param:Named("RedditSentMessage") private val channelStart: CoroutineStart,
): SentMessageFactory, MessageEventFactory<SentMessageEvent> by InboxEventFactory(
        "sent",
        config[RedditSpec.messages.sent.limit],
        config[RedditSpec.messages.sent.waitIntervall],
        config[RedditSpec.messages.sent.maxTimeDistance],
        redditClient,
        ::RedditSentMessageEvent,
        channelStart
)

class RedditIncomingMessageFactory @Inject constructor(
        private val config: Config,
        private val redditClient: RedditClient,
        @param:Named("RedditIncomingMessage") private val channelStart: CoroutineStart,
): IncomingMessageFactory, MessageEventFactory<IncomingMessagesEvent> by InboxEventFactory(
        "unread",
        config[RedditSpec.messages.unread.limit],
        config[RedditSpec.messages.unread.waitIntervall],
        config[RedditSpec.messages.sent.maxTimeDistance],
        redditClient,
        ::RedditIncomingMessageEvent,
        channelStart
)

class RedditSentMessageEvent(private val out: ReceiveChannel<Message>, private val job: Job): SentMessageEvent{
    override fun start() = job.start()

}
class RedditIncomingMessageEvent(private val out: ReceiveChannel<Message>, private val job: Job): IncomingMessagesEvent {
    override fun start() = job.start()
}

