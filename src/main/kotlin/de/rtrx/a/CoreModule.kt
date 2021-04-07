package de.rtrx.a

import com.google.inject.Provides
import com.google.inject.TypeLiteral
import com.google.inject.name.Names
import com.uchuhimo.konf.Config
import de.rtrx.a.database.DDL
import de.rtrx.a.flow.*
import de.rtrx.a.flow.events.*
import de.rtrx.a.flow.events.comments.CommentsFetcherFactory
import de.rtrx.a.flow.events.comments.FullComments
import de.rtrx.a.flow.events.comments.RedditCommentsFetchedFactory
import de.rtrx.a.monitor.DBCheckFactory
import de.rtrx.a.monitor.IDBCheckBuilder
import dev.misfitlabs.kotlinguice4.KotlinModule
import dev.misfitlabs.kotlinguice4.typeLiteral
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import net.dean.jraw.RedditClient
import net.dean.jraw.http.OkHttpNetworkAdapter
import net.dean.jraw.http.UserAgent
import net.dean.jraw.models.Message
import net.dean.jraw.oauth.Credentials
import net.dean.jraw.oauth.OAuthHelper
import net.dean.jraw.references.SubmissionReference
import java.lang.Exception
import javax.inject.Provider

class CoreModule(private val config: Config, private val useDB: Boolean) : KotlinModule() {
    private val logger = KotlinLogging.logger {  }
    val redditClient: RedditClient

    val newPostEvent: NewPostReferenceEvent
    val newPostsOutput: ReceiveChannel<SubmissionReference>

    val incomingMessageFactory: IncomingMessageFactory
    val sentMessageFactory: SentMessageFactory

    init {
        redditClient = initReddit()

        val (newPosts, outChannel) =
                runBlocking { RedditNewPostReferenceFactory(config, redditClient).create(config[RedditSpec.subreddit]) }

        this.newPostEvent = newPosts
        newPostsOutput = outChannel

        incomingMessageFactory = RedditIncomingMessageFactory(config, redditClient, CoroutineStart.LAZY)
        sentMessageFactory = RedditSentMessageFactory(config, redditClient, CoroutineStart.LAZY)
    }



    @Provides
    fun provideEventMultiplexer(): EventMultiplexerBuilder<FullComments, *, @JvmSuppressWildcards ReceiveChannel<FullComments>>{
        return SimpleMultiplexer.SimpleMultiplexerBuilder<FullComments>().setIsolationStrategy(SingleFlowIsolation())
    }

    @Provides
    fun provideSpecificEventMultiplexer(): EventMultiplexerBuilder<FullComments, EventMultiplexer<FullComments>, @JvmSuppressWildcards ReceiveChannel<FullComments>>{
        return provideEventMultiplexer()
    }

    override fun configure() {
        bind(RedditClient::class.java).toInstance(redditClient)
        bind(Config::class.java).toInstance(config)

        if(useDB) bind(DDL::class.java)

        bindConfigValues()
        bind(IsolationStrategy::class.java).to(SingleFlowIsolation::class.java)

        bind(typeLiteral<@JvmSuppressWildcards EventMultiplexerBuilder<Message, @JvmSuppressWildcards EventMultiplexer<Message>, @JvmSuppressWildcards ReceiveChannel<Message>>>())
                .toProvider(object : Provider<@kotlin.jvm.JvmSuppressWildcards EventMultiplexerBuilder<Message, @kotlin.jvm.JvmSuppressWildcards EventMultiplexer<Message>, @kotlin.jvm.JvmSuppressWildcards ReceiveChannel<Message>>> {
                    override fun get() = SimpleMultiplexer.SimpleMultiplexerBuilder<Message>()
                })

        bind(IncomingMessageFactory::class.java).toInstance(incomingMessageFactory)
        bind(SentMessageFactory::class.java).toInstance(sentMessageFactory)
        bind(CommentsFetcherFactory::class.java).to(RedditCommentsFetchedFactory::class.java)
        bind(object : TypeLiteral<@kotlin.jvm.JvmSuppressWildcards ReceiveChannel<SubmissionReference>>() {})
                .toInstance(newPostsOutput)


        bind(MarkAsReadFlow::class.java)

        bind(object : TypeLiteral<JumpstartConversation<String>>() {}).toProvider(DefferedConversationProvider::class.java)
        bind(IDBCheckBuilder::class.java).toProvider(DBCheckFactory::class.java)
        bind(MessageComposer::class.java).to(RedditMessageComposer::class.java)
        bind(Replyer::class.java).to(RedditReplyer::class.java)
        bind(Unignorer::class.java).to(RedditUnignorer::class.java)

    }

    fun bindConfigValues() {
        bind(Long::class.java).annotatedWith(Names.named("delayToDeleteMillis")).toInstance(config[RedditSpec.scoring.timeUntilRemoval])
        bind(Long::class.java)
                .annotatedWith(Names.named("delayToFinishMillis"))
                .toInstance(config[RedditSpec.messages.sent.maxTimeDistance])
    }
    fun initReddit(): RedditClient {

        val oauthCreds = Credentials.script(
                config[RedditSpec.credentials.username],
                config[RedditSpec.credentials.password],
                config[RedditSpec.credentials.clientID],
                config[RedditSpec.credentials.clientSecret]
        )

        val userAgent = UserAgent("linux", config[RedditSpec.credentials.appID], "2.2", config[RedditSpec.credentials.operatorUsername])


        val reddit = try {
            logger.info { "Trying to authenticate..." }
            OAuthHelper.automatic(OkHttpNetworkAdapter(userAgent), oauthCreds)
        } catch (e: Throwable){
            logger.error { "An exception was raised while trying to authenticate. Are your credentials correct?" }
            System.exit(1)
            throw Exception()
        }

        logger.info { "Successfully authenticated" }
        reddit.logHttp = false
        return reddit
    }
}