package de.rtrx.a.monitor

import com.uchuhimo.konf.Config
import de.rtrx.a.RedditSpec
import de.rtrx.a.database.Linkage
import de.rtrx.a.database.ObservationLinkage
import de.rtrx.a.flow.events.comments.FullComments
import de.rtrx.a.flow.events.comments.ManuallyFetchedEvent
import kotlinx.coroutines.delay
import mu.KotlinLogging
import net.dean.jraw.RedditClient
import net.dean.jraw.models.Comment
import net.dean.jraw.references.SubmissionReference
import javax.inject.Inject
import javax.inject.Provider

private val logger = KotlinLogging.logger {  }

interface IDBCheck: Monitor{
    suspend fun  saveToDB(fullComments: FullComments)
}

class DBCheck(
        submission: SubmissionReference,
        botComment: Comment?,
        private val config: Config,
        private val linkage: ObservationLinkage,
        private val redditClient: RedditClient,
        private val commentEvent: ManuallyFetchedEvent
) : Check(submission, botComment), IDBCheck {

    override suspend fun start() {
        for (i in 1..config[RedditSpec.checks.DB.forTimes]) {
            try {
                delay(config[RedditSpec.checks.DB.every])
                commentEvent.fetchComments()
                increaseCheckCounter()
            } catch (e: Throwable) {
                logger.error {
                    "An Exception was raised while monitoring a submission with id ${submission.id}:\n" +
                            e.message.toString()
                }
            }
        }
    }

    override suspend fun saveToDB(fullComments: FullComments){
        try {
            val (comments, hierarchy, sticky) = fullComments

            //Create the check on the DB
            logger.trace { "Creating ${checksPerformed + 1} Check for Submission with id ${submission.id}" }
            val (_, createdComments) = linkage.createCheck(submission.fullName, botComment, sticky, comments.toTypedArray())

            //Add the parents to the db for comments that were newly created
            logger.trace { "Added the following comments to the db:\n" + createdComments.map { it.fullName }.joinToString(", ") }
            createdComments.forEach {
                val parent = hierarchy[it.id]
                if(parent != null) linkage.add_parent(it.id, parent)
            }
        } catch (e: Throwable){
            logger.error {
                "An Exception was raised while saving comments from submission with id ${submission.id}:\n" +
                        e.message.toString()
            }
        }
    }
}

interface IDBCheckBuilder: MonitorBuilder<IDBCheck> {
    fun setCommentEvent(manuallyFetchedEvent: ManuallyFetchedEvent): IDBCheckBuilder
}
class DBCheckBuilder @Inject constructor(
        private val config: Config,
        private val linkage: ObservationLinkage,
        private val redditClient: RedditClient
): IDBCheckBuilder {
    private var botComment: Comment? = null
    lateinit var commentEvent: ManuallyFetchedEvent

    override fun setCommentEvent(manuallyFetchedEvent: ManuallyFetchedEvent): IDBCheckBuilder {
        this.commentEvent = manuallyFetchedEvent
        return this
    }

    override fun build(submission: SubmissionReference) = DBCheck(submission, botComment, config, linkage, redditClient, commentEvent)

    override fun setBotComment(botComment: Comment?): MonitorBuilder<IDBCheck> {
        this.botComment = botComment
        return this
    }


}
class DBCheckFactory @Inject constructor(
        private val config: Config,
        private val linkage: ObservationLinkage,
        private val redditClient: RedditClient
): Provider<IDBCheckBuilder> {
    override fun get(): IDBCheckBuilder {
        return DBCheckBuilder(config, linkage, redditClient)
    }

}

