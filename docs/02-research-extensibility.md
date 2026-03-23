# Research: Extensibility & Isolation

## Executive Summary

The AnyType Android client uses a centralized Dagger 2 DI graph, a monolithic navigation XML, and a pattern where feature libraries are pure presentation/UI modules while all wiring lives in the `app` module. Adding our new services (webhook, assimilation, change control, UI) requires touching `MainComponent`, `ComponentManager`, `ComponentDependenciesModule`, and `graph.xml` — there is no plugin architecture that avoids this. The recommended approach is a **multi-module strategy** with a **thin adapter facade** module isolating our code from AnyType internals, a **dedicated Dagger component hierarchy** bridged through a single `PebbleDependencies` interface, and a **Ktor CIO embedded server** running in an Android Foreground Service for webhook reception. This design concentrates all AnyType touch points in ~30 lines across 4 existing files, enabling clean merges with upstream.

## Context & Constraints

### Hard Constraints
- AnyType is a 30+ module Gradle project using Dagger 2 with `MainComponent` as the singleton root.
- Feature modules are **library** modules with no DI awareness; all Dagger wiring lives in `app`.
- Navigation uses a single `NavHostFragment` with `graph.xml` + nested included graphs.
- No existing WorkManager, ForegroundService, or webhook infrastructure.
- Middleware is an opaque Go binary accessed via JNI (`service.Service`) — all persistence and sync handled there.
- The app must remain offline-first; our additions must not require network connectivity for core function.
- Our fork must stay mergeable with upstream AnyType releases.

### Codebase Evidence

**DI Registration Pattern** (from `MainComponent.kt`, 489 lines):
- `MainComponent` is a `@Singleton @Component` implementing ~50 `*Dependencies` interfaces.
- `ComponentDependenciesModule` (lines 191–487) binds each `*Dependencies` into a `Map<Class, ComponentDependencies>`.
- `ComponentManager` (1333 lines) manually constructs feature-scoped components via `DaggerXxxComponent.builder().withDependencies(findComponentDependencies()).build()`.

**Navigation Pattern** (from `graph.xml` + `MainActivity`):
- `graph.xml` in `app/src/main/res/navigation/` with `startDestination="splashScreen"`, nested graphs, and `<include>` for sub-graphs.
- No deep link XML entries — deep links are resolved imperatively via `DefaultDeepLinkResolver` in `MainActivity`.
- Feature screens are `<fragment>` destinations backed by `app`-hosted Fragment shells.

**Background Infrastructure**:
- Only `AnytypePushService` (FCM) exists as an Android Service.
- `AppStateService` is a `DefaultLifecycleObserver`, not an Android Service.
- Background work uses `DEFAULT_APP_COROUTINE_SCOPE` (`SupervisorJob() + Dispatchers.Default`).

## Options Analysis

### 1. Module Strategy

#### Option A: Single `feature-pebble` Module
All new code (webhook, assimilation, change control, UI) in one library module.

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Simplest setup; one Gradle module to add; internal classes share a single DI scope. |
| **Cons** | Violates single-responsibility; all code coupled internally; rebuilds the entire module on any change; harder to test components in isolation. |
| **Build time** | Moderate — one module, but growing. |
| **Merge risk** | Low (one module to add) but internal complexity accumulates. |
| **Implementation cost** | Low initial, high long-term. |

#### Option B: Multiple Feature Modules (Recommended)
Split into 4+ modules with clear responsibilities:

```
pebble-core/          — Adapter facade, shared models, taxonomy, interfaces
pebble-webhook/       — HTTP server, input queue, raw input persistence
pebble-assimilation/  — NLP pipeline, entity resolution, plan generation
pebble-changecontrol/ — Change sets, rollback, approval state machine
feature-pebble-ui/    — Compose screens for history, approval, rollback, settings
```

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Clear separation of concerns; independent testing; parallel development; each module has a focused API surface. |
| **Cons** | More Gradle config; more DI wiring; inter-module API design needed. |
| **Build time** | Better incrementally — changing assimilation doesn't rebuild webhook. |
| **Merge risk** | Same as Option A for the `app` touch points; more modules in `settings.gradle` but those merge trivially. |
| **Implementation cost** | Moderate initial, low long-term. |

