package de.rtrx.a.unex

import com.uchuhimo.konf.Config
import de.rtrx.a.RedditSpec
import de.rtrx.a.database.Linkage
import de.rtrx.a.flow.*
import de.rtrx.a.flow.events.EventType
import de.rtrx.a.flow.events.IncomingMessagesEvent
import de.rtrx.a.flow.events.SentMessageEvent
import de.rtrx.a.monitor.Monitor
import de.rtrx.a.monitor.MonitorBuilder
import de.rtrx.a.monitor.MonitorFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import mu.KotlinLogging
import net.dean.jraw.RedditClient
import net.dean.jraw.models.Comment
import net.dean.jraw.models.DistinguishedStatus
import net.dean.jraw.models.Message
import net.dean.jraw.models.Submission
import net.dean.jraw.references.CommentReference
import net.dean.jraw.references.SubmissionReference
import javax.inject.Inject

private val logger = KotlinLogging.logger { }
/**
 * @param composingFn Function that sends the message to the user. First Argument is the recipient, second one the url to the post
 */
class UnexFlow(
        flowStub: FlowStub<SubmissionReference, UnexFlow>,
        private val callback: Callback<in FlowResult<UnexFlow>, Unit>,
        private val composingFn: MessageComposer,
        private val replyFn: Replyer,
        private val unignoreFn: Unignorer,
        private val sentMessages: SentMessageEvent,
        private val incomingMessages: IncomingMessagesEvent,
        private val config: Config,
        private val linkage: Linkage,
        private val monitorBuilder: MonitorBuilder<*>
) : IFlowStub<SubmissionReference> by flowStub,
        Flow{
    private val defferedOwnMessage: CompletableDeferred<Message> = CompletableDeferred()
    val ownMessage get() = defferedOwnMessage.getCompleted()

    private val defferedReply: CompletableDeferred<Message> = CompletableDeferred()
    val reply get() = defferedReply.getCompleted()

    private val defferedComment: CompletableDeferred<Comment> = CompletableDeferred()
    private val defferedCommentRef: CompletableDeferred<CommentReference> = CompletableDeferred()
    val incompletableDefferedComment: Deferred<Comment> get() = defferedComment
    val comment get() = defferedComment.getCompleted()

    val removeSubmission = remove()

    lateinit var monitor: Monitor

    override suspend fun start() {
        launch {
            try {
                logger.trace("Starting flow for ${initValue.fullName}")
                if (linkage.insertSubmission(initValue.inspect()) == 0) {
                    logger.trace("Cancelling flow for ${initValue.fullName} because the submission is already present")
                    callback(SubmissionAlreadyPresent(this@UnexFlow))
                    return@launch
                }
                subscribe(this@UnexFlow::saveOwnMessage, sentMessages)
                subscribe(this@UnexFlow::saveAnswer, incomingMessages)

                composingFn(initValue.inspect().author, initValue.inspect().permalink)

                val deletionJob = launch {
                    try {
                        delay(config[RedditSpec.scoring.timeUntilRemoval])
                        removeSubmission.start()
                        delay(config[RedditSpec.messages.sent.timeSaved] - config[RedditSpec.scoring.timeUntilRemoval])
                    } catch (e: CancellationException) {
                    }
                }

                val answered = select<Boolean> {
                    defferedReply.onAwait {
                        logger.trace("Received Reply for ${initValue.fullName}")
                        deletionJob.cancel()
                        if (removeSubmission.isActive || removeSubmission.isCompleted) {
                            removeSubmission.join()
                            initValue.approve()
                            unignoreFn(initValue)
                            logger.trace("Reapproved ${initValue.fullName}")
                        }
                        true
                    }
                    deletionJob.onJoin {
                        logger.trace("Didn't receive an answer for ${initValue.fullName}")
                        callback(NoAnswerReceived(this@UnexFlow))
                        false
                    }
                }

                if (!answered) return@launch


                val (comment, ref) = replyFn(initValue.inspect(), reply.body)
                defferedComment.complete(comment)
                defferedCommentRef.complete(ref)
                ref.distinguish(DistinguishedStatus.MODERATOR, true)
                linkage.commentMessage(initValue.id, reply, comment)

                logger.trace("Starting Monitor for ${initValue.fullName}")
                monitor = monitorBuilder.setBotComment(comment).build(initValue)
                monitor.start()

                callback(FlowResult.NotFailedEnd.RegularEnd(this@UnexFlow))


            } catch (c: CancellationException){
                callback(FlowResult.FailedEnd.Cancelled(this@UnexFlow))
                logger.warn("Flow for submission ${initValue.fullName} was cancelled")
            }
        }
    }

    suspend fun saveOwnMessage(message: Message){
        launch {
            if(checkMessage(message.body)){
                defferedOwnMessage.complete(message)
            }
        }
    }

    suspend fun saveAnswer(message: Message){
        launch {
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

    private fun remove(): Job {
        return this.launch(start = CoroutineStart.LAZY) {
            val willRemove = linkage.createCheckSelectValues(
                    initValue.fullName,
                    null,
                    null,
                    emptyArray(),
                    { if(it.has("approved")) it["approved"].asBoolean.not() else true }
            )
            if(willRemove) initValue.remove()
        }
    }

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
                id == this.initValue.id
            } catch (t: Throwable) { false }

        }
        return false
    }

    companion object{
        val logger = KotlinLogging.logger {  }
    }
}

