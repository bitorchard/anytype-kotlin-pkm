package com.anytypeio.anytype.di.feature.pebble

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import com.anytypeio.anytype.di.common.ComponentDependencies
import com.anytypeio.anytype.domain.base.AppCoroutineDispatchers
import com.anytypeio.anytype.domain.objects.StoreOfObjectTypes
import com.anytypeio.anytype.domain.objects.StoreOfRelations
import com.anytypeio.anytype.domain.workspace.SpaceManager
import com.anytypeio.anytype.feature.pebble.ui.approval.ApprovalViewModel
import com.anytypeio.anytype.feature.pebble.ui.changelog.ChangeLogViewModel
import com.anytypeio.anytype.feature.pebble.ui.changelog.ChangeSetDetailViewModel
import com.anytypeio.anytype.feature.pebble.ui.dashboard.PebbleDashboardViewModel
import com.anytypeio.anytype.feature.pebble.ui.debug.PebbleDebugViewModel
import com.anytypeio.anytype.feature.pebble.ui.history.InputHistoryViewModel
import com.anytypeio.anytype.feature.pebble.ui.manual.ManualInputViewModel
import com.anytypeio.anytype.feature.pebble.ui.settings.PebbleSettingsRepository
import com.anytypeio.anytype.feature.pebble.ui.settings.PebbleSettingsViewModel
import com.anytypeio.anytype.feature.pebble.ui.settings.WebhookQrViewModel
import com.anytypeio.anytype.pebble.assimilation.AssimilationEngine
import com.anytypeio.anytype.pebble.assimilation.context.ContextWindow
import com.anytypeio.anytype.pebble.assimilation.extraction.EntityExtractor
import com.anytypeio.anytype.pebble.assimilation.llm.LlmClient
import com.anytypeio.anytype.pebble.assimilation.llm.LlmClientFactory
import com.anytypeio.anytype.pebble.assimilation.llm.LlmProvider as AssimLlmProvider
import com.anytypeio.anytype.pebble.assimilation.llm.LlmClientConfig
import com.anytypeio.anytype.pebble.assimilation.model.ExtractionResult
import com.anytypeio.anytype.pebble.assimilation.plan.PlanGenerator
import com.anytypeio.anytype.pebble.assimilation.resolution.EntityCache
import com.anytypeio.anytype.pebble.assimilation.resolution.DisambiguationResolver
import com.anytypeio.anytype.pebble.assimilation.resolution.EntityResolver
import com.anytypeio.anytype.pebble.assimilation.resolution.ResolutionFeedbackDatabase
import com.anytypeio.anytype.pebble.assimilation.resolution.ResolutionFeedbackStore
import com.anytypeio.anytype.pebble.assimilation.resolution.ScoringEngine
import com.anytypeio.anytype.pebble.changecontrol.engine.ChangeExecutor
import com.anytypeio.anytype.pebble.changecontrol.engine.ChangeRollback
import com.anytypeio.anytype.pebble.changecontrol.store.AnytypeChangeStore
import com.anytypeio.anytype.pebble.changecontrol.store.ChangeStore
import com.anytypeio.anytype.pebble.changecontrol.store.CompositeChangeStore
import com.anytypeio.anytype.pebble.changecontrol.store.LocalChangeCache
import com.anytypeio.anytype.pebble.changecontrol.store.PebbleChangeDatabase
import com.anytypeio.anytype.pebble.core.AssimilationPipeline
import com.anytypeio.anytype.pebble.core.PipelineNotifier
import com.anytypeio.anytype.pebble.webhook.pipeline.InputProcessor
import com.anytypeio.anytype.ui.pebble.AndroidPipelineNotifier
import com.anytypeio.anytype.pebble.core.PebbleGraphService
import com.anytypeio.anytype.pebble.core.PebbleSearchService
import com.anytypeio.anytype.pebble.core.impl.DefaultPebbleGraphService
import com.anytypeio.anytype.pebble.core.impl.DefaultPebbleSearchService
import com.anytypeio.anytype.pebble.core.observability.PipelineEventStore
import com.anytypeio.anytype.pebble.core.observability.PipelineObservabilityDatabase
import com.anytypeio.anytype.pebble.core.observability.RoomPipelineEventStore
import com.anytypeio.anytype.pebble.webhook.model.WebhookConfig
import com.anytypeio.anytype.pebble.webhook.queue.InputQueue
import com.anytypeio.anytype.pebble.webhook.queue.InputQueueDatabase
import com.anytypeio.anytype.pebble.webhook.queue.PersistentInputQueue
import com.anytypeio.anytype.pebble.webhook.server.WebhookServer
import com.anytypeio.anytype.ui.pebble.ApprovalFragment
import com.anytypeio.anytype.ui.pebble.ManualInputFragment
import com.anytypeio.anytype.ui.pebble.ChangeLogFragment
import com.anytypeio.anytype.ui.pebble.ChangeSetDetailFragment
import com.anytypeio.anytype.ui.pebble.InputHistoryFragment
import com.anytypeio.anytype.ui.pebble.PebbleDashboardFragment
import com.anytypeio.anytype.ui.pebble.PebbleDebugFragment
import com.anytypeio.anytype.ui.pebble.PebbleSettingsFragment
import com.anytypeio.anytype.ui.pebble.WebhookQrFragment
import dagger.Binds
import dagger.Component
import dagger.MapKey
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import javax.inject.Provider
import javax.inject.Scope
import javax.inject.Singleton
import kotlin.reflect.KClass

