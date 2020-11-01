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
import de.rtrx.a.flow.events.comments.FullComments
import de.rtrx.a.flow.events.comments.ManuallyFetchedEvent
import de.rtrx.a.monitor.IDBCheck
import de.rtrx.a.monitor.IDBCheckBuilder
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
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsSource
import org.mockito.exceptions.verification.NeverWantedButInvoked
import org.mockito.exceptions.verification.WantedButNotInvoked
import java.time.LocalDateTime
import java.util.*
import kotlin.reflect.jvm.internal.impl.util.CheckResult

val timeout = 10 * 1000L
class UnexFlowTest {

    lateinit var flow: UnexFlow
    lateinit var submission: Submission
    lateinit var submissionRef: SubmissionReference
    lateinit var ownMessage: Message
    lateinit var reply: Message
    lateinit var comment: Comment
    lateinit var commentReference: CommentReference
    lateinit var conversationLinkage: ConversationLinkage
    lateinit var observationLinkage: ObservationLinkage
    lateinit var stub: IFlowStub<SubmissionReference, UnexFlow>
    lateinit var calledSubscriptions: MutableSet<Subscription<*>>
    lateinit var monitor: IDBCheck
    lateinit var monitorBuilder: IDBCheckBuilder
    lateinit var conversation: DefferedConversation
    lateinit var delayedDeleteFactory: DelayedDeleteFactory
    lateinit var manuallyFetchedEvent: ManuallyFetchedEvent

    private val sentMessageEvent = object : SentMessageEvent {
        override fun start(): Boolean {
            TODO("Not yet implemented")
        }
    }
    private val incomingMessagesEvent = object : IncomingMessagesEvent {
        override fun start(): Boolean {
            TODO("Not yet implemented")
        }
    }



    val configValues = Source.from.map.flat(mapOf(
            "reddit.messages.sent.timeSaved" to "10000",
            "reddit.messages.unread.maxAge" to "10000",
            "reddit.scoring.timeUntilRemoval" to "1000",
            "reddit.messages.sent.maxTimeDistance" to "300000",
            "reddit.messages.sent.maxWaitForCompletion" to "10000"
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
    lateinit var waitForRemoval: CompletableDeferred<Unit>
    val subscribeCalls = mutableListOf<Triple<UnexFlow, suspend (Any) -> Unit, EventType<*>>>()
    val unsubscribeCalls = mutableListOf<Pair<UnexFlow, EventType<*>>>()

    val deleteStarted = CompletableDeferred<Unit>()

    var foundAuthor: String? = null
    var foundSubmissionURL: String? = null

    var commentText: String? = null



    @BeforeEach
    fun setup(){
        monitor = spy(object : IDBCheck{
            override suspend fun saveToDB(fullComments: FullComments) { }
            override suspend fun start() { } })

        monitorBuilder = mock {
            on {build(any())}.doReturn(monitor)
            on {setBotComment(anyOrNull())}.doReturn(this.mock)
            on { setCommentEvent(any())}.doReturn(this.mock)
        }
        conversationLinkage = spy(DummyLinkage())
        observationLinkage = conversationLinkage as DummyLinkage
        var submissionRemoved = false
        waitForRemoval = CompletableDeferred()
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
            on { remove() }.then { submissionRemoved = true; waitForRemoval.complete(Unit); Unit }
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
                            scope: CoroutineScope,
                            skip: Long
                    ) = createDeletion(config[RedditSpec.scoring.timeUntilRemoval], config[RedditSpec.messages.sent.maxTimeDistance] - config[RedditSpec.scoring.timeUntilRemoval], skip)

        })

        manuallyFetchedEvent = mock()

