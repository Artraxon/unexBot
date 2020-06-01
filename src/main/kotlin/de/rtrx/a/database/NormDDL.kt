package de.rtrx.a.tihi.database

import de.rtrx.a.toSupplier
import org.intellij.lang.annotations.Language

object NormDDL {
    @Language("PostgreSQL")
    val commentsTable = """
        create table comments
        (
            id            text                     not null
                constraint comments_pkey
                    primary key,
            body          text                     not null,
            created       timestamp with time zone not null,
            author_id     text                     not null,
            submission_id text
                constraint comment_submission
                    references submissions
        );

        create unique index comments_id_uindex
            on comments (id);

    """.trimIndent().toSupplier()

    @Language("PostgreSQL")
    val topPosts = """
        create table top_posts
        (
            timestamp  timestamp with time zone not null,
            comment_id text                     not null
                constraint comment_referenced
                    references comments,
            score      integer                  not null,
            constraint top_posts_pk
                primary key (timestamp, comment_id)
        );

    """.trimIndent().toSupplier()

    @Language("PostgreSQL")
    val createComment = """
       create function comment_if_not_exists(submission_id text, comment_id text, body text, created timestamp with time zone, author text) returns boolean
    language plpgsql
as
$$
DECLARE
    result boolean;
BEGIN
    result = false;
    INSERT INTO comments VALUES(comment_id, body, created, author, submission_id)
        ON CONFLICT DO NOTHING
        RETURNING TRUE INTO result;
    RETURN result;
end;
$$;
 
    """.trimIndent().toSupplier()

    @Language("PostgreSQL")
    val createCheck = """
        create function create_check(submission_id text, tmptz timestamp with time zone, user_reports json, user_reports_dismissed json, is_deleted boolean, submission_score integer, removed_by text, flair text, current_sticky text, unexscore integer, top_posts_id text[], top_posts_score integer[]) returns void
            language plpgsql
        as
        $$
        DECLARE
        BEGIN
          INSERT INTO "check" VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5, ${'$'}6, ${'$'}7, ${'$'}8, ${'$'}9);
          INSERT INTO unex_score VALUES (${'$'}1, ${'$'}2, ${'$'}10);
          IF array_upper(top_posts_id, 1) IS NOT NULL THEN
              FOR i in 1.. array_upper(top_posts_id, 1)
                  LOOP
                      IF top_posts_id[i] IS NULL THEN
                          EXIT;
                      end if;
                      INSERT INTO top_posts VALUES (${'$'}2, top_posts_id[i], top_posts_score[i]);
                  end loop;
          end if;
        END;
        $$;

    """.trimIndent().toSupplier()
}