// region Pebble PKM Integration

@Scope
@Retention(AnnotationRetention.RUNTIME)
annotation class PebbleScope

/**
 * Dependencies that [com.anytypeio.anytype.di.main.MainComponent] exposes to the Pebble component.
 * Only AnyType-core services are listed here; all Pebble-specific infrastructure is self-provided.
 */
interface PebbleDependencies : ComponentDependencies {
    fun pebbleGraphService(): PebbleGraphService
    fun pebbleSearchService(): PebbleSearchService
    fun appCoroutineDispatchers(): AppCoroutineDispatchers
    fun spaceManager(): SpaceManager
    fun storeOfObjectTypes(): StoreOfObjectTypes
    fun storeOfRelations(): StoreOfRelations
    fun context(): Context
}

/**
 * Dagger module that binds the AnyType adapter implementations at MainComponent level.
 */
@Module
interface PebbleModule {

    @Singleton
    @Binds
    fun bindPebbleGraphService(impl: DefaultPebbleGraphService): PebbleGraphService

    @Singleton
    @Binds
    fun bindPebbleSearchService(impl: DefaultPebbleSearchService): PebbleSearchService
}

// ── ViewModel multi-binding ───────────────────────────────────────────────────

@MapKey
annotation class ViewModelKey(val value: KClass<out ViewModel>)

class PebbleViewModelFactory(
    private val providers: Map<Class<out ViewModel>, @JvmSuppressWildcards Provider<ViewModel>>
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        providers[modelClass]?.get() as? T
            ?: throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
}

// ── Infrastructure module — all Pebble-owned singletons ───────────────────────

@Module
object PebbleInfraModule {

    @PebbleScope
    @Provides
    fun settingsRepo(context: Context): PebbleSettingsRepository = PebbleSettingsRepository(context)

    @PebbleScope
    @Provides
    fun observabilityDb(context: Context): PipelineObservabilityDatabase =
        Room.databaseBuilder(context, PipelineObservabilityDatabase::class.java, "pebble_observability.db")
            .fallbackToDestructiveMigration()
            .build()

    @PebbleScope
    @Provides
    fun pipelineEventStore(db: PipelineObservabilityDatabase): PipelineEventStore =
        RoomPipelineEventStore(db.pipelineEventDao())

    @PebbleScope
    @Provides
    fun inputQueueDb(context: Context): InputQueueDatabase =
        Room.databaseBuilder(context, InputQueueDatabase::class.java, "pebble_input_queue.db")
            .fallbackToDestructiveMigration()
            .build()

    @PebbleScope
    @Provides
    fun inputQueue(
        db: InputQueueDatabase,
        eventStore: PipelineEventStore
    ): InputQueue = PersistentInputQueue(db.inputQueueDao(), eventStore)

    @PebbleScope
    @Provides
    fun changeDb(context: Context): PebbleChangeDatabase =
        Room.databaseBuilder(context, PebbleChangeDatabase::class.java, "pebble_change.db")
            .fallbackToDestructiveMigration()
            .build()

    @PebbleScope
    @Provides
    fun localChangeCache(db: PebbleChangeDatabase): LocalChangeCache = LocalChangeCache(db.changeSetDao())

    @PebbleScope
    @Provides
    fun anytypeChangeStore(graphService: PebbleGraphService): AnytypeChangeStore =
        AnytypeChangeStore(graphService)

    @PebbleScope
    @Provides
    fun changeStore(
        anytypeStore: AnytypeChangeStore,
        localCache: LocalChangeCache
    ): ChangeStore = CompositeChangeStore(anytypeStore, localCache)