        calledSubscriptions = mutableSetOf()
        stub = spy(object :IFlowStub<SubmissionReference, UnexFlow> by FlowStub(
                submissionRef,
                {flow: UnexFlow, fn, type -> subscribeCalls.add(Triple(flow, fn, type))},
                {flow, type -> unsubscribeCalls.add(flow to type)},
                CoroutineScope(Dispatchers.Default)){
            override suspend fun <R : Any, T> withSubscription(subscription: Subscription<R>, block: suspend CoroutineScope.() -> T): T {
                calledSubscriptions.add(subscription)
                return CoroutineScope(Dispatchers.Default).block()
            }

            override suspend fun <T> withSubscriptions(subscriptions: Collection<Subscription<*>>, block: suspend CoroutineScope.() -> T): T {
                calledSubscriptions.addAll(subscriptions)
                return CoroutineScope(Dispatchers.Default).block()
            }
        })
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
                manuallyFetchedEvent,
                conversationLinkage,
                observationLinkage,
                monitorBuilder,
                conversation,
                delayedDeleteFactory)

        stub.setOuter(flow)
    }

    private fun testFlowOutput() = assertAll(
            { assert(this.ownMessage.fullName == conversation.ownMessage) },
            { assert(this.reply == conversation.reply) },
            { assert(this.comment == flow.comment) },
            { assert(this.commentText == reply.body)},
            { assert(this.submission.isRemoved.not())},
            { verify(commentReference).distinguish(DistinguishedStatus.MODERATOR, true) },
            { assert(calledSubscriptions.any { it.type  is IncomingMessagesEvent })},
            { verify(conversationLinkage, times(1)).saveCommentMessage(submissionID, reply, comment)},
            { runBlocking {  verify(monitor).start()} })

    private fun testFlowStart() = assertAll(
            { assert(this.author == foundAuthor) { "Recipient $foundAuthor does not match author $author" } },
            { assert(this.submissionURL == foundSubmissionURL) { "SubmissionURL $foundSubmissionURL does not match ${this.submissionURL}" } },
            { verify(observationLinkage, times(1)).insertSubmission(submission)}
    )
    @Test
    fun `flow gets messages in correct order`(){
        runBlocking {
            flow.startInScope(flow::start)
            conversation.start(ownMessage)
            conversation.reply(reply)
            assert(select<Boolean> {
                flow.incompletableDefferedComment.onAwait { true }
                onTimeout(timeout) {false}
            })
            expectResult(defferedResult)
        }
        verify(submissionRef, never()).approve()
        testFlowOutput()
        testFlowStart()
        assert(calledSubscriptions.any { it.type  is SentMessageEvent })
    }


    @Test
    fun `flow gets Messages in wrong order`(){
        runBlocking {
            flow.startInScope(flow::start)
            conversation.reply(reply)
            delay(10L)
            conversation.start(ownMessage)
            assert(select<Boolean> {
                flow.incompletableDefferedComment.onAwait { true }
                onTimeout(timeout) {false}
            })
            expectResult(defferedResult)
        }
        verify(submissionRef, never()).approve()
        testFlowOutput()
        testFlowStart()
        assert(calledSubscriptions.any { it.type  is SentMessageEvent })

    }

    @Test
    fun `no Answer`() {
        doReturn(createDeletion(1, 10)).whenever(delayedDeleteFactory).create(any(), any(), any())
        doReturn(CheckSelectResult(DelayedDelete.DeleteResult.WasDeleted(), true.toBooleable(), null))
                .whenever(observationLinkage).createCheckSelectValues(
                        any(),
                        anyOrNull(),
                        anyOrNull(),
                        any(),
                        any<(JsonObject) -> Booleable>()
                )

        
        runBlocking {
            flow.startInScope(flow::start)
            conversation.start(ownMessage)
            expectResult(defferedResult)
        }
        assert(result is NoAnswerReceived)
        verify(submissionRef).remove()
    }

    @Test
    fun `late Answer`() {
        doReturn(createDeletion(1, 10000)).whenever(delayedDeleteFactory).create(any(), any(), any())
        doReturn(CheckSelectResult(DelayedDelete.DeleteResult.WasDeleted(), true.toBooleable(), null))
                .whenever(observationLinkage).createCheckSelectValues(
                        any(),
                        anyOrNull(),
                        anyOrNull(),
                        any(),
                        any<(JsonObject) -> Booleable>()
                )

        runBlocking {
            flow.startInScope(flow::start)
            conversation.start(ownMessage)
            delay(50L)
            conversation.reply(reply)
            expectResult(defferedResult)
        }

        verify(submissionRef).remove()
        testFlowOutput()
        testFlowStart()
        assert(calledSubscriptions.any { it.type  is SentMessageEvent })
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
        doReturn(0).whenever(observationLinkage).insertSubmission(any())
        val result = runBlocking { runForResult() }

        assertAll(
                { assert(result is SubmissionAlreadyPresent) },
                { runBlocking { verify(stub, never()).subscribe(any(), argThat<MessageEvent> { this is IncomingMessagesEvent }) }},
                { runBlocking { verify(stub, never()).subscribe(any(), argThat<MessageEvent> { this is SentMessageEvent }) } })

    }

    @Test
    fun `No Removal upon approval`(){
        doReturn(createDeletion(1, 10)).whenever(delayedDeleteFactory).create(any(), any(), any())
        doReturn(CheckSelectResult(DelayedDelete.DeleteResult.NotDeleted(), true.toBooleable(), null))
                .whenever(observationLinkage).createCheckSelectValues(
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

    @Test
    fun `Relaunch with answer after no skip`(){
        doReturn(Date()).whenever(submission).created
        val messageComposer = spy(
                object : MessageComposer {
                    override fun invoke( recipient: String, submissionURL: String) {
                        foundAuthor = recipient
                        foundSubmissionURL = submissionURL
                    }
                })
        flow = UnexFlow(
                stub,
                Callback { defferedResult.complete(it) },
                messageComposer,
                object : Replyer {
                    override fun invoke(submission: Submission, s: String): Pair<Comment, CommentReference> {
                        commentText = s
                        return comment to commentReference
                    }
                },
                sentMessageEvent,
                incomingMessagesEvent,
                manuallyFetchedEvent,
                conversationLinkage,
                observationLinkage,
                monitorBuilder,
                conversation,
                delayedDeleteFactory,
                ownMessageID
        )

        runBlocking {
            flow.startInScope(flow::relaunch)
            conversation.reply(reply)
            assert(select<Boolean> {
                flow.incompletableDefferedComment.onAwait { true }
                onTimeout(timeout) {false}
            })
            expectResult(defferedResult)
        }
        verify(submissionRef, never()).approve()
        verify(messageComposer, never()).invoke(any(), any())
        verify(submissionRef, never()).remove()
        observationLinkage.insertSubmission(submission)
        testFlowOutput()
    }

    @Nested
    @DisplayName("test relaunch with answer skip to deletion")
    inner class RepeatedTestSkipToDeletion{
        @ParameterizedTest
        @ArgumentsSource(RepeatedTest::class)
        fun `test relaunch with answer skip to deletion`(id: Int){
            doReturn(Date(System.currentTimeMillis() - (config[RedditSpec.scoring.timeUntilRemoval] * 2))).whenever(submission).created
            val messageComposer = spy(
                    object : MessageComposer {
                        override fun invoke( recipient: String, submissionURL: String) {
                            foundAuthor = recipient
                            foundSubmissionURL = submissionURL
                        }
                    })
            flow = UnexFlow(
                    stub,
                    Callback { defferedResult.complete(it) },
                    messageComposer,
                    object : Replyer {
                        override fun invoke(submission: Submission, s: String): Pair<Comment, CommentReference> {
                            commentText = s
                            return comment to commentReference
                        }
                    },
                    sentMessageEvent,
                    incomingMessagesEvent,
                    manuallyFetchedEvent,
                    conversationLinkage,
                    observationLinkage,
                    monitorBuilder,
                    conversation,
                    delayedDeleteFactory,
                    ownMessageID
            )


            runBlocking {
                flow.startInScope(flow::relaunch)
                assert(select {
                    waitForRemoval.onAwait { true }
                    onTimeout(timeout) { false }
                })
                conversation.reply(reply)
                assert(select {
                    flow.incompletableDefferedComment.onAwait { true }
                    onTimeout(timeout) {false}
                })
                expectResult(defferedResult)
            }

            try {
                verify(submissionRef, never()).remove()
                verify(submissionRef, never()).approve()
            } catch (e: NeverWantedButInvoked){
                try {
                    verify(submissionRef).remove()
                    verify(submissionRef).approve()
                } catch (c: WantedButNotInvoked){
                    fail { "Submission was removed without reapproval" }
                }
            }
            verify(messageComposer, never()).invoke(any(), any())
            observationLinkage.insertSubmission(submission)
            testFlowOutput()
        }
    }

    @Test
    fun `test relaunch with late answer skip to deletion`(){
        doReturn(100000).`when`(config)[RedditSpec.scoring.timeUntilRemoval]
        doReturn(Date(System.currentTimeMillis() - 999999)).whenever(submission).created
        val messageComposer = spy(
                object : MessageComposer {
                    override fun invoke( recipient: String, submissionURL: String) {
                        foundAuthor = recipient
                        foundSubmissionURL = submissionURL
                    }
                })
        flow = UnexFlow(
                stub,
                Callback { defferedResult.complete(it) },
                messageComposer,
                object : Replyer {
                    override fun invoke(submission: Submission, s: String): Pair<Comment, CommentReference> {
                        commentText = s
                        return comment to commentReference
                    }
                },
                sentMessageEvent,
                incomingMessagesEvent,
                manuallyFetchedEvent,
                conversationLinkage,
                observationLinkage,
                monitorBuilder,
                conversation,
                delayedDeleteFactory,
                ownMessageID
        )


        runBlocking {
            flow.startInScope(flow::relaunch)
            delay(500L)
            conversation.reply(reply)
            assert(select<Boolean> {
                flow.incompletableDefferedComment.onAwait { true }
                onTimeout(timeout) {false}
            })
            expectResult(defferedResult)
        }
        verify(submissionRef).approve()
        verify(submissionRef).remove()
        verify(messageComposer, never()).invoke(any(), any())
        observationLinkage.insertSubmission(submission)
        testFlowOutput()
    }



    suspend fun runForResult(fn: suspend () -> Unit = {}): FlowResult<UnexFlow>{
        flow.startInScope(flow::start)
        fn()
        expectResult(defferedResult)
        return result!!
    }

    fun createDeletion(toDeletion: Long, saved: Long, skip: Long = 0): DelayedDelete{
        val original = RedditDelayedDelete(
                toDeletion,
                saved - toDeletion,
                object : Unignorer { override fun invoke(p1: PublicContributionReference) { } },
                DelayedDelete.approvedCheck(observationLinkage),
                submissionRef,
                CoroutineScope(Dispatchers.Default),
                skip
        )
        return spy(object : DelayedDelete by original{
            override fun start() {
                deleteStarted.complete(Unit)
                original.start()
            }
        })
    }
}


class RepeatedTest : InlineArgumentProvider( {
    List(50) { Arguments.of(it) }.stream()
})

suspend fun expectResult(def: Deferred<*>) = assert(select {
    def.onAwait { true }
    //onTimeout(timeout) { false }
})

