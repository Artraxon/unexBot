package de.rtrx.a

import de.rtrx.a.jrawExtension.subscribe
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select
import mu.KotlinLogging
import net.dean.jraw.models.Message
import net.dean.jraw.references.SubmissionReference
import java.util.concurrent.ConcurrentHashMap


@ExperimentalCoroutinesApi
class MessageMonitor {

    private val logger = KotlinLogging.logger {  }

    private val savingMessagesScope = CoroutineScope(Dispatchers.Default)

    val sentMessages = ConcurrentHashMap<String, CompletableDeferred<Message>>()

    init {
        val retrievalPair = reddit.me().inbox()
            .iterate("sent")
            .limit(config[RedditSpec.messages.sent.limit])
            .build()
            .subscribe(config[RedditSpec.messages.sent.waitIntervall])

        savingMessagesScope.saveSentMessages(retrievalPair.second)
        jobs.add(retrievalPair.first)
    }


    private val unfilteredInboxScope = CoroutineScope(Dispatchers.Default)
    private val unfilteredInbox = unfilteredInboxScope.retrieveNewMessages(
        config[RedditSpec.messages.unread.limit],
        config[RedditSpec.messages.unread.waitIntervall]
    )


    private val filteredInboxScope = CoroutineScope(Dispatchers.Default)
    val filteredInbox = filteredInboxScope.filterMessages(unfilteredInbox)


    @kotlinx.coroutines.ExperimentalCoroutinesApi
    private fun CoroutineScope.saveSentMessages(
        newSentMessages: ReceiveChannel<Message>
    ) {
        val job = launch {
            println("starting to save sent messages")
            val uncacheScope = CoroutineScope(Dispatchers.Default)
            for (message in newSentMessages) {
                sentMessages.access(message.fullName).complete(message)
                uncacheScope.launch {
                    delay(config[RedditSpec.messages.sent.timeSaved])
                    sentMessages.remove(message.fullName)
                }
            }
        }

        jobs.add(job)
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    private fun CoroutineScope.filterMessages(
        messageChannel: ReceiveChannel<Message>
    ): ReceiveChannel<Pair<SubmissionReference, Message>> {
        println("starting to filter messages")
        val channel = Channel<Pair<SubmissionReference, Message>>(Channel.UNLIMITED)

        val usedUsername = config[RedditSpec.credentials.username]

        val job = launch {
            for (message in messageChannel) {
                try {
                    logger.trace { "Received new message with id: ${message.id}" }
                    //Dropping messages if they are too old, this prevents an expensive db lookup from happening
                    val parentFullname = message.firstMessage
                    if (message.author != usedUsername && parentFullname != null) {
                        //We'll do the rest async since there will definitly be some network or db stuff
                        launch {
                            val parent = waitForParent(parentFullname)
                            if (parent != null && parent.author == usedUsername) {
                                val submission = extractSubmission(parent)
                                if (submission != null) {
                                    channel.send(submission to message)
                                }
                            }

                        }
                    }
                } catch (e: Throwable){
                    logger.error { "An exception was raised while filtering messages:\n${e.message}" }
                }
            }
        }

        jobs.add(job)
        return channel
    }

    fun extractSubmission(message: Message): SubmissionReference?{
        val startIndex = message.body.indexOf("(")
        val endIndex = message.body.indexOf(")")

        //Check whether the parent message was sent by us and if a link exists.
        if (startIndex >= 0 && endIndex >= 0) {
            val submission: SubmissionReference? = run {
                try {
                    reddit.submission(
                        message.body
                            //Extract the Link from the parent message by cropping around the first parenthesis
                            .slice((startIndex + 1) until endIndex)
                            //Extract the ID of the submission
                            .split("comments/")[1].split("/")[0]
                    )
                } catch (e: Throwable) {
                    logger.error { "An exception was raised while fetching submission:\n${e.message}" }
                    null
                }
            }
            return submission
        }

        return null
    }


    suspend fun CoroutineScope.waitForParent(parentFullname: String): Message? {
        return try {
            val message: Message? = select {
                sentMessages.access(parentFullname).onAwait { it }
                onTimeout(config[RedditSpec.messages.unread.maxAge]) {null}
            }
            sentMessages.remove(parentFullname)
            message
        } catch (e: Throwable){
            logger.error { "An exception was raised while waiting for the parent message to arrive:\n${e.message}" }
            null
        }
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    fun CoroutineScope.retrieveNewMessages(unreadMessageLimit: Int, waitIntervall: Long): ReceiveChannel<Message> {
        val channel = Channel<Message>(Channel.UNLIMITED)
        jobs.add(launch {
            println("starting to retrieve new incoming messages")
            while (isActive) {
                try {
                    val paginator = reddit.me().inbox().iterate("unread").limit(unreadMessageLimit).build()
                    var currentPage = paginator.next()
                    while (currentPage.isNotEmpty()) {
                        currentPage.forEach {
                            logger.trace { "Received new Message with id ${it.id}" }
                            reddit.me().inbox().markRead(true, it.fullName)
                            channel.send(it!!)
                        }
                        currentPage = paginator.next()
                    }
                } catch (e: Throwable){
                    logger.error { "Exception Thrown when trying to fetch messages:\n${e.message}" }
                }
                delay(waitIntervall)

            }
        })
        return channel
    }

}

