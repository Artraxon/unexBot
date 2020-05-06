package de.rtrx.a.flow

import com.google.gson.JsonObject
import com.nhaarman.mockitokotlin2.*
import com.uchuhimo.konf.Config
import com.uchuhimo.konf.source.Source
import de.rtrx.a.RedditSpec
import de.rtrx.a.database.*
import de.rtrx.a.flow.events.EventType
import de.rtrx.a.flow.events.IncomingMessagesEvent
import de.rtrx.a.flow.events.MessageEvent
import de.rtrx.a.flow.events.SentMessageEvent
import de.rtrx.a.monitor.Monitor
import de.rtrx.a.monitor.MonitorBuilder
import de.rtrx.a.unex.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import net.dean.jraw.models.Comment
import net.dean.jraw.models.DistinguishedStatus
import net.dean.jraw.models.Message
import net.dean.jraw.models.Submission
import net.dean.jraw.references.CommentReference
import net.dean.jraw.references.PublicContributionReference
import net.dean.jraw.references.SubmissionReference
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import kotlin.reflect.jvm.internal.impl.util.CheckResult

val timeout = 3 * 1000L
class UnexFlowTest {

    lateinit var flow: UnexFlow
    lateinit var submission: Submission
    lateinit var submissionRef: SubmissionReference
    lateinit var  ownMessage: Message
    lateinit var reply: Message
    lateinit var comment: Comment
    lateinit var commentReference: CommentReference
    lateinit var linkage: Linkage
    lateinit var stub: FlowStub<SubmissionReference, UnexFlow>
    lateinit var monitor: Monitor
    lateinit var monitorBuilder: MonitorBuilder<*>
    lateinit var conversation: DefferedConversation
    lateinit var delayedDeleteFactory: DelayedDeleteFactory

    private val sentMessageEvent = object : SentMessageEvent {}
    private val incomingMessagesEvent = object : IncomingMessagesEvent {}



    val configValues = Source.from.map.flat(mapOf(
            "reddit.messages.sent.timeSaved" to "10000",
            "reddit.messages.unread.maxAge" to "10000",
            "reddit.scoring.timeUntilRemoval" to "1000",
            "reddit.messages.unread.maxTimeDistance" to "300000"
    ))
    val config = spy(Config { addSpec(RedditSpec) }.withSource(configValues))
    val ownMessageID = "TestMessageID"
    val author = "Testauthor"
    val submissionID = "xxxxx"
    val submissionURL = "http://notreddit.com/r/unex/comments/$submissionID"
    val botMessage = "Please argue with me because of [Your Submission]($submissionURL)"
    val reason = "SomethingSomething Unexpected"
    val defferedResult: CompletableDeferred<FlowResult<UnexFlow>> = CompletableDeferred()
    val result: FlowResult<UnexFlow>?
        get() = defferedResult.takeIf { defferedResult.isCompleted }?.getCompleted()
    val subscribeCalls = mutableListOf<Triple<UnexFlow, suspend (Any) -> Unit, EventType<*>>>()
    val unsubscribeCalls = mutableListOf<Pair<UnexFlow, EventType<*>>>()

    var foundAuthor: String? = null
    var foundSubmissionURL: String? = null

    var commentText: String? = null



    @BeforeEach
    fun setup(){
        monitor = spy(object : Monitor{ override suspend fun start() { } })
        monitorBuilder = mock {
            on {build(any())}.doReturn(monitor)
            on {setBotComment(any())}.doReturn(this.mock)
        }
        linkage = spy(DummyLinkage())
        var submissionRemoved = false
        submission = mock {
            on { author }.doReturn(this@UnexFlowTest.author)
            on { permalink }.doReturn(this@UnexFlowTest.submissionURL)
            on { id }.doReturn(this@UnexFlowTest.submissionID)
        }
        doAnswer { submissionRemoved }.whenever(submission).isRemoved

        submissionRef = mock {
            on { inspect() }.doReturn(this@UnexFlowTest.submission)
            on { id }.doReturn(this@UnexFlowTest.submissionID)
            on { fullName }.doReturn("t3_" + this@UnexFlowTest.submissionID)
            on { remove() }.then { submissionRemoved = true; Unit }
            on { approve() }.then { submissionRemoved = false; Unit }
        }
        ownMessage = mock {
            on { body }.doReturn(this@UnexFlowTest.botMessage)
            on { fullName }.doReturn(this@UnexFlowTest.ownMessageID)
        }
        reply = mock {
            on { body }.doReturn(this@UnexFlowTest.reason)
            on { firstMessage }.doReturn(this@UnexFlowTest.ownMessageID)
        }
        conversation = spy(DefferedConversation(config))
        comment = mock()
        commentReference = mock()
        delayedDeleteFactory = spy(object: DelayedDeleteFactory {
                    override fun create(
                            publicContribution: PublicContributionReference,
                            scope: CoroutineScope
                    ) = createDeletion(config[RedditSpec.scoring.timeUntilRemoval], config[RedditSpec.messages.sent.timeSaved])
                })
        stub = spy(FlowStub(
                submissionRef,
                {flow: UnexFlow, fn, type -> subscribeCalls.add(Triple(flow, fn, type))},
                {flow, type -> unsubscribeCalls.add(flow to type)},
                CoroutineScope(Dispatchers.Default)
                ))
        flow = UnexFlow(
                stub,
                Callback { defferedResult.complete(it) },
                object : MessageComposer {
                    override fun invoke( recipient: String, submissionURL: String) {
                        foundAuthor = recipient
                        foundSubmissionURL = submissionURL
                    }
                },
                object : Replyer {
                    override fun invoke(submission: Submission, s: String): Pair<Comment, CommentReference> {
                        commentText = s
                        return comment to commentReference
                    }
                },
                sentMessageEvent,
                incomingMessagesEvent,
                linkage,
                monitorBuilder,
                conversation,
                delayedDeleteFactory)

        stub.setOuter(flow)
    }

