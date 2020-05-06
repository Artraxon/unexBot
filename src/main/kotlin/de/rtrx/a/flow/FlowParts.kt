package de.rtrx.a.flow

import com.google.inject.Inject
import com.google.inject.Provider
import com.google.inject.assistedinject.Assisted
import com.google.inject.assistedinject.AssistedInject
import com.uchuhimo.konf.Config
import de.rtrx.a.RedditSpec
import de.rtrx.a.database.Linkage
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.SelectClause1
import kotlinx.coroutines.selects.select
import mu.KotlinLogging
import net.dean.jraw.RedditClient
import net.dean.jraw.models.Comment
import net.dean.jraw.models.Message
import net.dean.jraw.models.Submission
import net.dean.jraw.references.CommentReference
import net.dean.jraw.references.PublicContributionReference
import javax.inject.Named

typealias MessageCheck = (Message) -> Boolean
typealias DeletePrevention = suspend (PublicContributionReference) -> Boolean

class Callback<T, R>(private val action: (T) -> R) : (T) -> R{
    private var wasCalled = false
    override operator fun invoke(value: T): R {
        if(wasCalled == true) throw CallbackAlreadyCalledException()
        else return action(value)
    }

    class CallbackAlreadyCalledException : Throwable("Callback was already called")
    class NoCallbackDefinedWarning: Throwable("No Callback function was set")
}

interface Conversation {
    suspend fun CoroutineScope.waitForCompletion(check: MessageCheck): Message
    suspend fun start(message: Message)
    suspend fun reply(message: Message)
}

class DefferedConversationProvider @Inject constructor (
        private val config: Config
): Provider<Conversation> { override fun get() = DefferedConversation(config) }

class DefferedConversation @Inject constructor(
        private val config: Config
) : Conversation{
    private val defferedOwnMessage: CompletableDeferred<Message> = CompletableDeferred()
    val ownMessage get() = defferedOwnMessage.getCompleted()

    private val defferedReply: CompletableDeferred<Message> = CompletableDeferred()
    val reply get() = defferedReply.getCompleted()

    private val deferredScope: CompletableDeferred<CoroutineScope> = CompletableDeferred()
    private lateinit var checkMessage: (Message) -> Boolean

    override suspend fun CoroutineScope.waitForCompletion(check: MessageCheck): Message {
        deferredScope.complete(this)
        checkMessage = check
        return defferedReply.await()
    }

    override suspend fun start(message: Message) {
        deferredScope.await().launch {
            if(checkMessage(message)){
                defferedOwnMessage.complete(message)
            }
        }
    }

    override suspend fun reply(message: Message) {
        deferredScope.await().launch {
            val proceed = select<Boolean> {
                defferedOwnMessage.onAwait { true }
                defferedReply.onAwait { false }
                onTimeout(config[RedditSpec.messages.unread.maxTimeDistance]) { false }
            }
            if(proceed){
                if(message.firstMessage == ownMessage.fullName){
                    defferedReply.complete(message)
                }
            }
        }
    }

}

fun produceCheckString(submissionID: String): (String) -> Boolean {
    fun checkMessage(body: String): Boolean {
        val startIndex = body.indexOf("(")
        val endIndex = body.indexOf(")")

        //Check whether the parent message was sent by us and if a link exists.
        if (startIndex >= 0 && endIndex >= 0) {
            //Extract the Link from the parent message by cropping around the first parenthesis
            return try {
                val id = body
                        .slice((startIndex + 1) until endIndex)
                        //Extract the ID of the submission
                        .split("comments/")[1].split("/")[0]
                id == submissionID
            } catch (t: Throwable) {
                false
            }

        }
        return false
    }
    return ::checkMessage
}

fun produceCheckMessage(submissionID: String): (Message) -> Boolean {
    return {message ->  produceCheckString(submissionID).invoke(message.body)}
}
interface MessageComposer: (String, String) -> Unit
class RedditMessageComposer @Inject constructor(
        private val redditClient: RedditClient,
        private val config: Config
): MessageComposer {
    override fun invoke(author: String, postURL: String) {
        redditClient.me().inbox().compose(
                dest = author,
                subject = config[RedditSpec.messages.sent.subject],
                body = config[RedditSpec.messages.sent.body]
                        .replace("%{Submission}", postURL)
                        .replace("%{HoursUntilDrop}", (config[RedditSpec.messages.sent.timeSaved] / (1000 * 60 * 60)).toString())
                        .replace("%{subreddit}", config[RedditSpec.subreddit])
                        .replace("%{MinutesUntilRemoval}", (config[RedditSpec.scoring.timeUntilRemoval] / (1000 * 60)).toString())
        )
    }
}

