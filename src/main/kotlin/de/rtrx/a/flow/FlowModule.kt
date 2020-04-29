package de.rtrx.a.flow

import com.google.inject.AbstractModule
import com.google.inject.Provides
import com.google.inject.Scopes
import com.google.inject.TypeLiteral
import com.google.inject.name.Names
import com.uchuhimo.konf.Config
import de.rtrx.a.RedditSpec
import de.rtrx.a.database.*
import de.rtrx.a.flow.events.*
import de.rtrx.a.initConfig
import de.rtrx.a.monitor.*
import de.rtrx.a.unex.*
import dev.misfitlabs.kotlinguice4.KotlinModule
import dev.misfitlabs.kotlinguice4.typeLiteral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import mu.KotlinLogging
import net.dean.jraw.RedditClient
import net.dean.jraw.http.OkHttpNetworkAdapter
import net.dean.jraw.http.UserAgent
import net.dean.jraw.models.Message
import net.dean.jraw.models.Submission
import net.dean.jraw.oauth.Credentials
import net.dean.jraw.oauth.OAuthHelper
import net.dean.jraw.references.SubmissionReference
import java.lang.Exception
import javax.inject.Named
import javax.inject.Provider

class FlowModule(private val options: Map<String, Any>): KotlinModule() {
    val config: Config
    val redditClient: RedditClient
    val newPostEvent: NewPostReferenceEvent
    val newPostsOutput: ReceiveChannel<SubmissionReference>

    val incomingMessageFactory: IncomingMessageFactory
    val sentMessageFactory: SentMessageFactory

    init {
        config = initConfig(options.get("configPath") as String?)
        redditClient = initReddit(config)

        val (newPosts, outChannel) = RedditNewPostReferenceFactory(config, redditClient).create(config[RedditSpec.subreddit])
        this.newPostEvent = newPosts
        newPostsOutput = outChannel

        incomingMessageFactory = RedditIncomingMessageFactory(config, redditClient)
        sentMessageFactory = RedditSentMessageFactory(config, redditClient)
    }

    @Provides
    fun provideDispatcherStub(
            newPosts: ReceiveChannel<SubmissionReference>,
            flowFactory: UnexFlowFactory,
            @Named("launcherScope") launcherScope: CoroutineScope
    ) : IFlowDispatcherStub<UnexFlow, UnexFlowFactory> = FlowDispatcherStub(newPosts, flowFactory, launcherScope)

    override fun configure() {
        if((options.get("useDB") as Boolean?) ?: true){
            bind(Linkage::class.java).to(PostgresSQLinkage::class.java).`in`(Scopes.SINGLETON)
            bind(DDL::class.java)
        } else bind(Linkage::class.java).to(DummyLinkage::class.java)

        bind(RedditClient::class.java).toInstance(redditClient)
        bind(Config::class.java).toInstance(config)

        bind(IsolationStrategy::class.java).to(SingleFlowIsolation::class.java)

        bind(IncomingMessageFactory::class.java).toInstance(incomingMessageFactory)
        bind(SentMessageFactory::class.java).toInstance(sentMessageFactory)
        bind(typeLiteral<@kotlin.jvm.JvmSuppressWildcards EventMultiplexerBuilder<Message, @kotlin.jvm.JvmSuppressWildcards EventMultiplexer<Message>, @kotlin.jvm.JvmSuppressWildcards ReceiveChannel<Message>>>())
                .toProvider(object : Provider<@kotlin.jvm.JvmSuppressWildcards EventMultiplexerBuilder<Message, @kotlin.jvm.JvmSuppressWildcards EventMultiplexer<Message>, @kotlin.jvm.JvmSuppressWildcards ReceiveChannel<Message>>>{
                    override fun get() = SimpleMultiplexer.SimpleMultiplexerBuilder<Message>()
                })
        //bind(object :TypeLiteral<EventMultiplexerBuilder<Message, @JvmSuppressWildcards EventMultiplexer<Message>, @JvmSuppressWildcards ReceiveChannel< @JvmSuppressWildcards Message>>>(){})
                //.annotatedWith(Names.named("incoming"))
        //bind(typeLiteral<EventMultiplexerBuilder<Message, EventMultiplexer<Message>, ReceiveChannel<Message>>>())
        //bind(object : TypeLiteral<EventMultiplexerBuilder<Message, @JvmSuppressWildcards EventMultiplexer<Message>, @JvmSuppressWildcards ReceiveChannel<@JvmSuppressWildcards Message>>>() {})
                //.annotatedWith(Names.named("sent"))
                //.to(SimpleMultiplexer.SimpleMultiplexerBuilder::class.java)


        //Dispatcher Stub
        bind(MarkAsReadFlow::class.java)
        bind(object : TypeLiteral<@kotlin.jvm.JvmSuppressWildcards ReceiveChannel<SubmissionReference>>() {})
                .toInstance(newPostsOutput)
        bind(object : TypeLiteral<MonitorFactory<*, *>>() {}).to(DBCheckFactory::class.java)
        bind(UnexFlowFactory::class.java).to(RedditUnexFlowFactory::class.java)
        bind(CoroutineScope::class.java).annotatedWith(Names.named("launcherScope"))
                .toInstance(CoroutineScope(Dispatchers.Default))

        bind(MessageComposer::class.java).to(RedditMessageComposer::class.java)
        bind(Replyer::class.java).to(RedditReplyer::class.java)
        bind(Unignorer::class.java).to(RedditUnignorer::class.java)
        bind(MonitorBuilder::class.java).to(DBCheckBuilder::class.java)
        bind(UnexFlowDispatcher::class.java)
    }



    fun initReddit(config: Config): RedditClient {
        println(  config[RedditSpec.credentials.username])
        println( config[RedditSpec.credentials.password])
        println( config[RedditSpec.credentials.clientID])
        println(config[RedditSpec.credentials.clientSecret])

        val oauthCreds = Credentials.script(
                config[RedditSpec.credentials.username],
                config[RedditSpec.credentials.password],
                config[RedditSpec.credentials.clientID],
                config[RedditSpec.credentials.clientSecret]
        )

        val userAgent = UserAgent("linux", config[RedditSpec.credentials.appID], "0.9", config[RedditSpec.credentials.operatorUsername])


        val reddit = try {
            OAuthHelper.automatic(OkHttpNetworkAdapter(userAgent), oauthCreds)
        } catch (e: Throwable){
            KotlinLogging.logger {  }.error { "An exception was raised while trying to authenticate. Are your credentials correct?" }
            System.exit(1)
            throw Exception()
        }
        reddit.logHttp = false
        return reddit
    }
}