    private fun testFlowOutput() = assertAll(
            { assert(this.author == foundAuthor) { "Recipient $foundAuthor does not match author $author" } },
            { assert(this.submissionURL == foundSubmissionURL) { "SubmissionURL $foundSubmissionURL does not match ${this.submissionURL}" } },
            { assert(this.ownMessage == conversation.ownMessage) },
            { assert(this.reply == conversation.reply) },
            { assert(this.comment == flow.comment) },
            { assert(this.commentText == reply.body)},
            { assert(this.submission.isRemoved.not())},
            { verify(commentReference).distinguish(DistinguishedStatus.MODERATOR, true) },
            { verify(stub, times(1)).subscribe(any(), argThat<MessageEvent> { this is IncomingMessagesEvent})},
            { verify(stub, times(1)).subscribe(any(), argThat<MessageEvent> { this is SentMessageEvent })},
            { verify(linkage, times(1)).insertSubmission(submission)},
            { verify(linkage, times(1)).commentMessage(submissionID, reply, comment)},
            { runBlocking {  verify(monitor).start()} })

    @Test
    fun `flow gets messages in correct order`(){
        runBlocking {
            flow.start()
            conversation.start(ownMessage)
            conversation.reply(reply)
            assert(select<Boolean> {
                flow.incompletableDefferedComment.onAwait { true }
                onTimeout(timeout) {false}
            })
        }
        verify(submissionRef, never()).approve()
        testFlowOutput()
    }


    @Test
    fun `flow gets Messages in wrong order`(){
        runBlocking {
            flow.start()
            conversation.reply(reply)
            conversation.start(ownMessage)
            assert(select<Boolean> {
                flow.incompletableDefferedComment.onAwait { true }
                onTimeout(timeout) {false}
            })
        }
        verify(submissionRef, never()).approve()
        testFlowOutput()

    }

    @Test
    fun `no Answer`() {
        doReturn(createDeletion(1, 10)).whenever(delayedDeleteFactory).create(any(), any())
        doReturn(CheckSelectResult(DelayedDelete.DeleteResult.WasDeleted(), true.toBooleable(), null))
                .whenever(linkage).createCheckSelectValues(
                        any(),
                        anyOrNull(),
                        anyOrNull(),
                        any(),
                        any<(JsonObject) -> Booleable>()
                )

        
        runBlocking {
            flow.start()
            conversation.start(ownMessage)
            expectResult(defferedResult)
        }
        assert(result is NoAnswerReceived)
        verify(submissionRef).remove()
    }

    @Test
    fun `late Answer`() {
        doReturn(createDeletion(1, 10000)).whenever(delayedDeleteFactory).create(any(), any())
        doReturn(CheckSelectResult(DelayedDelete.DeleteResult.WasDeleted(), true.toBooleable(), null))
                .whenever(linkage).createCheckSelectValues(
                        any(),
                        anyOrNull(),
                        anyOrNull(),
                        any(),
                        any<(JsonObject) -> Booleable>()
                )

        runBlocking {
            flow.start()
            conversation.start(ownMessage)
            delay(50L)
            conversation.reply(reply)
            expectResult(defferedResult)
        }

        verify(submissionRef).remove()
        testFlowOutput()
        verify(submissionRef).approve()
    }


    @Test
    fun `link Check`(){
        assert(produceCheckString(submissionID)(botMessage))
        assert(!produceCheckString(submissionID)(botMessage.replace("t", "s")))
        assert(!produceCheckString(submissionID)(botMessage.replace("x", "m")))
    }

    @Test
    fun `submission already present`(){
        doReturn(0).whenever(linkage).insertSubmission(any())
        val result = runBlocking { runForResult() }

        assertAll(
                { assert(result is SubmissionAlreadyPresent) },
                { verify(stub, never()).subscribe(any(), argThat<MessageEvent> { this is IncomingMessagesEvent})},
                { verify(stub, never()).subscribe(any(), argThat<MessageEvent> { this is SentMessageEvent })})

    }

    @Test
    fun `No Removal upon approval`(){
        doReturn(createDeletion(1, 10)).whenever(delayedDeleteFactory).create(any(), any())
        doReturn(CheckSelectResult(DelayedDelete.DeleteResult.NotDeleted(), true.toBooleable(), null))
                .whenever(linkage).createCheckSelectValues(
                        any(),
                        anyOrNull(),
                        anyOrNull(),
                        any(),
                        any<(JsonObject) -> Booleable>()
                )

        val result = runBlocking {
            runForResult {
                conversation.start(ownMessage)
            }
        }

        assert(result is FlowResult.NotFailedEnd<UnexFlow>)
        verify(submissionRef, never()).remove()

    }



    suspend fun runForResult(fn: suspend () -> Unit = {}): FlowResult<UnexFlow>{
        flow.start()
        fn()
        expectResult(defferedResult)
        return result!!
    }

    fun createDeletion(toDeletion: Long, saved: Long): RedditDelayedDelete{
        return spy(RedditDelayedDelete(
                toDeletion,
                saved - toDeletion,
                linkage,
                object : Unignorer { override fun invoke(p1: PublicContributionReference) { } },
                DelayedDelete.approvedCheck(linkage),
                submissionRef,
                CoroutineScope(Dispatchers.Default)))
    }
}


suspend fun expectResult(def: Deferred<*>) = assert(select {
    def.onAwait { true }
    onTimeout(timeout) { false }
})

