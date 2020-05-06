package de.rtrx.a.database

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.inject.Inject
import com.uchuhimo.konf.Config
import de.rtrx.a.*
import mu.KotlinLogging
import net.dean.jraw.RedditClient
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


interface Booleable{
    val bool: Boolean
}
fun Boolean.toBooleable() = object : Booleable {
    override val bool: Boolean
        get() = this@toBooleable
}
data class CheckSelectResult(public val checkResult: Booleable, public val dbResult: Booleable, public val exception: Throwable?)
interface Linkage {
    val connection: Connection

    /**
     * @return The amount of rows changed in the DB
     */
    fun insertSubmission(submission: Submission): Int

    /**
     * @return whether the check was inserted successfully into the DB or not
     */ fun createCheck(submission_fullname: String, botComment: Comment?, stickied_comment: Comment?, top_comments: Array<Comment>): Pair<Boolean, List<Comment>> {
        return createCheck(getSubmissionJson(submission_fullname), botComment, stickied_comment, top_comments)
    }

    fun getSubmissionJson(submissionFullname: String): JsonObject


    fun <T: Booleable> createCheckSelectValues(
            submission_fullname: String,
            botComment: Comment?,
            stickied_comment: Comment?,
            top_comments: Array<Comment>,
            predicate: (JsonObject) -> T
    ): CheckSelectResult {
        return try {
            val json = getSubmissionJson(submission_fullname)
            val predicateResult = predicate(json)
            CheckSelectResult(predicateResult,
                    if(predicateResult.bool) this.createCheck(json, botComment, stickied_comment, top_comments).first.toBooleable()
                    else false.toBooleable(),
                    null)
        } catch (e: Throwable) {
            KotlinLogging.logger {  }.error { e.message }
            CheckSelectResult(false.toBooleable(), false.toBooleable(), e)
        }
    }

    /**
     * @return The amount of rows changed in the DB
     */
    fun commentMessage(submission_id: String, message: Message, comment: Comment): Int


    fun createCheck(jsonData: JsonObject, botComment: Comment?, stickied_comment: Comment?, top_comments: Array<Comment>): Pair<Boolean, List<Comment>>

    fun add_parent(child: Comment, parent: Comment): Boolean

}

class DummyLinkage:Linkage {
    override fun insertSubmission(submission: Submission): Int = 1

    override fun createCheck(
            data: JsonObject,
            botComment: Comment?,
            stickied_comment: Comment?,
            top_comments: Array<Comment>
    ): Pair<Boolean, List<Comment>> = true to emptyList()

    override fun getSubmissionJson(submissionFullname: String): JsonObject {
        return JsonObject()
    }

    override fun commentMessage(submission_id: String, message: Message, comment: Comment) = 1
    override fun add_parent(child: Comment, parent: Comment): Boolean {
        return true
    }

    override val connection: Connection
        get() = throw DummyException()

    class DummyException: Throwable("Tried to access the sql connection on a dummy")
}

class PostgresSQLinkage @Inject constructor(private val redditClient: RedditClient, private val config: Config): Linkage {
    private val logger = KotlinLogging.logger {  }
    override val connection: Connection = run {
        val properties = Properties()
        properties.put("user", config[DBSpec.username])
        if (config[DBSpec.password].isNotEmpty()){
            properties.put("password", config[DBSpec.password])
        }
        try {
            Class.forName("org.postgresql.Driver").getDeclaredConstructor().newInstance()
            DriverManager.getConnection("jdbc:postgresql://${config[DBSpec.address]}/${config[DBSpec.db]}", properties)
        } catch (ex: Exception) {
            logger.error {"Something went wrong when trying to connect to the db" }
            logger.error { ex.getStackTraceString() }
            exitProcess(1)
        }

    }

    override fun insertSubmission(submission: Submission): Int{
        val pst = connection.prepareStatement("INSERT INTO submissions VALUES (?, ?, ?, ?, ?)")
        pst.setString(1, submission.id)
        pst.setString(2, submission.title)
        pst.setString(3, submission.url)
        pst.setString(4, submission.author)
        pst.setObject(5, submission.created.toOffsetDateTime())
        return try {
            pst.executeUpdate()
        } catch (ex: SQLException){
            if(ex.message?.contains("""unique constraint "submission_id"""") ?: false)0
            else {
                logger.error { "SQL Exception: ${ex.message}" }
                1
            }
        }
    }

    override fun createCheck(jsonData: JsonObject, botComment: Comment?, stickied_comment: Comment?, topComments: Array<Comment>): Pair<Boolean, List<Comment>>{
        val submission_fullname = jsonData.get("name")?.asString ?: return false to emptyList()

        val linkFlairText = jsonData.get("link_flair_text")?.asStringOrNull()
        val userReports = jsonData.get("user_reports")?.asJsonArray?.ifEmptyNull()
        val userReportsDismissed = jsonData.get("user_reports_dimissed")?.asJsonArray?.ifEmptyNull()
        val deleted = jsonData.get("author")?.asStringOrNull() == "[deleted]"
        val removedBy = jsonData.get("banned_by")?.asStringOrNull()
        val score = jsonData.get("score")?.asInt
        val unexScore = if(botComment != null)(redditClient.lookup(botComment.fullName)[0] as Comment).score else null

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
        pst.setObject(11, topComments.map { it.id }.toTypedArray())
        pst.setArray(12, connection.createArrayOf("INTEGER", topComments.map { it.score }.toTypedArray()))

        val commentsPst = connection.prepareStatement("SELECT * FROM comment_if_not_exists(?, ?, ?, ?)")
        val createdComments = topComments.mapNotNull { comment ->
            commentsPst.setString(1, comment.id)
            commentsPst.setString(2, comment.body)
            commentsPst.setObject(3, comment.created.toOffsetDateTime())
            commentsPst.setString(4, comment.author)
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

    override fun getSubmissionJson(submissionFullname: String) = redditClient.getSubmissionJson(submissionFullname)


    override fun commentMessage(submission_id: String, message: Message, comment: Comment): Int{

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
            logger.error { (ex.message) }
            0
        }


    }

    override fun add_parent(child: Comment, parent: Comment): Boolean {
        val pst = connection.prepareStatement("SELECT * FROM add_parent_if_not_exists(?, ?)")
        pst.setString(1, child.id)
        pst.setString(2, parent.id)
        return try {
            pst.execute()
            true
        } catch (ex: SQLException){
            logger.error { ex.message }
            false
        }
    }

}

fun Date.toOffsetDateTime() = OffsetDateTime.ofInstant(this.toInstant(), ZoneId.ofOffset("", ZoneOffset.UTC))


fun JsonArray.ifEmptyNull(): JsonArray? {
    return if(size() > 0 ) this else null
}

fun JsonElement.asStringOrNull() = if(this.isJsonNull) null else this.asString