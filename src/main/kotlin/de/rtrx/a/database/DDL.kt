package de.rtrx.a.database

import de.rtrx.a.RedditSpec
import de.rtrx.a.config
import de.rtrx.a.logger
import java.io.InputStreamReader
import java.sql.PreparedStatement
import java.sql.SQLException

val ddlFilePath = "/DDL.sql"

object DDL {
    fun init(createDDL: Boolean, createFunctions: Boolean){
        if(createDDL){
            val reader = InputStreamReader(DDL.javaClass.getResourceAsStream(ddlFilePath))
            val script = SQLScript(reader.readText())
            reader.close()

            script.prepareStatements()
            script.executeStatements()
        }
        if(createFunctions){
            lateinit var statements: List<String>
            with(SQLFunctions){
                statements = listOf(commentIfNotExists, commentWithMessage, createCheck, redditUsername)
            }

            statements.forEach { DB.connection.prepareStatement(it).execute() }
        }
    }
}
class SQLScript(val content: String){
    lateinit var statements: List<PreparedStatement>

    fun prepareStatements(){
        statements = content.split(";").fold(emptyList<PreparedStatement>()) {prev, str ->
            prev + DB.connection.prepareStatement(str)
        }
    }

    fun executeStatements(){
        statements.forEach {
            try {
                it.execute()
            }catch (ex: SQLException) {
                logger.error { "During DDL init: ${ex.message}" }
            }
        }
    }
}

object SQLFunctions {
    val commentIfNotExists = """
        create function comment_if_not_exists(comment_id text, body text, created timestamp with time zone, author text)
          returns void
        language plpgsql
        as $$
        DECLARE
        test comments%ROWTYPE;
        BEGIN
            IF NOT EXISTS(SELECT * FROM comments WHERE comments.id = comment_id) THEN
              INSERT INTO comments VALUES(comment_id, body, created, author);
            end if;
        end;
        $$;
        """.trimIndent()

    val commentWithMessage = """
        create function comment_with_message(submission_id text, message_id text, comment_id text, message_body text, comment_body text, author_id text, comment_time timestamp with time zone, message_time timestamp with time zone)
          returns void
        language plpgsql
        as $$
        DECLARE
        BEGIN
          INSERT INTO comments VALUES (comment_id, comment_body, comment_time, reddit_username());
          INSERT INTO relevant_messages VALUES (message_id, submission_id, message_body, author_id, message_time);
          INSERT INTO comments_caused VALUES (message_id, comment_id);
        END
        $$;
        """.trimIndent()

    val createCheck = """
        create function comment_with_message(submission_id text, message_id text, comment_id text, message_body text, comment_body text, author_id text, comment_time timestamp with time zone, message_time timestamp with time zone)
          returns void
        language plpgsql
        as $$
        DECLARE
        BEGIN
          INSERT INTO comments VALUES (comment_id, comment_body, comment_time, reddit_username());
          INSERT INTO relevant_messages VALUES (message_id, submission_id, message_body, author_id, message_time);
          INSERT INTO comments_caused VALUES (message_id, comment_id);
        END
        $$;
        """.trimIndent()

    val redditUsername = """
        create function reddit_username()
          returns text
        language sql
        as $$
            SELECT '${config[RedditSpec.credentials.username]}'
        $$;
        """.trimIndent()
}
