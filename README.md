# unexBot

A small bot for Reddit that filters out (some) of the rubbish and collects data for analyzing submissions.

## What does this bot do?

1. When a post is submitted to a subreddit, the user is messaged asking him to answer back with a sum-up of his content.
2. Does the user answer quickly?
    1. If the user answers within a small time frame, then the post stays up and the reply is posted in a stickied comment below the post.
    2. If the user doesn't answer within this time frame, then the post is removed. The user has another window of time to reply and restore the post. Replying after this time window is passed will do ... nothing.
3. The bot creates a snapshot of the post, including the score of the post, the score of the stickied comment, if it was removed or deleted, user reports, top comments and so on and saves them in a database. Then the bot waits for a fixed amount of time and repeats everything for a set amount of times.

## How do I use it?

There are both a docker image and a fat-jar (a jar that includes all dependencies) available. You can also build the fat-jar on your own using:

```bash
gradle shadowJar
```

or if you want to create the docker image run while having the docker daemon running (and having access to it):

```bash
gradle dockerBuildImage
```

You'll need gradle with a version > 5.

### Command Line Options

There are currently 4 command line options available:

- `useDB` (boolean): Whether you want to actually want to use a (postresql) database. The default is true, I wouldn't recommend turning it off, it exists mostly for debugging purposes.
- `createDDL` (boolean): Whether you want the bot to run the commands to create the tables in the database. Default is true. This options just exists because the error messages can be a bit annoying.
- `createDBFunctions` (boolean): Same like createDDL, but has even more annoying error messages.
- `configPath` (string): If you don't want to configure everything via environment variables, this should be set to the location of your config file. If no file is found at the given path, the default one will be copied there.
- `startDispatcher` (boolean): Whether the Bot should actually start working. The intended purpose is that you can create the DDL and SQL Functions without starting the bot. It's also the only way to make the program exit with 2.

This would tell the bot to not create the DDL or functions and use the config file called "unexbotConfig.yml" in the same directory:

```bash
java -jar unexBot-1.1.0-all.jar createDDL=false createDBFunctions=false configPath="unexbotConfig.yml"
```

You can pass options for the docker image via the command option.

### Configuration File

Most settings are set to a sane default, but some things (like database access or the Reddit API credentials) will have to be set by every user.
The bot uses three sources for values. The values of the first one are overwritten by the second and third one, and the values of the second one by the third one:

1. The default configuration file included in the jar (and also found in this repository as `config.yml`).
2. Your config file, for which you have specified the path with the command line option `configPath`.
3. Environment variables. This is useful for both configuring the docker image and debugging. To set a variable use a dot to separate the node names (e.g. `reddit.credentials.username`)

### Logging

The SLF4J java.util logging library is included by default, but feel free to swap it out. You will then have to configure the underlying logger according to your needs.

## Credits

This program uses different libraries:

- [JRAW](https://github.com/mattbdean/JRAW) is probably the most important one. I even stole an 1 and a halve class from it (Rotating Search List and SuspendableStream), since the original implementation blocks the thread. Maybe someday I will find the time to create a pull request to JRAW to include this (or create my own fork of it and properly integrate it)
- SLF4J for logging
- [Gson](https://github.com/google/gson): For parsing some data since I'm to stupid to change the appropriate classes of JRAW.
- [Kotlin Logging](https://github.com/MicroUtils/kotlin-logging): A wrapper around SLF4J (which itself is a wrapper around a logging backend).
- [Konf](https://github.com/uchuhimo/konf): For the configuration system.
- [The PostgreSQL JDBC Driver](https://jdbc.postgresql.org).
- [Docker Gradle Plugin](https://github.com/bmuschko/gradle-docker-plugin): Pretty amazing stuff if you ask me!
- [Docker Shadow Plugin](https://github.com/johnrengelman/shadow): To create Fat jars.
- Guice for DI
- Mockito for Mocking