interface Replyer : (Submission, String) -> Pair<Comment, CommentReference>
class RedditReplyer @Inject constructor(
        private val redditClient: RedditClient,
        private val config: Config): Replyer {
    override fun invoke(submission: Submission, reason: String): Pair<Comment, CommentReference> {
        val comment = submission.toReference(redditClient)
                .reply(config[RedditSpec.scoring.commentBody].replace("%{Reason}",
                        reason.take(config[RedditSpec.messages.unread.answerMaxCharacters])))
        return comment to comment.toReference(redditClient)
    }
}

interface Unignorer : (PublicContributionReference) -> Unit

class RedditUnignorer @Inject constructor(
        private val redditClient: RedditClient
) : Unignorer{
    override fun invoke(publicContribution: PublicContributionReference) {
        val response = redditClient.request {
            it.url("https://oauth.reddit.com/api/unignore_reports").post(
                    mapOf( "id" to publicContribution.fullName )
            )
        }
        if(response.successful.not()){
            KotlinLogging.logger {  }.warn { "couldn't unignore reports from post ${publicContribution.fullName}" }
        }

    }

}

interface DelayedDeleteFactory{
    fun create(publicContribution: PublicContributionReference, scope: CoroutineScope): DelayedDelete
}

/**
 * Represents a safe Way for deleting (and reapproving) a [PublicContributionReference] depending on the outcome of a selectClause
 * (For Example The Finishing of a job or an deferred Value becoming available)
 */
interface DelayedDelete {
    /**
     * Starts The Implementation Specific counter for deleting the Post
     */
    fun start()

    suspend fun safeSelectTo(clause1: SelectClause1<Any?>): Boolean

    companion object {
        val approvedCheck: (Linkage) -> DeletePrevention = { linkage ->
            { publicContribution: PublicContributionReference ->
                linkage.createCheckSelectValues(
                        publicContribution.fullName,
                        null,
                        null,
                        emptyArray(),
                        { if (it.has("approved")) it["approved"].asBoolean.not() else true }
                )
            }
        }
    }
}

class RedditDelayedDelete @AssistedInject constructor(
        @param:Named("delayToDeleteMillis") private val delayToDeleteMillis: Long,
        @param:Named("delayToFinishMillis") private val delayToFinishMillis: Long,
        private val linkage: Linkage,
        private val unignorer: Unignorer,
        private val preventsDeletion: DeletePrevention,
        @param:Assisted private val publicContribution: PublicContributionReference,
        @param:Assisted private val scope: CoroutineScope
): DelayedDelete {
    val removeSubmission = remove()
    lateinit var deletionJob: Job

    override fun start() {
        deletionJob = scope.launch {
            try {
                delay(delayToDeleteMillis)
                removeSubmission.start()
                delay(delayToFinishMillis)
            } catch (e: CancellationException) { }
        }
    }

    override suspend fun safeSelectTo(clause1: SelectClause1<Any?>): Boolean {
        return select {
            clause1 {
                logger.trace("Received Reply for ${publicContribution.fullName}")
                deletionJob.cancel()
                if (removeSubmission.isActive || removeSubmission.isCompleted) {
                    removeSubmission.join()
                    publicContribution.approve()
                    unignorer(publicContribution)
                    logger.trace("Reapproved ${publicContribution.fullName}")
                }
                true
            }
            deletionJob.onJoin {
                logger.trace("Didn't receive an answer for ${publicContribution.fullName}")
                false
            }
        }
    }

    private fun remove(): Job {
        return scope.launch(start = CoroutineStart.LAZY) {
            val willRemove = preventsDeletion(publicContribution )
            if(willRemove) publicContribution.remove()
        }
    }
    companion object {
        private val logger = KotlinLogging.logger {  }
    }
}

class SubmissionAlreadyPresent<T: Flow>(finishedFlow: T) : FlowResult.NotFailedEnd<T>(finishedFlow)
class NoAnswerReceived<T: Flow>(finishedFlow: T) : FlowResult.NotFailedEnd<T>(finishedFlow)