#### Option C: Single Module + Internal Packages
One module with package-level separation. A middle ground.

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Simpler than Option B; better than Option A for organization. |
| **Cons** | No compile-time enforcement of boundaries; easy to create internal coupling. |

**Recommendation: Option B.** The multi-module approach matches AnyType's existing pattern (`feature-chats`, `feature-object-type`, etc.) and provides compile-time boundary enforcement. The adapter facade (`pebble-core`) is the critical module that isolates downstream modules from AnyType API changes.

### 2. Isolation Interface Design

#### Option A: Direct Dependency on Domain Use Cases
Our modules depend directly on `domain` and use `BlockRepository`, `SearchObjects`, `CreateObject`, `SetObjectDetails`, etc.

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Zero abstraction overhead; full access to all capabilities; matches how existing feature modules work. |
| **Cons** | Tightly coupled to AnyType's internal API surface — any upstream rename/refactor breaks our code; our modules need many transitive dependencies. |
| **Merge risk** | High — upstream refactors ripple into our modules. |

#### Option B: Adapter Facade Module (Recommended)
A `pebble-core` module exposes a **stable contract** (`PebbleGraphService`, `PebbleSearchService`, etc.) wrapping AnyType use cases. Our downstream modules depend only on `pebble-core` interfaces.

```kotlin
// pebble-core/src/main/java/.../PebbleGraphService.kt
interface PebbleGraphService {
    suspend fun createObject(space: SpaceId, typeKey: TypeKey, details: Map<String, Any?>): ObjectResult
    suspend fun updateObjectDetails(objectId: Id, details: Map<String, Any?>): Unit
    suspend fun addRelation(objectId: Id, relationKey: String): Unit
    suspend fun deleteObjects(objectIds: List<Id>): Unit
    suspend fun searchObjects(space: SpaceId, filters: List<SearchFilter>, fulltext: String = ""): List<PebbleObject>
    suspend fun getObject(objectId: Id, keys: List<String>): PebbleObject?
}
```

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Single point of coupling to AnyType APIs; our 3 downstream modules never import AnyType domain classes directly; upstream refactors only require updating the adapter implementation, not all our modules. |
| **Cons** | Indirection layer to maintain; must evolve as we need new AnyType capabilities. |
| **Merge risk** | Low — adapter absorbs API drift. Only `pebble-core`'s implementation class changes on upstream refactors. |
| **Implementation cost** | Moderate. A well-scoped facade is ~200-400 lines initially. |

#### Option C: Plugin / Service Discovery Architecture
Full plugin system with `ServiceLoader` or similar.

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Maximum decoupling; could theoretically work without forking at all. |
| **Cons** | Massive overengineering for this use case; AnyType has no plugin API; would need to build the entire plugin infrastructure. |
| **Implementation cost** | Very high. |

**Recommendation: Option B.** The adapter facade gives us the key benefit — isolating our modules from AnyType internals — without the overhead of a plugin system. The facade maps 1:1 to our needs and can grow incrementally.

### 3. DI Integration

#### Option A: Full Dagger Integration (Follow Existing Pattern)
Add our `PebbleDependencies` interface to `MainComponent`, binding in `ComponentDependenciesModule`, and `ComponentManager`.

**Changes to existing files:**

| File | Changes | Lines |
|------|---------|-------|
| `MainComponent.kt` | Add `PebbleDependencies` to interface list; expose adapter provider | ~3 lines |
| `ComponentDependenciesModule` | Add `@Binds @IntoMap` for `PebbleDependencies` | ~6 lines |
| `ComponentManager.kt` | Add `pebbleComponent` property | ~8 lines |
| `settings.gradle` | Add `include` lines for new modules | ~5 lines |
| `app/build.gradle` | Add `implementation project` dependencies | ~5 lines |