interface UnexFlowBuilder : FlowBuilder<UnexFlow, SubmissionReference>{
    fun setSavedMessages(event: SentMessageEvent): UnexFlowBuilder
    fun setIncomingMessages(event: IncomingMessagesEvent): UnexFlowBuilder
    fun setMessagesConfig(messagesConfig: Config): UnexFlowBuilder
    fun setComposingFn(fn: MessageComposer): UnexFlowBuilder
    fun setReplyFn(fn: Replyer): UnexFlowBuilder
    fun setUnignoreFn(fn: Unignorer): UnexFlowBuilder
    fun setLinkage(linkage: Linkage): UnexFlowBuilder
    fun setMonitor(monitorBuilder: MonitorBuilder<*>): UnexFlowBuilder
}
abstract class UnexFlowBuilderDSL : UnexFlowBuilder, FlowBuilderDSL<UnexFlow,  SubmissionReference>()

interface UnexFlowFactory : FlowFactory<UnexFlow, SubmissionReference>{
    fun setSentMessages(sentMessages: SentMessageEvent)
    fun setIncomingMessages(incomingMessages: IncomingMessagesEvent)
}

class RedditUnexFlowFactory @Inject constructor(
        private val config: Config,
        private val composingFn: MessageComposer,
        private val replyFn: Replyer,
        private val unignoreFn: Unignorer,
        private val monitorFactory: MonitorFactory<*, *>,
        private val linkage: Linkage
) : UnexFlowFactory {
    private lateinit var _sentMessages: SentMessageEvent
    private lateinit var _incomingMessages: IncomingMessagesEvent

    override fun createBuilder(dispatcher: FlowDispatcherInterface<UnexFlow>): UnexFlowBuilderDSL {
        return UnexFlowBuilderImpl()
                .setSavedMessages(_sentMessages)
                .setIncomingMessages(_incomingMessages)
                .setMessagesConfig(config)
                .setComposingFn(composingFn)
                .setReplyFn(replyFn)
                .setUnignoreFn(unignoreFn)
                .setMonitor(monitorFactory.get())
                .setLinkage(linkage)
                .setSubscribeAccess { unexFlow: UnexFlow, fn: suspend (Any) -> Unit, type: EventType<Any> ->
                    dispatcher.subscribe(unexFlow, fn, type)
                }
                .setUnsubscribeAccess(dispatcher::unsubscribe) as UnexFlowBuilderDSL
    }

    override fun setSentMessages(sentMessages: SentMessageEvent) {
        if(!this::_sentMessages.isInitialized)this._sentMessages = sentMessages
    }

    override fun setIncomingMessages(incomingMessages: IncomingMessagesEvent) {
        if(!this::_incomingMessages.isInitialized)this._incomingMessages = incomingMessages
    }
    private class UnexFlowBuilderImpl() : UnexFlowBuilderDSL(){
        lateinit var composingFn: MessageComposer
        lateinit var replyFn: Replyer
        lateinit var unignoreFn: Unignorer
        lateinit var sentMessages: SentMessageEvent
        lateinit var incomingMessagesEvent: IncomingMessagesEvent
        lateinit var messagesConfig: Config
        lateinit var linkage: Linkage
        lateinit var monitorBuilder: MonitorBuilder<*>
        override fun setComposingFn(fn: MessageComposer): UnexFlowBuilderDSL {
            this.composingFn = fn
            return this
        }

        override fun setReplyFn(fn: Replyer): UnexFlowBuilderDSL {
            this.replyFn = fn
            return this
        }

        override fun setUnignoreFn(fn: Unignorer): UnexFlowBuilderDSL {
            this.unignoreFn = fn
            return this
        }

        override fun setLinkage(linkage: Linkage): UnexFlowBuilderDSL {
            this.linkage = linkage
            return this
        }

        override fun setSavedMessages(event: SentMessageEvent): UnexFlowBuilderDSL {
            this.sentMessages = event
            return this
        }

        override fun setIncomingMessages(event: IncomingMessagesEvent): UnexFlowBuilderDSL {
            this.incomingMessagesEvent = event
            return this
        }

        override fun setMessagesConfig(messagesConfig: Config): UnexFlowBuilderDSL {
            this.messagesConfig = messagesConfig
            return this
        }

        override fun setMonitor(monitor: MonitorBuilder<*>): UnexFlowBuilderDSL {
            this.monitorBuilder = monitor
            return this
        }

        override fun build(): UnexFlow {
            val stub = FlowStub(_initValue!!, _subscribeAccess, _unsubscribeAccess, CoroutineScope(Dispatchers.Default))
            val flow = UnexFlow( stub, _callback, composingFn, replyFn, unignoreFn, sentMessages, incomingMessagesEvent, messagesConfig, linkage, monitorBuilder)
            stub.setOuter(flow)
            return flow
        }
    }

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

interface Unignorer : (SubmissionReference) -> Unit
class RedditUnignorer @Inject constructor(
        private val redditClient: RedditClient
) : Unignorer{
    override fun invoke(submissionReference: SubmissionReference) {
        val response = redditClient.request {
            it.url("https://oauth.reddit.com/api/unignore_reports").post(
                    mapOf( "id" to submissionReference.fullName )
            )
        }
        if(response.successful.not()){
            logger.warn { "couldn't unignore reports from post ${submissionReference.fullName}" }
        }

    }

}

class SubmissionAlreadyPresent(finishedFlow: UnexFlow) : FlowResult.NotFailedEnd<UnexFlow>(finishedFlow)
class NoAnswerReceived(finishedFlow: UnexFlow) : FlowResult.NotFailedEnd<UnexFlow>(finishedFlow)

