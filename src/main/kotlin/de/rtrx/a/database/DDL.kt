package de.rtrx.a.database

import com.uchuhimo.konf.Config
import de.rtrx.a.RedditSpec
import mu.KotlinLogging
import java.io.InputStreamReader
import java.sql.PreparedStatement
import java.sql.SQLException
import javax.inject.Inject

val ddlFilePath = "/DDL.sql"

class DDL @Inject constructor( private val config: Config, private val db: Linkage){
    private val logger = KotlinLogging.logger {  }
    fun init(createDDL: Boolean, createFunctions: Boolean){
        if(createDDL){
            logger.info("Creating the DDL")
            val reader = InputStreamReader(DDL::class.java.getResourceAsStream(ddlFilePath))
            val script = SQLScript(reader.readText(), db)
            reader.close()

            script.prepareStatements()
            script.executeStatements()
        }
        if(createFunctions){
            logger.info("Creating DB Functions")
            val statements: List<String> = listOf(commentIfNotExists, commentWithMessage, createCheck, redditUsername, addParentIfNotExists)

            statements.forEach {
                try {
                    db.connection.prepareStatement(it).execute()
                }catch (e: SQLException){
                    logger.error { "Something went wrong when creating function $it:\n${e.message}" }
                }
            }
        }
    }

    private val commentIfNotExists = """
        create function comment_if_not_exists(comment_id text, body text, created timestamp with time zone, author text)
          returns void
        language plpgsql
        as $$
        DECLARE
        BEGIN
            IF NOT EXISTS(SELECT * FROM comments WHERE comments.id = comment_id) THEN
              INSERT INTO comments VALUES(comment_id, body, created, author);
              RETURN true;
            end if;
            RETURN false;
        end;
        $$;
        """.trimIndent()

    private val commentWithMessage = """
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

    private val createCheck = """
        create function create_check(submission_id text, tmptz timestamp with time zone, user_reports json, user_reports_dismissed json, is_deleted boolean, submission_score integer, removed_by text, flair text, current_sticky text, unexscore integer, top_posts_id text[], top_posts_score integer[])
        returns void
        language plpgsql
        as $$
        DECLARE
        BEGIN
          INSERT INTO public."check" VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5, ${'$'}6, ${'$'}7, ${'$'}8, ${'$'}9);
          INSERT INTO public.unex_score VALUES (${'$'}1, ${'$'}2, ${'$'}10);
          FOR i in 1.. array_upper(top_posts_id, 1)
          LOOP
            IF top_posts_id[i] IS NULL THEN
              EXIT;
            end if;
            INSERT INTO public.top_posts VALUES (${'$'}1, ${'$'}2, top_posts_id[i], top_posts_score[i]);
          end loop;
          END;
       $$;
        """.trimIndent()

    private val addParentIfNotExists = """
        create function comment_if_not_exists(comment_id text, body text, created timestamp with time zone, author text) returns boolean
            language plpgsql
        as
        ${'$'}${'$'}
        DECLARE
        BEGIN
            IF NOT EXISTS(SELECT * FROM comments WHERE comments.id = comment_id) THEN
              INSERT INTO comments VALUES(comment_id, body, created, author);
              RETURN true;
            end if;
            RETURN false;
        end;
        ${'$'}${'$'};

    """.trimIndent()

    private val  redditUsername = """
        create function reddit_username()
          returns text
        language sql
        as $$
            SELECT '${config[RedditSpec.credentials.username]}'
        $$;
        """.trimIndent()
}
class SQLScript(val content: String, private val db: Linkage){
    private val logger = KotlinLogging.logger {  }
    lateinit var statements: List<PreparedStatement>

    fun prepareStatements(){
        statements = content.split(";").fold(emptyList<PreparedStatement>()) {prev, str ->
            prev + db.connection.prepareStatement(str)
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

