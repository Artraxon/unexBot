package de.rtrx.a.monitor

import com.uchuhimo.konf.Config
import de.rtrx.a.RedditSpec
import de.rtrx.a.database.Linkage
import kotlinx.coroutines.delay
import mu.KotlinLogging
import net.dean.jraw.RedditClient
import net.dean.jraw.models.Comment
import net.dean.jraw.models.CommentSort
import net.dean.jraw.references.CommentsRequest
import net.dean.jraw.references.SubmissionReference
import net.dean.jraw.tree.ReplyCommentNode
import java.util.*
import javax.inject.Inject

private val logger = KotlinLogging.logger {  }


class DBCheck(
        submission: SubmissionReference,
        botComment: Comment?,
        private val config: Config,
        private val linkage: Linkage,
        private val redditClient: RedditClient
) : Check(submission, botComment) {

    override suspend fun start() {
        //TODO make this an external class and more configurable via the Config
        for (i in 1..config[RedditSpec.checks.DB.forTimes]) {
            try {
                delay(config[RedditSpec.checks.DB.every])

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
                val comment_hierarchy = mutableMapOf<Comment, Comment>()
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
                            comment_hierarchy.put(comment, contribution.parent.subject as Comment)
                        }
                    }
                }

                //Create the check on the DB
                logger.trace { "Creating ${checksPerformed + 1} Check for Submission with id ${submission.id}" }
                val (_, createdComments) = linkage.createCheck(submission.fullName, botComment, sticky, comments.toTypedArray())

                //Add the parents to the db for comments that were newly created
                logger.trace { "Added the following comments to the db:\n" + createdComments.map { it.fullName }.joinToString(", ") }
                createdComments.forEach {
                    val parent = comment_hierarchy[it]
                    if(parent != null) linkage.add_parent(it, parent)
                }

                increaseCheckCounter()
            } catch (e: Throwable) {
                logger.error {
                    "An Exception was raised while monitoring a submission with id ${submission.id}:\n" +
                            e.message.toString()
                }
            }
        }
    }
}

interface IDBCheckBuilder: MonitorBuilder<DBCheck>
class DBCheckBuilder @Inject constructor(
        private val config: Config,
        private val linkage: Linkage,
        private val redditClient: RedditClient
): MonitorBuilder<DBCheck> {
    var botComment: Comment? = null
    override fun build(submission: SubmissionReference) = DBCheck(submission, botComment, config, linkage, redditClient)

    override fun setBotComment(botComment: Comment?): MonitorBuilder<DBCheck> {
        this.botComment = botComment
        return this
    }

}
class DBCheckFactory @Inject constructor(
        private val config: Config,
        private val linkage: Linkage,
        private val redditClient: RedditClient
): MonitorFactory<DBCheck, DBCheckBuilder>{
    override fun get(): DBCheckBuilder {
        return DBCheckBuilder(config, linkage, redditClient)
    }

}

