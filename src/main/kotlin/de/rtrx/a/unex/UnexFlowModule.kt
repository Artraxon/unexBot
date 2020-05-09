package de.rtrx.a.unex

import com.google.inject.Provides
import com.google.inject.Scopes
import com.google.inject.TypeLiteral
import com.google.inject.assistedinject.FactoryModuleBuilder
import com.google.inject.name.Names
import com.uchuhimo.konf.Config
import de.rtrx.a.RedditSpec
import de.rtrx.a.database.*
import de.rtrx.a.flow.*
import de.rtrx.a.flow.events.*
import de.rtrx.a.flow.events.comments.CommentsFetcherFactory
import de.rtrx.a.flow.events.comments.FullComments
import de.rtrx.a.flow.events.comments.ManuallyFetchedEvent
import de.rtrx.a.flow.events.comments.RedditCommentsFetchedFactory
import de.rtrx.a.initConfig
import de.rtrx.a.monitor.*
import dev.misfitlabs.kotlinguice4.KotlinModule
import dev.misfitlabs.kotlinguice4.typeLiteral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import net.dean.jraw.RedditClient
import net.dean.jraw.http.OkHttpNetworkAdapter
import net.dean.jraw.http.UserAgent
import net.dean.jraw.models.Message
import net.dean.jraw.oauth.Credentials
import net.dean.jraw.oauth.OAuthHelper
import net.dean.jraw.references.PublicContributionReference
import net.dean.jraw.references.SubmissionReference
import org.w3c.dom.events.Event
import java.lang.Exception
import javax.inject.Named
import javax.inject.Provider
import kotlin.reflect.KClass

class UnexFlowModule(): KotlinModule() {

    @Provides
    fun provideDispatcherStub(
            newPosts: ReceiveChannel<SubmissionReference>,
            flowFactory: UnexFlowFactory,
            @Named("launcherScope") launcherScope: CoroutineScope,
            manuallyFetchedFactory: CommentsFetcherFactory
    ) : IFlowDispatcherStub<UnexFlow, UnexFlowFactory> = FlowDispatcherStub(newPosts, flowFactory, launcherScope,
            mapOf( ManuallyFetchedEvent::class to (manuallyFetchedFactory to SubmissionReference::class) ) as EventFactories )

    @Provides
    @com.google.inject.name.Named ("functions")
    fun provideDDLFunctions(config: Config) = with(DDL.Companion.Functions){listOf(
            addParentIfNotExists,
            commentIfNotExists,
            commentWithMessage,
            createCheck,
            redditUsername
    ).map { it(config) }}

    @Provides
    @com.google.inject.name.Named("tables")
    fun provideDDLTable(config: Config) = with(DDL.Companion.Tables) { listOf(
            submissions,
            check,
            comments,
            comments_caused,
            commentsHierarchy,
            unexScore,
            top_posts,
            relevantMessages
    ).map { it(config) }}


    override fun configure() {
        bind(UnexFlowFactory::class.java).to(RedditUnexFlowFactory::class.java)
        bind(CoroutineScope::class.java).annotatedWith(Names.named("launcherScope"))
                .toInstance(CoroutineScope(Dispatchers.Default))
        bind(UnexFlowDispatcher::class.java)
    }


}