package de.rtrx.a.tihi.database

import com.google.gson.JsonObject
import de.rtrx.a.database.*
import de.rtrx.a.getStackTraceString
import mu.KotlinLogging
import net.dean.jraw.RedditClient
import net.dean.jraw.models.Comment
import net.dean.jraw.models.Message
import net.dean.jraw.models.Submission
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types
import java.time.OffsetDateTime
import javax.inject.Inject

class NormLinkage @Inject constructor(private val delegateLinkage: PostgresSQLinkage, private val redditClient: RedditClient) : ObservationLinkage by delegateLinkage{
private val logger = KotlinLogging.logger {  }

    override fun createCheck(jsonData: JsonObject, botComment: Comment?, stickied_comment: Comment?, topComments: Array<Comment>): Pair<Boolean, List<Comment>>{
        val submission_fullname = jsonData.get("name")?.asString ?: return false to emptyList()

        val linkFlairText = jsonData.get("link_flair_text")?.asStringOrNull()
        val userReports = jsonData.get("user_reports")?.asJsonArray?.ifEmptyNull()
        val userReportsDismissed = jsonData.get("user_reports_dimissed")?.asJsonArray?.ifEmptyNull()
        val deleted = jsonData.get("author")?.asStringOrNull() == "[deleted]"
        val removedBy = jsonData.get("banned_by")?.asStringOrNull()
        val score = jsonData.get("score")?.asInt
        val unexScore = if(botComment != null)(redditClient.lookup(botComment.fullName)[0] as Comment).score else null

        val pst = delegateLinkage.connection.prepareStatement("SELECT * FROM create_check(?, ?, (to_json(?::json)), (to_json(?::json)), ?, ?, ?, ?, ?, ?, ?, ?)")
        pst.setString(1, submission_fullname.drop(3))
        pst.setObject(2, OffsetDateTime.now(), Types.TIMESTAMP_WITH_TIMEZONE)
        pst.setString(3, userReports?.toString())
        pst.setString(4, userReportsDismissed?.toString())
        pst.setBoolean(5, deleted)
        score?.also { pst.setInt(6, score) } ?: pst.setNull(6, Types.INTEGER)
        pst.setString(7, removedBy)
        pst.setString(8, linkFlairText)
        pst.setString(9, stickied_comment?.id)
        unexScore?.also { pst.setInt(10, unexScore) } ?: pst.setNull(10, Types.INTEGER)
        pst.setObject(11, topComments.map { it.id }.toTypedArray())
        pst.setArray(12, connection.createArrayOf("INTEGER", topComments.map { it.score }.toTypedArray()))

        val commentsPst = connection.prepareStatement("SELECT * FROM comment_if_not_exists(?, ?, ?, ?, ?)")
        val createdComments = topComments.mapNotNull { comment ->
            commentsPst.setString(1, submission_fullname.drop(3))
            commentsPst.setString(2, comment.id)
            commentsPst.setString(3, comment.body)
            commentsPst.setObject(4, comment.created.toOffsetDateTime())
            commentsPst.setString(5, comment.author)
            try {
                commentsPst.execute()
                val resultSet = commentsPst.resultSet
                resultSet.next()
                if(resultSet.getBoolean(1)) comment else null
            } catch (ex: SQLException) {
                logger.error { ex.getStackTraceString() }
                null
            }
        }

        return try {
            pst.execute() to createdComments
        } catch (ex: SQLException){
            logger.error { (ex.message) }
            false to createdComments
        }
    }

}