package de.rtrx.a.unex

import com.google.inject.Provides
import com.google.inject.Scopes
import com.google.inject.assistedinject.FactoryModuleBuilder
import com.google.inject.name.Names
import com.uchuhimo.konf.Config
import de.rtrx.a.database.*
import de.rtrx.a.flow.*
import de.rtrx.a.flow.events.comments.CommentsFetcherFactory
import de.rtrx.a.flow.events.comments.ManuallyFetchedEvent
import dev.misfitlabs.kotlinguice4.KotlinModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import net.dean.jraw.references.SubmissionReference
import javax.inject.Named

class UnexFlowModule(private val restart: Boolean): KotlinModule() {

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

    @Provides
    fun provideApprovedCheck(linkage: ObservationLinkage): DeletePrevention = DelayedDelete.approvedCheck(linkage)

    override fun configure() {
        install(FactoryModuleBuilder()
                .implement(DelayedDelete::class.java, RedditDelayedDelete::class.java)
                .build(DelayedDeleteFactory::class.java))

        bind(Linkage::class.java).to(PostgresSQLinkage::class.java).`in`(Scopes.SINGLETON)
        bind(ObservationLinkage::class.java).to(PostgresSQLinkage::class.java).`in`(Scopes.SINGLETON)
        bind(ConversationLinkage::class.java).to(PostgresSQLinkage::class.java).`in`(Scopes.SINGLETON)

        bind(Boolean::class.java).annotatedWith(Names.named("restart")).toInstance(restart)
        bind(UnexFlowFactory::class.java).to(RedditUnexFlowFactory::class.java)
        bind(CoroutineScope::class.java).annotatedWith(Names.named("launcherScope"))
                .toInstance(CoroutineScope(Dispatchers.Default))
        bind(UnexFlowDispatcher::class.java)
    }


}