package de.rtrx.a.flow

import com.nhaarman.mockitokotlin2.*
import de.rtrx.a.RedditSpec
import de.rtrx.a.database.DummyLinkage
import de.rtrx.a.database.Linkage
import de.rtrx.a.flow.events.*
import de.rtrx.a.flow.events.comments.CommentsFetcherFactory
import de.rtrx.a.flow.events.comments.FullComments
import de.rtrx.a.flow.events.comments.ManuallyFetchedEvent
import de.rtrx.a.unex.RedditUnexFlowFactory
import de.rtrx.a.unex.UnexFlow
import de.rtrx.a.unex.UnexFlowDispatcher
import de.rtrx.a.unex.UnexFlowFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select
import net.dean.jraw.RedditClient
import net.dean.jraw.models.Comment
import net.dean.jraw.models.Message
import net.dean.jraw.models.Submission
import net.dean.jraw.references.CommentReference
import net.dean.jraw.references.SubmissionReference
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.reflect.KClass
import kotlin.reflect.jvm.isAccessible

class UnexFlowDispatcherTest {

    val timeout = 10000L
    lateinit var unexFlowTest: UnexFlowTest
    lateinit var dispatcherStub: IFlowDispatcherStub<UnexFlow, UnexFlowFactory>
    lateinit var unexFlowDispatcher: UnexFlowDispatcher

    lateinit var starterEventChannel: Channel<SubmissionReference>
    lateinit var flowFactory: UnexFlowFactory
    lateinit var producedFlow: CompletableDeferred<UnexFlow>
    lateinit var launcherScope: CoroutineScope
    lateinit var eventFactories: EventFactories
    lateinit var commentsFetchedWrap: EventWrap<FullComments, ManuallyFetchedEvent, SubmissionReference, CommentsFetcherFactory>
    lateinit var startAction: suspend UnexFlow.() -> Unit

    lateinit var linkage: Linkage
    lateinit var fullCommentsMultiplexerWrap: MultiplexerWrap<FullComments>
    lateinit var redditClient: RedditClient

    lateinit var incomingMessageMultiplexerWrap: MultiplexerWrap<Message>
    lateinit var sentMessageMultiplexerWrap: MultiplexerWrap<Message>

    lateinit var incomingMessageWrap: EventWrap<Message, IncomingMessagesEvent, String, IncomingMessageFactory>
    lateinit var sentMessageWrap: EventWrap<Message, SentMessageEvent, String, SentMessageFactory>
    lateinit var isolationStrategy: IsolationStrategy
    lateinit var markAsReadFlow: MarkAsReadFlow
    val runningEvents get() = accessPrivateProperty<
            FlowDispatcherStub<UnexFlow, SubmissionReference, UnexFlowFactory>,
            ConcurrentHashMap<KClass<*>, ConcurrentHashMap<Any, Pair<EventType<*>, ReceiveChannel<*>>>>
            >(
            dispatcherStub as FlowDispatcherStub<UnexFlow, SubmissionReference, UnexFlowFactory>,
            "runningEvents"
    )
    val multiplexerList get() = accessPrivateProperty<
            FlowDispatcherStub<UnexFlow, SubmissionReference, UnexFlowFactory>,
            MutableMap<EventType<*>, EventMultiplexer<*>>
            >(
            dispatcherStub as FlowDispatcherStub<UnexFlow, SubmissionReference, UnexFlowFactory>,
            "multiplexers"
    )


    @BeforeEach
    fun setup() {
        unexFlowTest = UnexFlowTest()
        doReturn(3*1000L).whenever(unexFlowTest.config)[RedditSpec.scoring.timeUntilRemoval]

        unexFlowTest.setup()


        linkage = spy(DummyLinkage())

        commentsFetchedWrap = EventWrap.wrap {  }
        eventFactories = mapOf(ManuallyFetchedEvent::class to (commentsFetchedWrap.eventTypeFactory to SubmissionReference::class)) as EventFactories

        incomingMessageWrap = EventWrap.wrapMessage()
        sentMessageWrap = EventWrap.wrapMessage()

        fullCommentsMultiplexerWrap = MultiplexerWrap(commentsFetchedWrap.channel)
        incomingMessageMultiplexerWrap = MultiplexerWrap(incomingMessageWrap.channel)
        sentMessageMultiplexerWrap = MultiplexerWrap(sentMessageWrap.channel)

        redditClient = mock {
            on { submission(any()) }.doReturn(unexFlowTest.submissionRef)
        }

        producedFlow = CompletableDeferred()

        starterEventChannel = Channel()
        flowFactory = RedditUnexFlowFactory(
                unexFlowTest.config[RedditSpec.scoring.timeUntilRemoval],
                unexFlowTest.config[RedditSpec.messages.sent.maxTimeDistance] - unexFlowTest.config[RedditSpec.scoring.timeUntilRemoval],
                object : MessageComposer {
                    override fun invoke(recipient: String, submissionURL: String) {
                        unexFlowTest.foundAuthor = recipient
                        unexFlowTest.foundSubmissionURL = submissionURL
                    }
                },
                object : Replyer {
                    override fun invoke(submission: Submission, s: String): Pair<Comment, CommentReference> {
                        unexFlowTest.commentText = s
                        return unexFlowTest.comment to unexFlowTest.commentReference
                    }
                },
                { unexFlowTest.monitorBuilder },
                unexFlowTest.conversationLinkage,
                unexFlowTest.observationLinkage,
                linkage,
                { unexFlowTest.conversation },
                unexFlowTest.delayedDeleteFactory,
                { fullCommentsMultiplexerWrap.multiplexerBuilder },
                unexFlowTest.config,
                redditClient,
        )

        launcherScope = CoroutineScope(Dispatchers.Default)
        startAction = { start() }
        isolationStrategy = SingleFlowIsolation()
        markAsReadFlow = mock { }

        dispatcherStub = FlowDispatcherStub(starterEventChannel, flowFactory, launcherScope, eventFactories, startAction)


        unexFlowDispatcher = UnexFlowDispatcher(
                dispatcherStub,
                incomingMessageMultiplexerWrap.multiplexerBuilder,
                sentMessageMultiplexerWrap.multiplexerBuilder,
                incomingMessageWrap.eventTypeFactory,
                sentMessageWrap.eventTypeFactory,
                isolationStrategy,
                markAsReadFlow,
                mock {  },
                false
        )
    }

