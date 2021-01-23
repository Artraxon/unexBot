package de.rtrx.a.flow

import com.google.inject.Inject
import com.google.inject.Provider
import com.google.inject.assistedinject.Assisted
import com.google.inject.assistedinject.AssistedInject
import com.google.inject.name.Named
import com.uchuhimo.konf.Config
import de.rtrx.a.RedditSpec
import de.rtrx.a.database.Booleable
import de.rtrx.a.database.Linkage
import de.rtrx.a.database.ObservationLinkage
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

typealias MessageCheck = (Message) -> Boolean
interface DeletePrevention{
    suspend fun check(publicRef: PublicContributionReference): DelayedDelete.DeleteResult
}

class Callback<T, R>(private var action: (T) -> R) : (T) -> R{
    private var wasCalled = false
    override operator fun invoke(value: T): R {
        if(wasCalled == true) throw CallbackAlreadyCalledException()
        else return action(value)
    }

    fun addAction(action: (T) -> R){
        val currentAction = this.action
        this.action = { t ->
            currentAction(t)
            action(t)
        }
    }

    class CallbackAlreadyCalledException : Throwable("Callback was already called")
    class NoCallbackDefinedWarning: Throwable("No Callback function was set")
}

interface Conversation {
    /**
     * Setup the conversation
     * @param check A Predicate for identifiying the starting Message of the conversation
     */
    suspend fun CoroutineScope.waitForCompletion(check: MessageCheck): Message

    /**
     * Takes in messages, that are checked and if matched are saved as the origin of the conversation
     */
    suspend fun start(message: Message)

    /**
     * Takes in messages, that are checked if they are a reply to the message matched by [start]
     */
    suspend fun reply(message: Message)


}

interface JumpstartConversation <R> : Conversation {
    suspend fun jumpstart(id: R)
}
class DefferedConversationProvider @Inject constructor (
        private val config: Config
): Provider<JumpstartConversation<String>> { override fun get() = DefferedConversation(config) }

class DefferedConversation @Inject constructor(
        private val config: Config
) : JumpstartConversation<String>{
    private val defferedOwnMessage: CompletableDeferred<String> = CompletableDeferred()
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
                defferedOwnMessage.complete(message.fullName)
            }
        }
    }

    override suspend fun reply(message: Message) {
        deferredScope.await().launch {
            val proceed = select<Boolean> {
                defferedOwnMessage.onAwait { true }
                defferedReply.onAwait { false }
                onTimeout(config[RedditSpec.messages.sent.maxWaitForCompletion]) { false }
            }
            if(proceed){
                if(message.firstMessage == ownMessage){
                    defferedReply.complete(message)
                }
            }
        }
    }

    override suspend fun jumpstart(id: String) {
        deferredScope.await().launch {
            defferedOwnMessage.complete(id)
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
                        .replace("%{HoursUntilDrop}", (config[RedditSpec.messages.sent.maxTimeDistance] / (1000 * 60 * 60)).toString())
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
                        //Prevents People from breaking the spoiler tag
                        reason.take(config[RedditSpec.messages.unread.answerMaxCharacters]).replace("\n\n","  \n")))
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
    fun create(publicContribution: PublicContributionReference, scope: CoroutineScope, skip: Long): DelayedDelete
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

    suspend fun safeSelectTo(clause1: SelectClause1<Any?>): DeleteResult

    companion object {
        val approvedCheck: (ObservationLinkage) -> DeletePrevention = { linkage -> object : DeletePrevention
            {
                override suspend fun check(publicRef: PublicContributionReference): DeleteResult {
                    return linkage.createCheckSelectValues(
                            publicRef.fullName,
                            null,
                            null,
                            emptyArray(),
                            { if (it.get("approved")?.asBoolean ?: false) NotDeletedApproved() else DeleteResult.WasDeleted() }
                    ).checkResult as DeleteResult
                }
            }
        }
        class NotDeletedApproved:DeleteResult.NotDeleted()
    }
    sealed class DeleteResult(bool: Boolean): Booleable{
        override val bool: Boolean = bool
        open class WasDeleted: DeleteResult(false)
        open class NotDeleted: DeleteResult(true){
            class NotDeletedReapproved: NotDeleted()
        }
    }
}

class RedditDelayedDelete @AssistedInject constructor(
        @Named("delayToDeleteMillis") delayToDeleteMillis: Long,
        @Named("delayToFinishMillis") delayToFinishMillis: Long,
        private val unignorer: Unignorer,
        private val preventsDeletion: @JvmSuppressWildcards DeletePrevention,
        @param:Assisted private val publicContribution: PublicContributionReference,
        @param:Assisted private val scope: CoroutineScope,
        @param:Assisted private val skip: Long
): DelayedDelete {
    val removeSubmission = remove()
    lateinit var deletionJob: Deferred<DelayedDelete.DeleteResult>

    private val delayToDeleteMillis: Long
    private val delayToFinishMillis: Long

    init {
        this.delayToDeleteMillis = (delayToDeleteMillis - skip).coerceAtLeast(0)
        this.delayToFinishMillis = (delayToFinishMillis - (delayToDeleteMillis - skip)).coerceAtLeast(0)
    }
    override fun start() {
        deletionJob = scope.async {
            try {
                delay(delayToDeleteMillis)
                removeSubmission.start()
                delay(delayToFinishMillis)
                removeSubmission.await()
            } catch (e: CancellationException) { DelayedDelete.DeleteResult.NotDeleted()}
        }
    }

    override suspend fun safeSelectTo(clause1: SelectClause1<Any?>): DelayedDelete.DeleteResult {
        return select {
            clause1 {
                logger.trace("Received Reply for ${publicContribution.fullName}")
                deletionJob.cancel()
                val removalCheck: DelayedDelete.DeleteResult
                if (removeSubmission.isActive || removeSubmission.isCompleted) {
                    removalCheck = removeSubmission
                            .await()
                            .takeUnless { it is DelayedDelete.DeleteResult.WasDeleted }
                            ?: DelayedDelete.DeleteResult.NotDeleted.NotDeletedReapproved()

                    publicContribution.approve()
                    unignorer(publicContribution)
                    logger.trace("Reapproved ${publicContribution.fullName}")
                } else { removalCheck = DelayedDelete.DeleteResult.NotDeleted() }
                removalCheck
            }
            deletionJob.onAwait {
                logger.trace("Didn't receive an answer for ${publicContribution.fullName}")
                it
            }
        }
    }

    private fun remove(): Deferred<DelayedDelete.DeleteResult> {
        return scope.async<DelayedDelete.DeleteResult> (start = CoroutineStart.LAZY) {
            val willRemove = preventsDeletion.check(publicContribution)
            if(!willRemove.bool) {
                publicContribution.remove()
                logger.trace { "Contribution ${publicContribution.fullName} removed" }
            } else logger.info { "Contribution ${publicContribution.fullName} not removed because it was manually approved" }
            willRemove
        }
    }
    companion object {
        private val logger = KotlinLogging.logger {  }
    }
}

class SubmissionAlreadyPresent<T: Flow>(finishedFlow: T) : FlowResult.NotFailedEnd<T>(finishedFlow)
class NoAnswerReceived<T: Flow>(finishedFlow: T) : FlowResult.NotFailedEnd<T>(finishedFlow)