    @PebbleScope
    @Provides
    fun changeExecutor(
        graphService: PebbleGraphService,
        changeStore: ChangeStore,
        eventStore: PipelineEventStore
    ): ChangeExecutor = ChangeExecutor(graphService, changeStore, eventStore)

    @PebbleScope
    @Provides
    fun changeRollback(
        graphService: PebbleGraphService,
        changeStore: ChangeStore,
        eventStore: PipelineEventStore
    ): ChangeRollback = ChangeRollback(graphService, changeStore, eventStore)

    @Provides
    fun webhookConfig(repo: PebbleSettingsRepository): WebhookConfig {
        val settings = repo.currentSnapshot()
        return if (settings != null) {
            WebhookConfig(
                port = settings.webhookPort,
                authToken = settings.webhookAuthToken
            )
        } else {
            WebhookConfig()
        }
    }

    @PebbleScope
    @Provides
    fun webhookServer(inputQueue: InputQueue, eventStore: PipelineEventStore): WebhookServer =
        WebhookServer(inputQueue, eventStore)

    // ── Assimilation infrastructure ──────────────────────────────────────────

    @PebbleScope
    @Provides
    fun feedbackDb(context: Context): ResolutionFeedbackDatabase =
        ResolutionFeedbackDatabase.create(context)

    @PebbleScope
    @Provides
    fun feedbackStore(db: ResolutionFeedbackDatabase): ResolutionFeedbackStore =
        ResolutionFeedbackStore(db.feedbackDao())

    @PebbleScope
    @Provides
    fun llmClient(repo: PebbleSettingsRepository): LlmClient {
        // Create a settings-aware delegating client that reads credentials dynamically.
        return object : LlmClient {
            override val modelName: String
                get() = repo.currentSnapshot()?.llmModel ?: LlmClientConfig.DEFAULT_ANTHROPIC_MODEL

            override suspend fun extractEntities(
                systemPrompt: String,
                userInput: String
            ): ExtractionResult {
                val settings = repo.currentSnapshot() ?: run {
                    return ExtractionResult(emptyList(), emptyList(), overallConfidence = 0f)
                }
                val config = LlmClientConfig(
                    provider = when (settings.llmProvider) {
                        com.anytypeio.anytype.feature.pebble.ui.settings.LlmProvider.ANTHROPIC -> AssimLlmProvider.ANTHROPIC
                        com.anytypeio.anytype.feature.pebble.ui.settings.LlmProvider.OPENAI -> AssimLlmProvider.OPENAI
                    },
                    apiKey = settings.llmApiKey,
                    model = settings.llmModel
                )
                return LlmClientFactory.create(config).extractEntities(systemPrompt, userInput)
            }
        }
    }

    @PebbleScope
    @Provides
    fun contextWindow(): ContextWindow = ContextWindow()

    @PebbleScope
    @Provides
    fun entityCache(searchService: PebbleSearchService): EntityCache = EntityCache(searchService)

    @PebbleScope
    @Provides
    fun scoringEngine(
        contextWindow: ContextWindow,
        feedbackStore: ResolutionFeedbackStore
    ): ScoringEngine = ScoringEngine(contextWindow, feedbackStore)

    @PebbleScope
    @Provides
    fun entityResolver(
        scoringEngine: ScoringEngine,
        entityCache: EntityCache
    ): EntityResolver = EntityResolver(scoringEngine, entityCache)

    @PebbleScope
    @Provides
    fun entityExtractor(
        llmClient: LlmClient,
        contextWindow: ContextWindow,
        eventStore: PipelineEventStore
    ): EntityExtractor = EntityExtractor(llmClient, contextWindow, eventStore)

    @PebbleScope
    @Provides
    fun planGenerator(): PlanGenerator = PlanGenerator()

    @PebbleScope
    @Provides
    fun disambiguationResolver(): DisambiguationResolver = DisambiguationResolver()

    @PebbleScope
    @Provides
    fun assimilationEngine(
        entityExtractor: EntityExtractor,
        entityResolver: EntityResolver,
        planGenerator: PlanGenerator,
        changeStore: ChangeStore,
        contextWindow: ContextWindow,
        eventStore: PipelineEventStore,
        changeExecutor: ChangeExecutor,
        repo: PebbleSettingsRepository
    ): AssimilationPipeline {
        val settings = repo.currentSnapshot()
        val autoApproveThreshold = if (settings?.autoApproveEnabled == true) {
            settings.autoApproveThreshold
        } else {
            null
        }
        return AssimilationEngine(
            entityExtractor, entityResolver, planGenerator, changeStore, contextWindow,
            eventStore, autoApproveThreshold, changeExecutor
        )
    }

