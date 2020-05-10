package de.rtrx.a.unex

import de.rtrx.a.flow.IFlowDispatcherStub
import de.rtrx.a.flow.IsolationStrategy
import de.rtrx.a.flow.events.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import net.dean.jraw.models.Message
import javax.inject.Inject


class UnexFlowDispatcher @Inject constructor(
        private val stub: IFlowDispatcherStub<UnexFlow, UnexFlowFactory>,
        incomingMessageMultiplexerBuilder: @JvmSuppressWildcards EventMultiplexerBuilder<Message, @JvmSuppressWildcards EventMultiplexer<Message>, @JvmSuppressWildcards ReceiveChannel<Message>>,
        sentMessageMultiplexerBuilder: @JvmSuppressWildcards EventMultiplexerBuilder<Message, @JvmSuppressWildcards EventMultiplexer<Message>, @JvmSuppressWildcards ReceiveChannel<Message>>,
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

