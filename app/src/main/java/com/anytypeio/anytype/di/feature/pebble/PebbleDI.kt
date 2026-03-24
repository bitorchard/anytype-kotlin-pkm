package com.anytypeio.anytype.di.feature.pebble

import com.anytypeio.anytype.di.common.ComponentDependencies
import com.anytypeio.anytype.domain.base.AppCoroutineDispatchers
import com.anytypeio.anytype.domain.objects.StoreOfObjectTypes
import com.anytypeio.anytype.domain.objects.StoreOfRelations
import com.anytypeio.anytype.domain.workspace.SpaceManager
import com.anytypeio.anytype.pebble.core.PebbleGraphService
import com.anytypeio.anytype.pebble.core.PebbleSearchService
import com.anytypeio.anytype.pebble.core.impl.DefaultPebbleGraphService
import com.anytypeio.anytype.pebble.core.impl.DefaultPebbleSearchService
import dagger.Binds
import dagger.Component
import dagger.Module
import javax.inject.Singleton

// region Pebble PKM Integration

/**
 * Dependencies surface that [com.anytypeio.anytype.di.main.MainComponent] exposes to any Pebble
 * feature component. All provision methods are satisfied by the main Dagger singleton graph;
 * [PebbleModule] must be added to MainComponent's modules list to supply the adapter bindings.
 */
interface PebbleDependencies : ComponentDependencies {
    fun pebbleGraphService(): PebbleGraphService
    fun pebbleSearchService(): PebbleSearchService
    fun appCoroutineDispatchers(): AppCoroutineDispatchers
    fun spaceManager(): SpaceManager
    fun storeOfObjectTypes(): StoreOfObjectTypes
    fun storeOfRelations(): StoreOfRelations
}

/**
 * Dagger module providing the Pebble adapter implementations as application-scoped singletons.
 * Added to [com.anytypeio.anytype.di.main.MainComponent] modules list; NOT used in
 * [PebbleComponent] itself (the services flow through [PebbleDependencies] provision methods).
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

/**
 * Feature component for the Pebble PKM integration. Unscoped — it acts as a typed pass-through
 * that exposes the services already held in the [Singleton]-scoped MainComponent, and will gain
 * inject() methods for Pebble fragments in Phase 5.
 */
@Component(dependencies = [PebbleDependencies::class])
interface PebbleComponent {

    @Component.Factory
    interface Factory {
        fun create(dependencies: PebbleDependencies): PebbleComponent
    }

    fun pebbleGraphService(): PebbleGraphService
    fun pebbleSearchService(): PebbleSearchService
}

// endregion
