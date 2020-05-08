package de.rtrx.a.flow.events.comments

import com.uchuhimo.konf.Config
import de.rtrx.a.RedditSpec
import de.rtrx.a.flow.events.EventType
import de.rtrx.a.flow.events.EventTypeFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import net.dean.jraw.RedditClient
import net.dean.jraw.models.Comment
import net.dean.jraw.models.CommentSort
import net.dean.jraw.references.CommentsRequest
import net.dean.jraw.references.SubmissionReference
import net.dean.jraw.tree.ReplyCommentNode
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject

data class FullComments(public val comments: List<Comment>, public val commentsHierarchy: Map<String, String>, public val sticky: Comment?)

interface CommentsFetchedEvent: EventType<FullComments>
interface ManuallyFetchedEvent: CommentsFetchedEvent {
    suspend fun fetchComments()
}


interface CommentsFetcherFactory: EventTypeFactory<ManuallyFetchedEvent, FullComments, SubmissionReference>

class RedditCommentsFetchedFactory @Inject constructor(
        private val redditClient: RedditClient,
        private val config: Config
) : CommentsFetcherFactory {

    override fun create(id: SubmissionReference): Pair<ManuallyFetchedEvent, ReceiveChannel<FullComments>> {
        val event = RedditCommentsFetchedEvent(id, redditClient, config)
        val out = event.channel
        return event to out
    }

    private class RedditCommentsFetchedEvent(
            private val submission: SubmissionReference,
            private val redditClient: RedditClient,
            private val config: Config
    ): ManuallyFetchedEvent {
        private val context = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
                .plus(CoroutineName("CommentFetcher:${submission.fullName}"))

        var channel = Channel<FullComments>()

        override suspend fun fetchComments() {
            CoroutineScope(context).launch {
                //Forms the Request for looking up the comments
                val commentsTree = submission.comments(CommentsRequest(
                        depth = config[RedditSpec.checks.DB.depth],
                        sort = CommentSort.TOP
                )).apply {
                    var size = totalSize()
                    //Pulls comments until the value specified in the configuration is reached or no more comments can be found
                    var newComments: List<ReplyCommentNode>? = null
                    while (size < config[RedditSpec.checks.DB.comments_amount] && newComments?.isNotEmpty() ?: true) {
                        delay(config[RedditSpec.checks.DB.commentWaitIntervall])
                        newComments = replaceMore(redditClient)
                        size += newComments.size
                    }
                }.walkTree().toCollection(LinkedList())

                var sticky: Comment? = null
                val commentHierarchy = mutableMapOf<Comment, Comment>()
                val comments = mutableListOf<Comment>()

                while (commentsTree.isNotEmpty()){
                    val contribution = commentsTree.poll()
                    if(contribution.subject is Comment) {
                        val comment = contribution.subject as Comment
                        if (comment.isStickied) {
                            sticky = comment
                            //Comments to the stickied comment are not loaded by default, so we have to
                            //load them explicitly and add them to the list
                            contribution.loadFully(redditClient)
                            commentsTree.addAll(contribution.replies.flatMap { it.walkTree().toList() })
                        }
                        comments.add(comment)
                        if (contribution.depth > 1){
                            commentHierarchy.put(comment, contribution.parent.subject as Comment)
                        }
                    }
                }

                val full = FullComments(comments, commentHierarchy.mapKeys { it.key.id }.mapValues { it.value.id }, sticky)
                channel.send(full)
            }
        }

    }
}
