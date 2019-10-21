package de.rtrx.a

import de.rtrx.a.database.DB
import de.rtrx.a.jrawExtension.subscribe
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select
import net.dean.jraw.models.*
import net.dean.jraw.references.CommentsRequest
import net.dean.jraw.references.SubmissionReference
import java.util.concurrent.ConcurrentHashMap

@ExperimentalCoroutinesApi
class SubmissionMonitor(filteredInboxChannel: ReceiveChannel<Pair<SubmissionReference, Message>>) {
    /**
     * Uses the Fullname of the submission as the key for the deferred comment
     */
    val approvedPosts = ConcurrentHashMap<String, CompletableDeferred<Comment>>()



    val savingMessagesScopes = CoroutineScope(Dispatchers.Default)

    val submissions = reddit
        .subreddit(config[RedditSpec.subreddit])
        .posts()
        .sorting(SubredditSort.NEW)
        .limit(config[RedditSpec.submissions.limit])
        .build()
        .subscribe(config[RedditSpec.submissions.waitIntervall]).second.let {
        savingMessagesScopes.saveNewPosts(it)
    }


    private val sendMessagesScope = CoroutineScope(Dispatchers.Default)
    val sendMessages = sendMessagesScope.sendMessages(
        submissions,
        config[RedditSpec.messages.sent.subject],
        config[RedditSpec.messages.sent.body]
    )

    private val postDeletionsScope = CoroutineScope(Dispatchers.Default)
    val postDeletions = postDeletionsScope.deletePosts(
        sendMessages,
        config[RedditSpec.scoring.timeUntilRemoval]
    )

    private val postApprovalScope = CoroutineScope(Dispatchers.Default)
    val stickiedComments = postApprovalScope.approvePosts(
        filteredInboxChannel,
        config[RedditSpec.scoring.commentBody]
    )

    private val monitorScope = CoroutineScope(Dispatchers.Default)
    val monitoring = monitorScope.monitorSubmissions(stickiedComments)

    private fun CoroutineScope.saveNewPosts(newSubmissions: ReceiveChannel<Submission>): ReceiveChannel<Submission> {
        val channel = Channel<Submission>()
        val job = launch{
            for(submission in newSubmissions){
                try {
                    logger.debug { "Read submission with id ${submission.id}" }
                    if (DB.insertSubmission(submission) != 0) {
                        channel.send(submission)
                        logger.info { "New submission with id ${submission.id}" }
                    }
                } catch (e: Throwable){
                    logger.info { "An exception was raised while inserting an entry for a submission into the database:\n" +
                            e.message.toString()
                    }
                }
            }
        }
        jobs.add(job)
        return channel
    }

    fun CoroutineScope.monitorSubmissions(submissions: ReceiveChannel<Pair<SubmissionReference, Comment>>): Job {
        val job =  launch {
            for((submission, _) in submissions){
                launch {
                    try {
                        for (i in 1..config[RedditSpec.checks.forTimes]) {
                            delay(config[RedditSpec.checks.every])

                            val comments = submission.comments(CommentsRequest(depth = 11)).map { it.subject as Comment }
                            val sticky = comments.filter { it.isStickied }.getOrNull(0)
                            val top_comments = comments
                                    .filter { it.fullName != sticky?.fullName }
                                    .sortedByDescending { it.score }
                                    .take(10)
                                    .toTypedArray()

                            logger.trace { "Creating Check for Submission with id ${submission.id}" }
                            DB.createCheck(submission.fullName, sticky, top_comments)
                        }
                    } catch (e: Throwable){
                        logger.error { "An Exception was raised while monitoring a submission with id ${submission.id}:\n" +
                                e.message.toString()
                        }
                    }
                }
            }
        }
        jobs.add(job)
        return job;
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    fun CoroutineScope.sendMessages(
        incomingPosts: ReceiveChannel<Submission>,
        messageSubject: String,
        messageBody: String
    ): ReceiveChannel<Submission> {
        val channel = Channel<Submission>(capacity = Channel.UNLIMITED)
        val job = launch {
            println("starting to send messages")
            for (submission in incomingPosts) {
                try {
                    logger.trace { ("received new submission with title ${submission.title}") }
                    reddit.me().inbox().compose(
                            dest = submission.author,
                            subject = messageSubject,
                            body = messageBody
                                    .replace("%{Submission}", submission.permalink)
                                    .replace("%{HoursUntilDrop}", (config[RedditSpec.messages.sent.timeSaved] / (1000 * 60 * 60)).toString())
                                    .replace("%{subreddit}", config[RedditSpec.subreddit])
                                    .replace("%{MinutesUntilRemoval}", (config[RedditSpec.scoring.timeUntilRemoval] / (1000 * 60)).toString())
                    )
                    logger.trace { "Sending Message to ${submission.author} regarding post ${submission.id}" }
                    channel.send(submission)
                } catch (e: Throwable){
                    logger.error { "An exception was raised while sending a message regarding submission ${submission.id}:\n" +
                            e.message.toString()
                    }
                }
            }
        }
        jobs.add(job)
        return channel
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    fun CoroutineScope.approvePosts(
        messageChannel: ReceiveChannel<Pair<SubmissionReference, Message>>,
        commentBody: String
    ): ReceiveChannel<Pair<SubmissionReference, Comment>> {
        println("starting to approve posts")
        val channel = Channel<Pair<SubmissionReference, Comment>>()
        val job = launch {
            for ((submission, message) in messageChannel) {
                try {
                    logger.trace { "Creating comment for and approving post ${submission.id}" }
                    val comment = submission.reply(commentBody.replace("%{Reason}", message.body))
                    val commentRef = comment.toReference(reddit)
                    commentRef.distinguish(DistinguishedStatus.MODERATOR, true)

                    DB.commentMessage(submission.id, message, comment)

                    channel.send(submission to comment)
                    approvedPosts.get(submission.fullName)?.complete(comment) ?: run {
                        submission.approve()
                        unignoreReports(submission.fullName)
                    }
                } catch (e: Throwable){
                    logger.error { "An exception was raised while approving submission ${submission.id}:\n" +
                            e.message.toString()
                    }
                }
            }
        }
        jobs.add(job)
        return channel
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    fun CoroutineScope.deletePosts(
        submissionChannel: ReceiveChannel<Submission>,
        timeUntilSubRemoval: Long
    ): Job {
        val job = launch {
            println("starting to delete posts")
            while (isActive) {
                for (submission in submissionChannel) {
                    launch {
                        try {
                            val comment = select<Comment?> {
                                approvedPosts.access(submission.fullName).onAwait { it }
                                onTimeout(timeUntilSubRemoval) { null }
                            }
                            DB.createCheck(submission.fullName, null, emptyArray())
                            approvedPosts.remove(submission.fullName)

                            if (comment == null) {
                                submission.toReference(reddit).remove()
                                logger.info { ("Post ${submission.id} was deleted") }
                            }
                        } catch (e: Throwable) {
                            logger.error { "An exception was raised while deleting submission ${submission.id}:\n" +
                                    e.message.toString()
                            }
                        }
                    }

                }
            }
        }
        jobs.add(job)
        return job
    }
}