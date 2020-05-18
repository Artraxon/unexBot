package de.rtrx.a.database

import com.uchuhimo.konf.Config
import de.rtrx.a.RedditSpec
import de.rtrx.a.toSupplier
import mu.KotlinLogging
import org.intellij.lang.annotations.Language
import java.sql.PreparedStatement
import java.sql.SQLException
import javax.inject.Inject
import javax.inject.Named

val ddlFilePath = "/DDL.sql"
typealias DDLFunctions = List<(Config) -> String>
class DDL @Inject constructor(
        private val config: Config,
        private val db: Linkage,
        @param:Named("functions") private val ddlFunctions: @JvmSuppressWildcards List<String>,
        @param:Named("tables") private val DDLs: @JvmSuppressWildcards List<String>
){
    private val logger = KotlinLogging.logger {  }
    fun init(createDDL: Boolean, createFunctions: Boolean){
        if(createDDL){
            logger.info("Creating the DDL")
            DDLs.forEach {
                try {
                    db.connection.prepareStatement(it).execute()
                } catch (e: SQLException){
                    logger.error { "Something went wrong when creating table $it:\n${e.message}" }
                }
            }
        }
        if(createFunctions){
            logger.info("Creating DB Functions")

            ddlFunctions.forEach {
                try {
                    db.connection.prepareStatement(it).execute()
                }catch (e: SQLException){
                    logger.error { "Something went wrong when creating function $it:\n${e.message}" }
                }
            }
        }
    }
    companion object {
        object Functions {
            @Language("PostgreSQL")
            val commentIfNotExists = """
        create function comment_if_not_exists(comment_id text, body text, created timestamp with time zone, author text)
          returns boolean
        language plpgsql
        as $$
        DECLARE
            result boolean;
        BEGIN
            result = false;
            INSERT INTO comments VALUES(comment_id, body, created, author)
                ON CONFLICT DO NOTHING
                RETURNING TRUE INTO result;
            RETURN result;
        end;
            $$;
        """.trimIndent().toSupplier()

            @Language("PostgreSQL")
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
        """.trimIndent().toSupplier()

            @Language("PostgreSQL")
            val createCheck = """
        create function create_check(submission_id text, tmptz timestamp with time zone, user_reports json, user_reports_dismissed json, is_deleted boolean, submission_score integer, removed_by text, flair text, current_sticky text, unexscore integer, top_posts_id text[], top_posts_score integer[])
        returns void
        language plpgsql
        as $$
        DECLARE
        BEGIN
          INSERT INTO "check" VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5, ${'$'}6, ${'$'}7, ${'$'}8, ${'$'}9);
          INSERT INTO unex_score VALUES (${'$'}1, ${'$'}2, ${'$'}10);
          FOR i in 1.. array_upper(top_posts_id, 1)
          LOOP
            IF top_posts_id[i] IS NULL THEN
              EXIT;
            end if;
            INSERT INTO top_posts VALUES (${'$'}1, ${'$'}2, top_posts_id[i], top_posts_score[i]);
          end loop;
          END;
       $$;
        """.trimIndent().toSupplier()

            @Language("PostgreSQL")
            val addParentIfNotExists = """
        create function add_parent_if_not_exists(c_id text, p_id text) returns boolean
            language plpgsql
        as
        $$
        DECLARE
            result boolean;
        BEGIN
            result = false;
            INSERT INTO comments_hierarchy VALUES(c_id, p_id)
                ON CONFLICT DO NOTHING 
                RETURNING TRUE INTO result;
            RETURN result;
        end;
        $$;

    """.trimIndent().toSupplier()

            val redditUsername = { config: Config ->
                """
        create function reddit_username()
          returns text
        language sql
        as $$
            SELECT '${config[RedditSpec.credentials.username]}'
        $$;
        """.trimIndent()
            }
        }

        object Tables {

            @Language("PostgreSQL")
            val comments = """
            create table if not exists comments
            (
                id text not null
                    constraint comments_pkey
                        primary key,
                body text not null,
                created timestamp with time zone not null,
                author_id text not null
            )
            ;

            create unique index comments_id_uindex
                on comments (id)
            ;""".trimIndent().toSupplier()

            @Language("PostgreSQL")
            val submissions = """create table if not exists submissions
            (
                id text not null
                    constraint submission_id
                        primary key,
                title text not null,
                url text not null,
                author_id text not null,
                created timestamp with time zone not null
            )
            ;""".trimIndent().toSupplier()


            @Language("PostgreSQL")
            val check = """create table if not exists "check"
            (
                submission_id text not null
                    constraint submission_constraint
                references submissions,
                timestamp timestamp with time zone not null,
                user_reports json,
                dismissed_user_reports json,
                is_deleted boolean not null,
                submission_score integer,
                removed_by text,
                flair text,
                current_sticky text
                    constraint comment_stickied
                references comments,
                    constraint depend_on_submission
                        primary key (submission_id, timestamp)
            )
            ;


            create unique index check_timestamp_uindex
            on "check" (timestamp)
            ;""".trimIndent().toSupplier()

            @Language("PostgreSQL")
            val relevantMessages = """create table if not exists relevant_messages
            (
                id text not null
                    constraint message_pkey
                    primary key,
                submission_id text not null
                    constraint submission_explained
                    references submissions,
                body text not null,
                author_id text not null,
                timestamp timestamp with time zone
            )
            ;
            
            create unique index message_id_uindex
                on relevant_messages (id)
            """.trimIndent().toSupplier()


            @Language("PostgreSQL")
            val comments_caused = """create table if not exists comments_caused
            (
                message_id text not null
                    constraint message_ref
                        references relevant_messages,
                comment_id text not null
                    constraint comment_ref
                        references comments,
                    constraint comment_caused_pk
                        primary key (comment_id, message_id)
                )
            ;

            create unique index comment_caused_message_id_uindex
            on comments_caused (message_id)
            ;

            ;""".trimIndent().toSupplier()

            @Language("PostgreSQL")
            val top_posts = """create table if not exists top_posts
            (
                submission_id text not null,
                timestamp timestamp with time zone not null,
                comment_id text not null
                    constraint comment_referenced
                        references comments,
                score integer not null,
                constraint top_posts_pk
                    primary key (submission_id, timestamp, comment_id),
                constraint during_check
                    foreign key (submission_id, timestamp) references "check"
            )
            ;""".trimIndent().toSupplier()

            @Language("PostgreSQL")
            val unexScore = """create table if not exists unex_score
            (
                submission_id text not null,
                timestamp timestamp with time zone not null,
                score integer,
                constraint check_identifier
                    primary key (submission_id, timestamp),
                constraint check_performed
                    foreign key (submission_id, timestamp) references "check"
            )
            ;""".trimIndent().toSupplier()

            @Language("PostgreSQL")
            val commentsHierarchy = """
            create table comments_hierarchy
            (
                child_id  text not null
                    constraint comments_hierarchy_pk
                        primary key
                    constraint comment_child
                        references comments,
                parent_id text not null
                    constraint parent_comment
                        references comments
            );
            """.trimIndent().toSupplier()


        }
    }

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

