package de.rtrx.a.database

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import de.rtrx.a.*
import net.dean.jraw.models.Comment
import net.dean.jraw.models.Message
import net.dean.jraw.models.Submission
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Types
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.*
import kotlin.system.exitProcess

object DB {
    val connection: Connection = run {
        val properties = Properties()
        properties.put("user", config[DBSpec.username])
        if (config[DBSpec.password].isNotEmpty()){
            properties.put("password", config[DBSpec.password])
        }
        try {
            Class.forName("org.postgresql.Driver").newInstance()
            DriverManager.getConnection("jdbc:postgresql://${config[DBSpec.address]}/${config[DBSpec.db]}", properties)
        } catch (ex: Exception) {
            println("Something went wrong when trying to connect to the db")
            ex.printStackTrace()
            exitProcess(1)
        }

    }

    init {

    }


    fun insertSubmission(submission: Submission): Int{
        val pst = connection.prepareStatement("INSERT INTO submissions VALUES (?, ?, ?, ?, ?)")
        pst.setString(1, submission.id)
        pst.setString(2, submission.title)
        pst.setString(3, submission.url)
        pst.setString(4, submission.author)
        pst.setObject(5, submission.created.toOffsetDateTime())
        return try {
            pst.executeUpdate()
        } catch (ex: SQLException){
            if(ex.message?.contains("""unique constraint "submission_id"""") ?: false)1
            else {
                logger.error { "SQL Exception: ${ex.message}" }
                0
            }
        }
    }

    fun createCheck(submission_fullname: String, stickied_comment: Comment?, top_comments: Array<Comment>): Boolean{
        val jsonData = getSubmissionJson(submission_fullname)
        val linkFlairText = jsonData?.get("link_flair_text")?.asStringOrNull()
        val userReports = jsonData?.get("user_reports")?.asJsonArray?.ifEmptyNull()
        val userReportsDismissed = jsonData?.get("user_reports_dimissed")?.asJsonArray?.ifEmptyNull()
        val deleted = jsonData?.get("author")?.asStringOrNull() == "[deleted]"
        val removedBy = jsonData?.get("banned_by")?.asStringOrNull()
        val score = jsonData?.get("score")?.asInt
        val unexScore = if(stickied_comment != null)(reddit.lookup(stickied_comment.fullName)[0] as Comment).score else null

        val pst = connection.prepareStatement("SELECT * FROM create_check(?, ?, (to_json(?::json)), (to_json(?::json)), ?, ?, ?, ?, ?, ?, ?, ?)")
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
        pst.setObject(11, top_comments.map { it.id }.toTypedArray())
        pst.setArray(12, connection.createArrayOf("INTEGER", top_comments.map { it.score }.toTypedArray()))

        val commentsPst = connection.prepareStatement("SELECT * FROM comment_if_not_exists(?, ?, ?, ?)")
        top_comments.forEach { comment ->
            commentsPst.setString(1, comment.id)
            commentsPst.setString(2, comment.body)
            commentsPst.setObject(3, comment.created.toOffsetDateTime())
            commentsPst.setString(4, comment.author)
            commentsPst.execute()
        }

        return try {
            pst.execute()
        } catch (ex: SQLException){
            println(ex.message)
            false
        }
    }


    fun commentMessage(submission_id: String, message: Message, comment: Comment): Int{

        val pst = connection.prepareStatement("SELECT * FROM comment_with_message(?, ?, ?, ?, ?, ?, ?, ?)")
        pst.setString(1, submission_id)
        pst.setString(2, message.id)
        pst.setString(3, comment.id)
        pst.setString(4, message.body)
        pst.setString(5, comment.body)
        pst.setString(6, message.author)
        pst.setObject(7, comment.created.toOffsetDateTime(), Types.TIMESTAMP_WITH_TIMEZONE)
        pst.setObject(8, message.created.toOffsetDateTime(), Types.TIMESTAMP_WITH_TIMEZONE)

        return try {
            pst.execute()
            1
        } catch (ex: SQLException){
            println(ex.message)
            0
        }


    }

}

fun Date.toOffsetDateTime() = OffsetDateTime.ofInstant(this.toInstant(), ZoneId.ofOffset("", ZoneOffset.UTC))


fun JsonArray.ifEmptyNull(): JsonArray? {
    return if(size() > 0 ) this else null
}

fun JsonElement.asStringOrNull() = if(this.isJsonNull) null else this.asString