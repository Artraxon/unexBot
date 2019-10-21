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
;

create table if not exists submissions
(
  id text not null
    constraint submission_id
    primary key,
  title text not null,
  url text not null,
  author_id text not null,
  created timestamp with time zone not null
)
;


create table if not exists "check"
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
;

create table if not exists relevant_messages
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


create table if not exists comments_caused
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


create unique index comment_caused_comment_id_uindex
  on comments_caused (comment_id)
;

create unique index comment_caused_message_id_uindex
  on comments_caused (message_id)
;

create unique index message_id_uindex
  on relevant_messages (id)
;

create table if not exists top_posts
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
;

create table if not exists unex_score
(
  submission_id text not null,
  timestamp timestamp with time zone not null,
  score integer,
  constraint check_identifier
  primary key (submission_id, timestamp),
  constraint check_performed
  foreign key (submission_id, timestamp) references "check"
)
;