Total: **~27 lines across 5 files** in existing AnyType code.

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Consistent with existing patterns; all DI in one graph; dependency resolution at compile time. |
| **Cons** | Touches core DI files (merge risk, though small). |

#### Option B: Separate Dagger Component with Bridge
Create a standalone `DaggerPebbleRootComponent` that receives a `PebbleBridge` interface (provided by `MainComponent`) containing only the dependencies we need.

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Even fewer touches to MainComponent (just expose PebbleBridge methods). |
| **Cons** | Two Dagger graphs to manage; testing setup more complex; not the established pattern. |

#### Option C: Use Koin for Pebble Modules
Use Koin (lightweight DI) for our modules, bridging to Dagger only at the boundaries.

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Zero changes to Dagger files; simpler DI syntax. |
| **Cons** | Two DI frameworks in one app; runtime resolution instead of compile-time; bridge complexity. |

**Recommendation: Option A** with a minimal `PebbleDependencies` interface. The changes are small, predictable, and merge-friendly. The key insight is that `PebbleDependencies` should expose only the adapter facade instances (from `pebble-core`), not raw AnyType use cases — this keeps the interface small and stable.

```kotlin
interface PebbleDependencies : ComponentDependencies {
    fun pebbleGraphService(): PebbleGraphService
    fun pebbleSearchService(): PebbleSearchService
    fun pebbleEventChannel(): PebbleEventChannel
    fun coroutineScope(): CoroutineScope
    fun dispatchers(): AppCoroutineDispatchers
    fun spaceManager(): SpaceManager
}
```

### 4. UI Integration

#### Option A: Destinations in Main `graph.xml`
Add `<fragment>` and `<dialog>` destinations for our screens directly to `graph.xml`.

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Simple; follows existing pattern; full nav action support. |
| **Cons** | Modifies core navigation file (merge risk on every upstream nav change). |

#### Option B: Separate Navigation Graph with `<include>` (Recommended)
Create `nav_pebble.xml` with all our destinations, included into `graph.xml` via `<include>`.

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Only 1 line added to `graph.xml` (`<include app:graph="@navigation/nav_pebble" />`); all our nav definitions isolated in our own file; cleanly separable. |
| **Cons** | Cross-graph navigation requires global action IDs or deep links; slightly more complex nav action setup. |
| **Merge risk** | Very low — single include line. |

#### Option C: Dedicated Activity
Launch a separate `PebbleActivity` for all our screens.

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Zero changes to existing navigation. |
| **Cons** | Loses shared navigation context; feels like a separate app; poor UX for integrated features. |

#### Option D: Bottom Sheet / Dialog Overlay
Use persistent bottom sheets or modal dialogs launched from existing screens.

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Non-intrusive; no navigation graph changes. |
| **Cons** | Poor UX for complex screens (approval review, change history); limited screen real estate. |

**Recommendation: Option B.** A separate `nav_pebble.xml` included into the main graph gives us full navigation capabilities with minimal merge risk. Entry points from existing screens (e.g., a floating action button on the home screen or a notification tap) can use explicit `NavController.navigate()` calls.

### 5. Background Service Architecture

#### Option A: Ktor CIO Embedded Server in Foreground Service (Recommended)
Run a lightweight Ktor HTTP server inside an Android `ForegroundService` with a persistent notification.

| Aspect | Assessment |
|--------|-----------|
| **Pros** | True HTTP endpoint (Pebble app can POST directly); Ktor CIO is coroutine-native, lightweight; foreground service ensures OS won't kill it; established pattern for Android embedded servers. |
| **Cons** | Persistent notification required (Android O+); battery impact (though CIO is idle when no requests); port conflicts possible. |
| **Dependencies** | `io.ktor:ktor-server-cio`, `io.ktor:ktor-server-core` (~1MB footprint). |
| **Integration** | Works with `AppStateService` — can reduce polling/listening when backgrounded if desired. |

