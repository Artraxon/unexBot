package de.rtrx.a.unex

import com.google.inject.name.Named
import de.rtrx.a.flow.Flow
import de.rtrx.a.flow.FlowDispatcherInterface
import de.rtrx.a.flow.IFlowDispatcherStub
import de.rtrx.a.flow.IsolationStrategy
import de.rtrx.a.flow.events.*
import de.rtrx.a.flow.events.comments.CommentsFetcherFactory
import de.rtrx.a.flow.events.comments.FullComments
import de.rtrx.a.flow.events.comments.ManuallyFetchedEvent
import de.rtrx.a.flow.events.comments.RedditCommentsFetchedFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import mu.KotlinLogging
import net.dean.jraw.models.Message
import net.dean.jraw.references.SubmissionReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Provider


class UnexFlowDispatcher @Inject constructor(
        private val stub: IFlowDispatcherStub<UnexFlow, UnexFlowFactory>,
        incomingMessageMultiplexerBuilder: @JvmSuppressWildcards EventMultiplexerBuilder<Message, @JvmSuppressWildcards EventMultiplexer<Message>, @JvmSuppressWildcards ReceiveChannel<Message>>,
        sentMessageMultiplexerBuilder: @JvmSuppressWildcards EventMultiplexerBuilder<Message, @JvmSuppressWildcards EventMultiplexer<Message>, @JvmSuppressWildcards ReceiveChannel<Message>>,
        private val commentsFetcherMultiplexerProvider: @JvmSuppressWildcards Provider<@JvmSuppressWildcards EventMultiplexerBuilder<FullComments, @JvmSuppressWildcards EventMultiplexer<FullComments>, @JvmSuppressWildcards ReceiveChannel<FullComments>>>,
        private val commentsFetcherIsolationStrategyProvider: Provider<IsolationStrategy>,
        incomingMessageFactory: IncomingMessageFactory,
        sentMessageFactory: SentMessageFactory,
        isolationStrategy: IsolationStrategy,
        markAsReadFlow: MarkAsReadFlow
) : IFlowDispatcherStub<UnexFlow, UnexFlowFactory> by stub{

    private val incomingMessageMultiplexer: EventMultiplexer<Message>
    private val sentMessageMultiplexer: EventMultiplexer<Message>

    private val incomingMessagesEvent: IncomingMessagesEvent
    private val sentMessageEvent: SentMessageEvent


    init {
        val (incomingEvent, incomingChannel) = incomingMessageFactory.create("")
        val (sentEvent, sentChannel) = sentMessageFactory.create("")
        this.incomingMessagesEvent = incomingEvent
        this.sentMessageEvent = sentEvent

        stub.flowFactory.setIncomingMessages(incomingMessagesEvent)
        stub.flowFactory.setSentMessages(sentMessageEvent)

        incomingMessageMultiplexer = incomingMessageMultiplexerBuilder
                .setOrigin(incomingChannel)
                .setIsolationStrategy(isolationStrategy)
                .build()
        sentMessageMultiplexer = sentMessageMultiplexerBuilder
                .setOrigin(sentChannel)
                .setIsolationStrategy(isolationStrategy)
                .build()

        runBlocking {
            stub.registerMultiplexer(incomingMessagesEvent, incomingMessageMultiplexer)
            stub.registerMultiplexer(sentMessageEvent, sentMessageMultiplexer)
        }
        incomingMessageMultiplexer.addListener(markAsReadFlow, markAsReadFlow::markAsRead)
        stub.start()
        KotlinLogging.logger { }.info("Started UnexFlow Dispatcher")
    }

}