    @Test
    fun `test References Removal`() {
        unexFlowDispatcher.start()
        val createdFlows = unexFlowDispatcher.getCreatedFlows()
        runBlocking {
            val fullCommentsCalled = CompletableDeferred<Unit>()
            doAnswer { fullCommentsCalled.complete(Unit); Unit }
                    .whenever(unexFlowTest.monitor).saveToDB(any())

            starterEventChannel.send(unexFlowTest.submissionRef)
            val flow: UnexFlow = select {
                createdFlows.onReceive { it }
                onTimeout(timeout) { null }
            } ?: fail("No flow created within timeout")

            flow.addCallback { unexFlowTest.defferedResult.complete(it) }

            withTimeout(timeout*10, { unexFlowTest.deleteStarted.await() })

            verify(incomingMessageMultiplexerWrap.multiplexerBuilder).build()
            verify(sentMessageMultiplexerWrap.multiplexerBuilder).build()
            verify(incomingMessageMultiplexerWrap.multiplexerBuilder).setOrigin(eq(incomingMessageWrap.channel))
            verify(sentMessageMultiplexerWrap.multiplexerBuilder).setOrigin(eq(sentMessageWrap.channel))

            //verify(dispatcherStub).registerMultiplexer(eq(incomingMessageWrap.event), eq(incomingMessageMultiplexerWrap.multiplexer))
            //verify(dispatcherStub).registerMultiplexer(eq(sentMessageWrap.event), eq(sentMessageMultiplexerWrap.multiplexer))

            sentMessageWrap.channel.send(unexFlowTest.ownMessage)
            incomingMessageWrap.channel.send(unexFlowTest.reply)

            expectResult(unexFlowTest.defferedResult)
            assert(runningEvents[ManuallyFetchedEvent::class]!!.size == 0)
            assert(multiplexerList.size == 2)
            assert(multiplexerList[incomingMessageWrap.event]!! === incomingMessageMultiplexerWrap.multiplexer)
            assert(multiplexerList[sentMessageWrap.event]!! == sentMessageMultiplexerWrap.multiplexer)
            assert(multiplexerList[commentsFetchedWrap.event] == null)
            assert(incomingMessageMultiplexerWrap.accessMultiplexerPrivateProperty<MutableMap<Flow, ConcurrentLinkedQueue<suspend (Message) -> Unit>>>("listeners").size == 1)
            assert(sentMessageMultiplexerWrap.accessMultiplexerPrivateProperty<MutableMap<Flow, ConcurrentLinkedQueue<suspend (Message) -> Unit>>>("listeners").size == 1)


        }
    }

    class MultiplexerWrap<R : Any>(channel: ReceiveChannel<R>) {
        val multiplexerBuilder: EventMultiplexerBuilder<R, *, ReceiveChannel<R>>
        lateinit var multiplexer: EventMultiplexer<R>

        init {
            val realBuilder = SimpleMultiplexer.SimpleMultiplexerBuilder<R>()
            realBuilder.setOrigin(channel)
            multiplexerBuilder = mock {
                on { setOrigin(any()) }.doReturn(mock)
                on { setIsolationStrategy(any()) }.doReturn(mock)
                //Partial Mocking won't work since it creates copies and messes up the interaction with the channel from the outside
                on { build() }.then {
                    if(!this@MultiplexerWrap::multiplexer.isInitialized) {
                        multiplexer = realBuilder.build()
                    }
                    return@then multiplexer
                }

            }
        }
        inline fun <reified S> accessMultiplexerPrivateProperty(name: String): S{
            return SimpleMultiplexer::class.members.find { it.name == "listeners" }!!.apply { isAccessible = true }.call(multiplexer) as S
        }
    }


    interface EventWrap<R : Any, E : EventType<R>, I : Any, F : EventTypeFactory<E, R, I>> {
        var eventTypeFactory: F
        var channel: Channel<R>
        var event: E


        companion object {
            inline fun <
                    reified R : Any,
                    reified E : EventType<R>,
                    reified I : Any,
                    reified F : EventTypeFactory<E, R, I>
                    > wrap(
                    crossinline stubbing: KStubbing<E>.() -> Unit
            ): EventWrap<R, E, I, F> {
                return object : EventWrap<R, E, I, F> {
                    override var eventTypeFactory: F
                    override var channel: Channel<R>
                    override var event: E

                    init {
                        channel = spy(Channel(Channel.RENDEZVOUS))
                        event = mock {
                            stubbing()
                        }
                        eventTypeFactory = mock {
                            on { create(any()) }.doReturn(event to channel)
                        }
                    }

                }
            }

            inline fun <reified E : MessageEvent, reified F : MessageEventFactory<E>> wrapMessage(): EventWrap<Message, E, String, F> {
                return EventWrap.wrap {
                    var started: Boolean = false
                    on { start() }.then {
                        return@then if (!started) {
                            started = true; true
                        } else false
                    }

                }
            }
        }
    }
}