Architecture:
```
Pebble App → HTTP POST → Ktor Server (ForegroundService)
                              ↓
                         Input Queue (in-memory + persisted)
                              ↓
                         Assimilation Engine (coroutine)
                              ↓
                         Change Control → AnyType Middleware
```

#### Option B: File-Based / ContentProvider Approach
Pebble app writes transcribed text to shared storage or a ContentProvider; our app observes changes.

| Aspect | Assessment |
|--------|-----------|
| **Pros** | No HTTP server needed; simple. |
| **Cons** | Requires Pebble app modification; file observation is unreliable on modern Android (scoped storage restrictions); not a webhook pattern. |

#### Option C: WorkManager + Polling
Periodically poll for new inputs from an external source.

| Aspect | Assessment |
|--------|-----------|
| **Pros** | WorkManager handles scheduling and constraints well. |
| **Cons** | Not real-time; introduces latency; doesn't match webhook pattern; still needs the external source to store inputs somewhere. |

#### Option D: Local Broadcast / Shared Preferences
Pebble app sends a local broadcast or writes to shared preferences.

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Lightweight. |
| **Cons** | Security concerns; no HTTP webhook compatibility; fragile inter-app communication. |

**Recommendation: Option A.** Ktor CIO embedded server in a Foreground Service. This provides a proper HTTP webhook endpoint that the Pebble phone app can POST to directly. The Ktor CIO engine is specifically designed for resource-constrained environments, is coroutine-native (matching AnyType's async patterns), and the foreground service ensures reliable background operation.

**Lifecycle integration:** The foreground service starts on app launch (or on-demand) and registers with `ProcessLifecycleOwner` similar to `AppStateService`. The notification gives users visibility into the webhook listener status. A toggle in settings allows disabling the listener.

### 6. Merge Strategy

#### Recommended Git Workflow

1. **Rebase-on-upstream strategy**: Maintain our `main` branch. Periodically rebase onto upstream's `main` (or `develop`). Conflicts will be concentrated in the ~5 files we modify.

2. **Delineated change markers**: In files we must modify (`MainComponent.kt`, `ComponentManager.kt`, `graph.xml`, `settings.gradle`, `app/build.gradle`), bracket our additions with comments:
   ```kotlin
   // region Pebble PKM Integration
   ...
   // endregion Pebble PKM Integration
   ```

3. **Automated conflict detection**: A CI check that diffs our fork against upstream on every upstream release, flagging which of our ~5 touch-point files have upstream changes.

4. **Module isolation**: All other code lives in our own modules — zero merge conflict risk for those files.

**Touch-Point Inventory** (complete list of existing files we modify):

| File | Change | Merge Risk |
|------|--------|------------|
| `settings.gradle` | 5 `include` lines | Trivial (append) |
| `app/build.gradle` | 5 `implementation` lines | Low (append to list) |
| `MainComponent.kt` | ~3 lines (interface list + expose) | Medium (interface list changes frequently upstream) |
| `ComponentDependenciesModule` | ~6 lines (binding) | Low (append to end) |
| `ComponentManager.kt` | ~8 lines (new property) | Low (append) |
| `graph.xml` | 1 `<include>` line | Low |
| `AndroidManifest.xml` | ~5 lines (foreground service) | Low |

**Total: ~33 lines across 7 files.**

## Recommended Approach

### Module Architecture

```
anytype-kotlin-pkm/
├── pebble-core/                    # Adapter facade + shared models
│   ├── PebbleGraphService.kt       # Wraps AnyType CRUD operations
│   ├── PebbleSearchService.kt      # Wraps AnyType search
│   ├── PebbleEventChannel.kt       # Wraps AnyType events
│   ├── PebbleObject.kt             # Our object model (maps to/from ObjectWrapper)
│   ├── PebbleTaxonomy.kt           # Taxonomy definitions
│   └── impl/                       # Implementations backed by AnyType use cases
│
├── pebble-webhook/                 # HTTP server + input queue
│   ├── WebhookServer.kt            # Ktor CIO server
│   ├── WebhookService.kt           # Android ForegroundService
│   ├── InputQueue.kt               # In-memory + persistent queue
│   └── RawInput.kt                 # Raw transcribed text model
│
├── pebble-assimilation/            # NLP + entity resolution
│   ├── AssimilationEngine.kt       # Pipeline orchestrator
│   ├── EntityExtractor.kt          # NLP/LLM extraction
│   ├── EntityResolver.kt           # Graph-based entity matching
│   └── PlanGenerator.kt            # Produces change plans
│
├── pebble-changecontrol/           # Change sets + rollback
│   ├── ChangeSet.kt                # Change set data model
│   ├── ChangeExecutor.kt           # Applies changes via PebbleGraphService
│   ├── ChangeRollback.kt           # Reverses changes
│   └── ChangeStore.kt              # Persists change history
│
└── feature-pebble-ui/              # Compose UI screens
    ├── InputHistoryScreen.kt
    ├── ApprovalScreen.kt
    ├── ChangeLogScreen.kt
    └── PebbleSettingsScreen.kt
```

### Dependency Flow

```
feature-pebble-ui → pebble-changecontrol → pebble-core → domain, core-models
                   → pebble-assimilation → pebble-core
                   → pebble-webhook      → pebble-core
```

### DI Contract (Single Bridge)

```kotlin
// In app/.../di/feature/pebble/PebbleDI.kt (new file, ~80 lines)
interface PebbleDependencies : ComponentDependencies {
    fun pebbleGraphService(): PebbleGraphService
    fun pebbleSearchService(): PebbleSearchService
    fun coroutineScope(): CoroutineScope
    fun dispatchers(): AppCoroutineDispatchers
    fun spaceManager(): SpaceManager
    fun storeOfRelations(): StoreOfRelations
    fun storeOfObjectTypes(): StoreOfObjectTypes
}
```

### Validation Criteria

| Criterion | Target | How to Measure |
|-----------|--------|---------------|
| Existing file changes | ≤ 35 lines across ≤ 7 files | `git diff --stat` against upstream |
| Module count | 5 new modules | `settings.gradle` entries |
| DI surface | 1 `*Dependencies` interface | Grep for `PebbleDependencies` |
| Build time impact | < 10% increase | Gradle profile comparison |
| Upstream merge | Resolve conflicts in < 30 min | Simulated merge against upstream HEAD |

## Open Questions

1. **Ktor version compatibility**: Need to verify Ktor CIO works with the project's Kotlin 2.2.10 and minimum Android SDK version.
2. **Port selection for webhook server**: How to handle port conflicts? Auto-discovery, configurable, or fixed?
3. **Pebble app integration**: Does the Pebble phone app support configurable webhook URLs, or do we need to use a specific port/path convention?
4. **Multi-space support**: Our `PebbleDependencies` exposes `SpaceManager` — should the webhook service support routing inputs to different spaces?
5. **Proguard/R8**: Do our new modules need specific keep rules for the Ktor server or our models?

## References

- AnyType `MainComponent.kt` (489 lines) — central DI graph
- AnyType `ComponentManager.kt` (1333 lines) — component lifecycle
- AnyType `ComponentDependencies.kt` — map-key pattern
- AnyType `graph.xml` — navigation graph
- AnyType `feature-chats/` — reference feature module pattern
- [Ktor CIO Embedded Server on Android](https://blog.azzahid.com/posts/android-ktor-http-server/) — lightweight HTTP server pattern
- [Google Guide to App Architecture](https://developer.android.com/topic/architecture) — modularization best practices
- [Now in Android](https://github.com/android/nowinandroid) — modular architecture reference