    @Provides
    fun pipelineNotifier(context: Context): PipelineNotifier =
        AndroidPipelineNotifier(context)

    @PebbleScope
    @Provides
    fun inputProcessor(
        inputQueue: InputQueue,
        pipeline: AssimilationPipeline,
        notifier: PipelineNotifier
    ): InputProcessor = InputProcessor(inputQueue, pipeline, notifier)
}

// ── ViewModel module ──────────────────────────────────────────────────────────

@Module
object PebbleViewModelModule {

    @Provides
    fun factory(
        providers: Map<Class<out ViewModel>, @JvmSuppressWildcards Provider<ViewModel>>
    ): ViewModelProvider.Factory = PebbleViewModelFactory(providers)

    @Provides
    @IntoMap
    @ViewModelKey(PebbleDashboardViewModel::class)
    fun dashboardVm(
        webhookServer: WebhookServer,
        changeStore: ChangeStore,
        inputQueue: InputQueue
    ): ViewModel = PebbleDashboardViewModel(webhookServer, changeStore, inputQueue)

    @Provides
    @IntoMap
    @ViewModelKey(InputHistoryViewModel::class)
    fun inputHistoryVm(inputQueue: InputQueue): ViewModel = InputHistoryViewModel(inputQueue)

    @Provides
    @IntoMap
    @ViewModelKey(ApprovalViewModel::class)
    fun approvalVm(
        changeStore: ChangeStore,
        changeExecutor: ChangeExecutor,
        disambiguationResolver: DisambiguationResolver,
        feedbackStore: ResolutionFeedbackStore
    ): ViewModel = ApprovalViewModel(changeStore, changeExecutor, disambiguationResolver, feedbackStore)

    @Provides
    @IntoMap
    @ViewModelKey(ChangeLogViewModel::class)
    fun changeLogVm(
        changeStore: ChangeStore,
        changeRollback: ChangeRollback
    ): ViewModel = ChangeLogViewModel(changeStore, changeRollback)

    @Provides
    @IntoMap
    @ViewModelKey(ChangeSetDetailViewModel::class)
    fun changeSetDetailVm(changeStore: ChangeStore): ViewModel = ChangeSetDetailViewModel(changeStore)

    @Provides
    @IntoMap
    @ViewModelKey(PebbleSettingsViewModel::class)
    fun settingsVm(repo: PebbleSettingsRepository): ViewModel = PebbleSettingsViewModel(repo)

    @Provides
    @IntoMap
    @ViewModelKey(WebhookQrViewModel::class)
    fun qrVm(repo: PebbleSettingsRepository): ViewModel = WebhookQrViewModel(repo)

    @Provides
    @IntoMap
    @ViewModelKey(PebbleDebugViewModel::class)
    fun debugVm(
        eventStore: PipelineEventStore,
        webhookServer: WebhookServer,
        inputQueue: InputQueue
    ): ViewModel = PebbleDebugViewModel(eventStore, webhookServer, inputQueue)

    @Provides
    @IntoMap
    @ViewModelKey(ManualInputViewModel::class)
    fun manualInputVm(inputQueue: InputQueue): ViewModel = ManualInputViewModel(inputQueue)
}

// ── Component ─────────────────────────────────────────────────────────────────

@PebbleScope
@Component(
    dependencies = [PebbleDependencies::class],
    modules = [PebbleInfraModule::class, PebbleViewModelModule::class]
)
interface PebbleComponent {

    @Component.Factory
    interface Factory {
        fun create(dependencies: PebbleDependencies): PebbleComponent
    }

    fun pebbleGraphService(): PebbleGraphService
    fun pebbleSearchService(): PebbleSearchService
    fun webhookServer(): WebhookServer
    fun webhookConfig(): WebhookConfig
    fun inputProcessor(): InputProcessor
    fun settingsRepository(): PebbleSettingsRepository

    // Fragment inject targets
    fun inject(fragment: PebbleDashboardFragment)
    fun inject(fragment: InputHistoryFragment)
    fun inject(fragment: ApprovalFragment)
    fun inject(fragment: ChangeLogFragment)
    fun inject(fragment: ChangeSetDetailFragment)
    fun inject(fragment: PebbleSettingsFragment)
    fun inject(fragment: WebhookQrFragment)
    fun inject(fragment: PebbleDebugFragment)
    fun inject(fragment: ManualInputFragment)
}

// endregion
