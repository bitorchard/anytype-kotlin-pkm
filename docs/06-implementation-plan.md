# Implementation Plan: AnyType Pebble Ring PKM Assimilation

## Overview

This document is the detailed implementation plan for the Pebble Ring PKM assimilation pipeline, synthesized from research artifacts 02–05. It defines concrete phases, tasks, acceptance criteria, and dependencies that a coding agent can pick up and execute iteratively.

### Key Architectural Decisions (from Research)

| Decision | Choice | Source |
|----------|--------|--------|
| Module strategy | 5 new Gradle modules (`pebble-core`, `pebble-webhook`, `pebble-assimilation`, `pebble-changecontrol`, `feature-pebble-ui`) | 02-extensibility |
| Isolation interface | Adapter facade in `pebble-core` wrapping AnyType use cases | 02-extensibility |
| DI integration | Single `PebbleDependencies` interface added to existing Dagger graph (~33 lines across 7 files) | 02-extensibility |
| UI integration | Separate `nav_pebble.xml` included into main `graph.xml` | 02-extensibility |
| Background service | Ktor CIO embedded HTTP server in Android Foreground Service | 02-extensibility |
| Change model | Operation log (oplog) with pre-computed inverse operations | 03-change-control |
| Change granularity | Per-input (one change set per voice note) | 03-change-control |
| Change storage | AnyType-native objects + Room cache for fast queries | 03-change-control |
| Rollback strategy | Compensating transactions (new forward changes), compatible with CRDT | 03-change-control |
| NLP pipeline | Hybrid: Cloud LLM (Claude/GPT-4) extraction + deterministic Kotlin post-processing | 04-data-assimilation |
| Entity resolution | Multi-signal scoring (name 0.35, type 0.15, proximity 0.20, recency 0.15, frequency 0.10, attributes 0.05) | 04-data-assimilation |
| Taxonomy representation | Kotlin sealed hierarchy (code-defined core) + AnyType runtime extension | 05-taxonomy |
| Taxonomy evolution | Versioned, additive-only migrations applied on space open | 05-taxonomy |

### Dependency Flow Between Modules

```
feature-pebble-ui → pebble-changecontrol → pebble-core → domain, core-models
                   → pebble-assimilation  → pebble-core
                   → pebble-webhook       → pebble-core
```

---

## Phase 0: Project Scaffolding & DI Bridge

**Goal:** Establish all 5 modules with Gradle configuration, wire into AnyType's DI graph, and verify the build compiles with zero functional code.

**Rationale:** This is the highest-risk, lowest-effort step. Getting the module structure and DI bridge right first means all subsequent work has a stable foundation. If this can't merge cleanly, nothing else matters.

### Task 0.1: Create Gradle Modules

Create the 5 module directories with `build.gradle.kts` files, following existing feature module patterns (e.g., `feature-chats/build.gradle.kts`).

| Module | Type | Key Dependencies |
|--------|------|-----------------|
| `pebble-core` | Android Library | `domain`, `core-models`, `core-utils` |
| `pebble-webhook` | Android Library | `pebble-core`, `io.ktor:ktor-server-cio`, `io.ktor:ktor-server-core`, `io.ktor:ktor-server-content-negotiation`, `io.ktor:ktor-serialization-kotlinx-json` |
| `pebble-assimilation` | Android Library | `pebble-core` |
| `pebble-changecontrol` | Android Library | `pebble-core`, `androidx.room:room-runtime`, `androidx.room:room-ktx` |
| `feature-pebble-ui` | Android Library | `pebble-core`, `pebble-webhook`, `pebble-assimilation`, `pebble-changecontrol`, `core-ui`, Compose dependencies |

**Files to create:**
- `pebble-core/build.gradle.kts`
- `pebble-core/src/main/AndroidManifest.xml` (minimal)
- `pebble-webhook/build.gradle.kts`
- `pebble-webhook/src/main/AndroidManifest.xml`
- `pebble-assimilation/build.gradle.kts`
- `pebble-assimilation/src/main/AndroidManifest.xml`
- `pebble-changecontrol/build.gradle.kts`
- `pebble-changecontrol/src/main/AndroidManifest.xml`
- `feature-pebble-ui/build.gradle.kts`
- `feature-pebble-ui/src/main/AndroidManifest.xml`

**Files to modify:**
- `settings.gradle` — add 5 `include` lines
- `app/build.gradle` — add 5 `implementation project(...)` lines

**Acceptance criteria:**
- [ ] `./gradlew assembleDebug` succeeds with all 5 new modules
- [ ] Each module produces a `.aar` artifact
- [ ] Zero changes to any existing `.kt` or `.java` files

### Task 0.2: Define Adapter Facade Interfaces

Create the core interfaces in `pebble-core` that isolate downstream modules from AnyType internals.

**Files to create in `pebble-core/src/main/java/com/anytypeio/anytype/pebble/core/`:**

```
PebbleGraphService.kt       — CRUD operations facade
PebbleSearchService.kt      — Search operations facade
PebbleEventChannel.kt       — Event observation facade
PebbleObject.kt             — Our object model (simplified view of AnyType objects)
PebbleTypes.kt              — Type aliases and shared constants (SpaceId, Id, etc.)
```

**`PebbleGraphService` interface:**
```kotlin
interface PebbleGraphService {
    suspend fun createObject(space: SpaceId, typeKey: TypeKey, details: Map<String, Any?>): PebbleObjectResult
    suspend fun updateObjectDetails(objectId: Id, details: Map<String, Any?>)
    suspend fun addRelationToObject(objectId: Id, relationKey: String)
    suspend fun setRelationValue(objectId: Id, relationKey: String, value: Any?)
    suspend fun deleteObjects(objectIds: List<Id>)
    suspend fun getObject(objectId: Id, keys: List<String> = emptyList()): PebbleObject?
    suspend fun createObjectType(space: SpaceId, name: String, uniqueKey: String, layout: Int): PebbleObjectResult
    suspend fun createRelation(space: SpaceId, name: String, uniqueKey: String, format: Int): PebbleObjectResult
}
```

**`PebbleSearchService` interface:**
```kotlin
interface PebbleSearchService {
    suspend fun searchObjects(
        space: SpaceId,
        filters: List<PebbleSearchFilter>,
        sorts: List<PebbleSearchSort> = emptyList(),
        fulltext: String = "",
        keys: List<String> = emptyList(),
        offset: Int = 0,
        limit: Int = 100
    ): List<PebbleObject>
    
    suspend fun searchWithMeta(
        space: SpaceId,
        filters: List<PebbleSearchFilter>,
        fulltext: String = "",
        keys: List<String> = emptyList(),
        limit: Int = 100
    ): List<PebbleSearchResult>
}
```

**`PebbleObject` model:**
```kotlin
data class PebbleObject(
    val id: Id,
    val name: String,
    val typeKey: String,
    val details: Map<String, Any?>,
    val lastModifiedDate: Long?
)
```

**Acceptance criteria:**
- [ ] Interfaces compile against `domain` and `core-models` types
- [ ] No implementation yet — only contracts
- [ ] `PebbleObject` provides a clean mapping layer over `ObjectWrapper`

### Task 0.3: Implement Adapter Facade

Create the implementation classes in `pebble-core` that bridge to AnyType use cases.

**Files to create in `pebble-core/src/main/java/com/anytypeio/anytype/pebble/core/impl/`:**

```
DefaultPebbleGraphService.kt   — Delegates to CreateObject, SetObjectDetails, etc.
DefaultPebbleSearchService.kt  — Delegates to SearchObjects, SearchWithMeta
PebbleObjectMapper.kt          — Maps ObjectWrapper ↔ PebbleObject
PebbleFilterMapper.kt          — Maps PebbleSearchFilter → DVFilter
```

Each implementation class takes AnyType use cases as constructor dependencies (injected via Dagger).

**`DefaultPebbleGraphService` dependencies:**
- `CreateObject` use case
- `SetObjectDetails` use case  
- `AddRelationToObject` use case (or equivalent from `BlockRepository`)
- `DeleteObjects` use case
- `GetObject` use case
- `BlockRepository` (for low-level operations not exposed as use cases)

**Acceptance criteria:**
- [ ] `DefaultPebbleGraphService` can create an object, set details, and delete it (tested via unit test with mocked use cases)
- [ ] `DefaultPebbleSearchService` can search with filters (unit test)
- [ ] `PebbleObjectMapper` correctly maps `ObjectWrapper.Basic` → `PebbleObject` and back

### Task 0.4: DI Bridge — Connect to AnyType's Dagger Graph

Modify AnyType's existing DI files to expose `PebbleDependencies` and wire our adapter implementations.

**Files to modify (existing AnyType code):**

| File | Change |
|------|--------|
| `app/.../di/main/MainComponent.kt` | Add `PebbleDependencies` to the implemented interfaces list; expose `pebbleGraphService()` and `pebbleSearchService()` providers |
| `app/.../di/main/MainComponentModule.kt` or `ComponentDependenciesModule` | Add `@Binds @IntoMap @ComponentDependenciesKey(PebbleDependencies::class)` binding |
| `app/.../di/common/ComponentManager.kt` | Add `pebbleComponent` lazy property following existing pattern |

**Files to create (new DI wiring in `app` module):**

```
app/.../di/feature/pebble/PebbleDependencies.kt      — Dependencies interface
app/.../di/feature/pebble/PebbleModule.kt             — @Module providing adapter implementations
app/.../di/feature/pebble/PebbleSubcomponent.kt       — @Subcomponent for pebble feature scope
```

**`PebbleDependencies` interface:**
```kotlin
interface PebbleDependencies : ComponentDependencies {
    fun pebbleGraphService(): PebbleGraphService
    fun pebbleSearchService(): PebbleSearchService
    fun coroutineScope(): CoroutineScope
    fun appCoroutineDispatchers(): AppCoroutineDispatchers
    fun spaceManager(): SpaceManager
    fun storeOfObjectTypes(): StoreOfObjectTypes
    fun storeOfRelations(): StoreOfRelations
}
```

All modifications to existing files MUST be bracketed with `// region Pebble PKM Integration` / `// endregion`.

**Acceptance criteria:**
- [ ] `./gradlew assembleDebug` succeeds
- [ ] `PebbleDependencies` is resolvable from `ComponentManager`
- [ ] Total lines changed in existing files: ≤ 35
- [ ] `git diff --stat` against pre-fork shows changes only in the 7 expected files

### Task 0.5: Verification — End-to-End DI Smoke Test

Write a minimal integration test that resolves `PebbleDependencies` from the DI graph and calls a no-op method on the adapter.

**File to create:**
- `app/src/test/java/.../pebble/PebbleDITest.kt`

**Acceptance criteria:**
- [ ] Test resolves `PebbleDependencies` and obtains a `PebbleGraphService` instance
- [ ] `make test_debug_all` passes (no regressions)

---

## Phase 1: Taxonomy & Schema Bootstrap

**Goal:** Define the PKM taxonomy in code and implement the schema bootstrap/migration system that creates custom types and relations in an AnyType space.

**Depends on:** Phase 0 (pebble-core module and adapter facade)

### Task 1.1: Define Taxonomy Sealed Hierarchy

Create the code-defined taxonomy in `pebble-core`.

**Files to create in `pebble-core/src/main/java/com/anytypeio/anytype/pebble/core/taxonomy/`:**

```
PkmObjectType.kt    — Sealed class hierarchy for object types
PkmRelation.kt      — Sealed class hierarchy for relations
TaxonomyVersion.kt  — Version constants and migration definitions
```

**`PkmObjectType` sealed hierarchy (10 types):**

| Type | `uniqueKey` | `isBuiltIn` | Layout |
|------|-------------|-------------|--------|
| Person | `ot-human` | true | PROFILE |
| Task | `ot-task` | true | TODO |
| Note | `ot-note` | true | NOTE |
| Project | `ot-project` | true | BASIC |
| Bookmark | `ot-bookmark` | true | BOOKMARK |
| Event | `ot-pebble-event` | false | BASIC |
| Reminder | `ot-pebble-reminder` | false | TODO |
| Place | `ot-pebble-place` | false | BASIC |
| Organization | `ot-pebble-org` | false | BASIC |
| Topic | `ot-pebble-topic` | false | BASIC |

Each type includes `requiredRelations` and `optionalRelations` lists referencing `PkmRelation` entries.

**`PkmRelation` sealed hierarchy (10 custom + references to built-in):**

Custom relations use `pebble-` prefix. Built-in relations referenced by their existing key from `Relations.kt`.

**Acceptance criteria:**
- [ ] `PkmObjectType.all()` returns 10 types
- [ ] `PkmRelation.custom()` returns 10 custom relations
- [ ] Each type's `requiredRelations` references valid relation entries
- [ ] Unit test: sealed hierarchy covers all expected types/relations

### Task 1.2: Implement Taxonomy Prompt Generator

Create the function that serializes the taxonomy into an LLM system prompt.

**File to create:**
- `pebble-core/src/main/java/com/anytypeio/anytype/pebble/core/taxonomy/TaxonomyPromptGenerator.kt`

**Behavior:**
- Iterates `PkmObjectType.all()` and `PkmRelation.objectRelations()`.
- Outputs a structured text block describing available types, their fields, and available relationships.
- Includes a `toPromptDescription()` method on each type/relation.
- Inserts the current date for temporal reference.

**Acceptance criteria:**
- [ ] `generateTaxonomyPrompt()` returns a string containing all 10 types and their fields
- [ ] Output is human-readable and LLM-parseable
- [ ] Unit test: prompt contains expected type names and relation names
- [ ] Unit test: adding a new type to the hierarchy automatically includes it in the prompt

### Task 1.3: Implement Taxonomy Provider

Create `TaxonomyProvider` that merges code-defined types with AnyType runtime state.

**File to create:**
- `pebble-core/src/main/java/com/anytypeio/anytype/pebble/core/taxonomy/TaxonomyProvider.kt`

**Behavior:**
- `coreTypes` property returns all code-defined types.
- `effectiveTypes(space)` queries `StoreOfObjectTypes` and maps each core type to its AnyType object (if it exists in the space).
- `effectiveRelations(space)` does the same for relations.
- Returns `EffectiveType` data class with `definition`, `anytypeId`, and `exists` fields.

**Acceptance criteria:**
- [ ] `effectiveTypes()` correctly identifies which types exist in the space and which need creation
- [ ] Unit test with mocked `StoreOfObjectTypes`

### Task 1.4: Implement Schema Bootstrapper & Migration Engine

Create the system that ensures all custom types and relations exist in a space.

**Files to create:**
```
pebble-core/.../taxonomy/SchemaBootstrapper.kt   — Creates missing types/relations
pebble-core/.../taxonomy/TaxonomyMigration.kt    — Migration step definitions
pebble-core/.../taxonomy/MigrationRunner.kt       — Applies pending migrations
```

**`SchemaBootstrapper` behavior:**
1. Call `effectiveTypes(space)` to get current state.
2. For each custom type where `exists == false`, call `pebbleGraphService.createObjectType(...)`.
3. For each custom relation where `exists == false`, call `pebbleGraphService.createRelation(...)`.
4. For each type, ensure recommended relations are set.
5. Store the current taxonomy version in a sentinel object or Room table.

**`MigrationRunner` behavior:**
- On space open, check stored version vs. current `TaxonomyVersion.CURRENT`.
- If behind, iterate `TaxonomyMigration` steps from stored version to current.
- Each step calls the appropriate facade method (create type, create relation, add relation to type).

**Acceptance criteria:**
- [ ] Bootstrap creates all 5 custom types and 10 custom relations in a test space
- [ ] Bootstrap is idempotent — running twice doesn't create duplicates
- [ ] Migration from version N to N+1 applies only the delta
- [ ] Integration test: bootstrap → verify types exist via search → re-bootstrap → verify no duplicates

### Task 1.5: Taxonomy Integration Test

Write a comprehensive test that bootstraps the taxonomy and verifies all types and relations are correctly created.

**Acceptance criteria:**
- [ ] Test creates custom types and relations via mocked `PebbleGraphService`
- [ ] Test verifies correct `uniqueKey`, `layout`, and `format` for each
- [ ] `make test_debug_all` passes

---

## Phase 2: Change Control Layer

**Goal:** Implement the operation log, change set lifecycle, execution engine, and rollback with conflict detection.

**Depends on:** Phase 0 (adapter facade), Phase 1 (taxonomy types for change set storage)

### Task 2.1: Define Change Set Data Model

Create the core data model for change sets and operations.

**Files to create in `pebble-changecontrol/src/main/java/com/anytypeio/anytype/pebble/changecontrol/model/`:**

```
ChangeSet.kt           — ChangeSet, ChangeSetStatus, ChangeSetMetadata
ChangeOperation.kt     — ChangeOperation, OperationType, OperationParams, OperationStatus
RollbackResult.kt      — RollbackResult, ConflictResolution, RollbackOperationResult
ExecutionResult.kt     — ExecutionResult, OperationResult
```

Follow the data model from `03-research-change-control.md`:
- `ChangeSet` with status state machine (PENDING → APPROVED → APPLYING → APPLIED, etc.)
- `ChangeOperation` with ordinal, type, params, inverse, beforeState, afterState, status
- `OperationParams` as sealed class hierarchy (CreateObjectParams, DeleteObjectParams, SetDetailsParams, AddRelationParams, RemoveRelationParams)

**Acceptance criteria:**
- [ ] All data classes compile and are serializable (kotlinx.serialization)
- [ ] `ChangeSetStatus` enum covers all states from research
- [ ] `OperationParams` sealed class covers all 5 operation types
- [ ] Unit test: serialize/deserialize round-trip for ChangeSet with operations

### Task 2.2: Implement Change Store (AnyType-Native + Room Cache)

Create the persistence layer for change sets.

**Files to create in `pebble-changecontrol/src/main/java/com/anytypeio/anytype/pebble/changecontrol/store/`:**

```
ChangeStore.kt            — Interface for change set persistence
AnytypeChangeStore.kt     — Implementation using PebbleGraphService (AnyType objects)
ChangeSetCache.kt         — Room entity for local cache
ChangeSetDao.kt           — Room DAO
PebbleChangeDatabase.kt   — Room database definition
LocalChangeCache.kt       — Room-backed cache implementation
CompositeChangeStore.kt   — Combines AnytypeChangeStore + LocalChangeCache
```

**`ChangeStore` interface:**
```kotlin
interface ChangeStore {
    suspend fun save(changeSet: ChangeSet): Id
    suspend fun updateStatus(changeSetId: Id, status: ChangeSetStatus)
    suspend fun updateOperation(operationId: Id, status: OperationStatus, beforeState: Map<String, Any?>?, afterState: Map<String, Any?>?)
    suspend fun getChangeSet(changeSetId: Id): ChangeSet?
    suspend fun getChangeSets(status: ChangeSetStatus? = null, limit: Int = 50): List<ChangeSet>
    suspend fun getChangeSetForInput(inputId: Id): ChangeSet?
}
```

**Room cache** provides fast local queries without middleware roundtrips. `CompositeChangeStore` writes to both AnyType (primary/synced) and Room (fast read cache).

**Acceptance criteria:**
- [ ] `AnytypeChangeStore` creates ChangeSet as AnyType objects with correct type key and relations
- [ ] `LocalChangeCache` stores/retrieves ChangeSet summaries in Room
- [ ] `CompositeChangeStore` writes to both and reads from Room cache (falling back to AnyType)
- [ ] Unit tests for each store implementation
- [ ] Room migration test

### Task 2.3: Implement Operation Ordering (Topological Sort)

Create the dependency ordering logic for operations within a change set.

**File to create:**
- `pebble-changecontrol/src/main/java/com/anytypeio/anytype/pebble/changecontrol/engine/OperationOrderer.kt`

**Behavior:**
- Build a dependency graph from operation params (CreateObject must precede SetDetails/AddRelation on that object).
- Topological sort produces execution order.
- `reverseOrder()` produces rollback order (reversed).

**Acceptance criteria:**
- [ ] Correctly orders: CreateObject(A) → SetDetails(A) → CreateObject(B) → AddRelation(A→B)
- [ ] Detects circular dependencies and throws (should never happen, but safety net)
- [ ] Unit test with 10+ operations and complex dependencies

### Task 2.4: Implement Change Executor

Create the engine that applies a change set against the AnyType graph.

**File to create:**
- `pebble-changecontrol/src/main/java/com/anytypeio/anytype/pebble/changecontrol/engine/ChangeExecutor.kt`

**Behavior (per research section "Execution Engine"):**
1. Set change set status to `APPLYING`.
2. Iterate operations in topological order.
3. For each: capture `beforeState`, execute via `PebbleGraphService`, capture `afterState`, fill `resultObjectId` for creates.
4. On success: set status to `APPLIED`.
5. On failure: set status to `APPLY_FAILED`, record which operation failed, return `PartialFailure`.

**Inverse computation:**
- `CreateObject → DeleteObject(createdId)`
- `SetDetails → SetDetails(previousValues)`
- `AddRelation → RemoveRelation`
- `DeleteObject → CreateObject(fromBeforeState)` (ID will differ)

**Acceptance criteria:**
- [ ] Executes a 3-operation change set (create person, create event, link them) successfully
- [ ] Correctly fills `resultObjectId` on create operations and updates subsequent operations' references
- [ ] Partial failure: if operation 2 of 3 fails, status is `APPLY_FAILED`, operation 1 is `APPLIED`, operations 2-3 are `FAILED`/`PENDING`
- [ ] `beforeState` and `afterState` are correctly captured
- [ ] Unit tests with mocked `PebbleGraphService`

### Task 2.5: Implement Rollback Engine

Create the rollback engine with conflict detection.

**File to create:**
- `pebble-changecontrol/src/main/java/com/anytypeio/anytype/pebble/changecontrol/engine/ChangeRollback.kt`

**Behavior (per research "Conflict During Rollback"):**
1. Set status to `ROLLING_BACK`.
2. Get applied operations in reverse topological order.
3. For each: fetch current state, compare with `afterState` (expected state).
4. If mismatch → conflict detected. Apply resolution strategy (FORCE, SKIP, ABORT).
5. Execute inverse operation.
6. Set final status: `ROLLED_BACK` or `PARTIALLY_ROLLED_BACK`.

**Acceptance criteria:**
- [ ] Clean rollback: apply 3 ops → rollback → all objects deleted/restored
- [ ] Conflict detection: apply → externally modify object → rollback → conflict flagged
- [ ] SKIP strategy: conflicted operation skipped, others rolled back
- [ ] ABORT strategy: rollback stops at first conflict
- [ ] FORCE strategy: overwrite user changes
- [ ] Unit tests for each conflict resolution path

### Task 2.6: Change Control Integration Test

End-to-end test: create a change set, execute it, verify objects exist, rollback, verify objects removed.

**Acceptance criteria:**
- [ ] Full lifecycle: PENDING → APPROVED → APPLIED → ROLLED_BACK
- [ ] All states correctly persisted in store
- [ ] `make test_debug_all` passes

---

## Phase 3: Webhook Service

**Goal:** Implement the Ktor CIO embedded HTTP server in a Foreground Service, with input queuing and persistence.

**Depends on:** Phase 0 (module structure)

### Task 3.1: Define Webhook Data Model

**Files to create in `pebble-webhook/src/main/java/com/anytypeio/anytype/pebble/webhook/model/`:**

```
RawInput.kt              — Raw transcribed text model
InputQueueEntry.kt       — Queue entry with metadata (timestamp, status, retries)
WebhookConfig.kt         — Server configuration (port, auth token, etc.)
```

**`RawInput` model:**
```kotlin
data class RawInput(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val receivedAt: Long = System.currentTimeMillis(),
    val source: String = "pebble",
    val metadata: Map<String, String> = emptyMap()
)
```

**Acceptance criteria:**
- [ ] Models compile and are serializable

### Task 3.2: Implement Input Queue

**Files to create:**
```
pebble-webhook/.../queue/InputQueue.kt           — Interface
pebble-webhook/.../queue/PersistentInputQueue.kt  — Room-backed implementation
pebble-webhook/.../queue/InputQueueDatabase.kt    — Room DB
pebble-webhook/.../queue/InputQueueDao.kt         — Room DAO
```

**Behavior:**
- `enqueue(input: RawInput)` — persists input to Room and notifies listeners.
- `dequeue(): RawInput?` — returns the next unprocessed input.
- `markProcessed(id: String)` — marks input as processed.
- `markFailed(id: String, error: String)` — marks input as failed with retry logic.
- `getPending(): Flow<List<RawInput>>` — observable stream of pending inputs.

Queue survives app restarts via Room persistence.

**Acceptance criteria:**
- [ ] Enqueue → dequeue returns same input
- [ ] Pending flow emits new inputs
- [ ] Failed inputs are retried up to 3 times
- [ ] Queue survives simulated app restart (Room persistence)
- [ ] Unit tests

### Task 3.3: Implement Ktor HTTP Server

**Files to create:**
```
pebble-webhook/.../server/WebhookServer.kt    — Ktor server setup and routes
pebble-webhook/.../server/WebhookRoutes.kt    — Route definitions
pebble-webhook/.../server/WebhookAuth.kt      — Simple auth (bearer token or shared secret)
```

**Routes:**

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/input` | Receive transcribed text |
| GET | `/api/v1/status` | Server health check |
| GET | `/api/v1/inputs` | List recent inputs (debugging) |

**POST `/api/v1/input` request body:**
```json
{
    "text": "Aarav has a basketball game on Friday",
    "source": "pebble",
    "timestamp": 1711152000000,
    "metadata": {}
}
```

**Response:**
```json
{
    "id": "uuid",
    "status": "queued"
}
```

**Server configuration:**
- Default port: 8391 (configurable)
- Ktor CIO engine (coroutine-native, lightweight)
- Content negotiation: `kotlinx.serialization` JSON
- Simple bearer token auth (configured in app settings)

**Acceptance criteria:**
- [ ] Server starts on configured port
- [ ] POST to `/api/v1/input` returns 200 and queues the input
- [ ] GET to `/api/v1/status` returns 200 with server info
- [ ] Invalid auth returns 401
- [ ] Invalid request body returns 400
- [ ] Integration test: start server → POST input → verify in queue

### Task 3.4: Implement Android Foreground Service

**Files to create:**
```
pebble-webhook/.../service/WebhookForegroundService.kt  — Android Service
pebble-webhook/.../service/WebhookNotification.kt       — Notification builder
pebble-webhook/.../service/WebhookServiceManager.kt     — Start/stop helper
```

**Behavior:**
- Extends `Service()`, promotes to foreground with persistent notification.
- Starts Ktor server on `onCreate`, stops on `onDestroy`.
- Notification shows: "Pebble PKM listener active on port {port}".
- Registers with `ProcessLifecycleOwner` for app lifecycle awareness.
- Toggleable from settings and via notification action.

**Files to modify (existing):**
- `app/src/main/AndroidManifest.xml` — add `<service>` declaration and foreground service permission

**Acceptance criteria:**
- [ ] Service starts and shows notification
- [ ] Ktor server is accessible while service runs
- [ ] Service stops cleanly when stopped
- [ ] `AndroidManifest.xml` changes are ≤ 5 lines

### Task 3.5: Pipeline Trigger — Connect Queue to Assimilation

Create the orchestrator that observes the input queue and triggers processing.

**File to create:**
- `pebble-webhook/.../pipeline/InputProcessor.kt`

**Behavior:**
- Collects from `inputQueue.getPending()`.
- For each pending input, invokes the assimilation engine (via interface — implementation in Phase 4).
- On success: marks input as processed and records the resulting change set ID.
- On failure: marks input as failed.
- Respects offline state: if assimilation requires network (LLM), queues until online.

**Interface for decoupling (defined in `pebble-core`):**
```kotlin
interface AssimilationPipeline {
    suspend fun process(input: RawInput, space: SpaceId): AssimilationResult
}
```

**Acceptance criteria:**
- [ ] Processes pending inputs when assimilation pipeline is available
- [ ] Correctly handles offline queueing
- [ ] Unit test with mock pipeline

---

## Phase 4: Assimilation Engine

**Goal:** Implement the LLM-based extraction, entity resolution, and plan generation pipeline.

**Depends on:** Phase 0 (adapter facade), Phase 1 (taxonomy), Phase 2 (change set model)

### Task 4.1: Define Extraction Data Model

**Files to create in `pebble-assimilation/src/main/java/com/anytypeio/anytype/pebble/assimilation/model/`:**

```
ExtractionResult.kt      — LLM output model
ExtractedEntity.kt       — Entity with type, name, attributes, confidence
ExtractedRelationship.kt — Relationship between entities
AssimilationPlan.kt      — Complete plan ready for change control
ScoredCandidate.kt       — Entity resolution candidate with scores
DisambiguationChoice.kt  — Disambiguation UI model
```

**Acceptance criteria:**
- [ ] All models compile and are serializable
- [ ] `AssimilationPlan` contains a list of `ChangeOperation` (from Phase 2 model)

### Task 4.2: Implement LLM Client

**Files to create in `pebble-assimilation/src/main/java/com/anytypeio/anytype/pebble/assimilation/llm/`:**

```
LlmClient.kt               — Interface
AnthropicLlmClient.kt      — Claude API implementation
OpenAiLlmClient.kt         — GPT-4 API implementation
LlmClientConfig.kt         — API key, model selection, timeout config
LlmClientFactory.kt        — Factory based on config
```

**`LlmClient` interface:**
```kotlin
interface LlmClient {
    suspend fun extractEntities(
        systemPrompt: String,
        userInput: String
    ): ExtractionResult
}
```

Each implementation:
- Makes HTTP call to provider API (Ktor client or OkHttp).
- Sends taxonomy prompt as system message + user input.
- Requests structured JSON output (tool use for Anthropic, JSON schema for OpenAI).
- Parses response into `ExtractionResult`.
- Handles rate limits, timeouts, and API errors.

**API key storage:** Android Keystore via `EncryptedSharedPreferences`.

**Acceptance criteria:**
- [ ] `AnthropicLlmClient` sends correctly formatted request and parses response
- [ ] `OpenAiLlmClient` same
- [ ] Error handling: timeout, rate limit, invalid response
- [ ] Unit tests with mock HTTP responses
- [ ] API key stored securely, not in plaintext

### Task 4.3: Implement Entity Extractor

**File to create:**
- `pebble-assimilation/src/main/java/com/anytypeio/anytype/pebble/assimilation/extraction/EntityExtractor.kt`

**Behavior:**
1. Generate taxonomy prompt from `TaxonomyPromptGenerator`.
2. Inject current date and context window (recent entities) into prompt.
3. Call `LlmClient.extractEntities()`.
4. Validate extracted entities against taxonomy (unknown types → fallback to Note + tag).
5. Normalize temporal references (if not handled by LLM).
6. Return validated `ExtractionResult`.

**Acceptance criteria:**
- [ ] Given "Aarav has a basketball game on Friday" → extracts Person(Aarav) + Event(basketball game, date=Friday)
- [ ] Unknown type falls back to Note
- [ ] Low-confidence extractions are flagged
- [ ] Unit test with mocked LLM client

### Task 4.4: Implement Entity Resolver

**Files to create:**
```
pebble-assimilation/.../resolution/EntityResolver.kt     — Main resolver
pebble-assimilation/.../resolution/ScoringEngine.kt      — Multi-signal scoring
pebble-assimilation/.../resolution/NameSimilarity.kt     — String matching utilities
pebble-assimilation/.../resolution/EntityCache.kt        — Hot cache via subscriptions
```

**`EntityResolver` behavior:**
1. For each extracted entity, call `findCandidates()` — search AnyType graph.
2. Score each candidate with `ScoringEngine` (6 signals, configurable weights).
3. Apply confidence thresholds:
   - ≥ 0.85 → auto-resolve
   - 0.50–0.85 → suggest with disambiguation
   - < 0.50 → create new
   - Multiple ≥ 0.70 → force disambiguation

**`ScoringEngine` signals:**

| Signal | Weight | Implementation |
|--------|--------|----------------|
| Name similarity | 0.35 | Levenshtein distance + token overlap |
| Type match | 0.15 | Exact = 1.0, related = 0.5, unrelated = 0.0 |
| Relationship proximity | 0.20 | Graph neighbor check via object relation values |
| Recency | 0.15 | Exponential decay on `lastModifiedDate` |
| Frequency | 0.10 | Count of past resolutions to this object |
| Context attributes | 0.05 | Attribute overlap (role, tags, etc.) |

**`EntityCache`:**
- Live subscriptions for Person objects and recent objects.
- Provides fast candidate retrieval without per-query search latency.

**Acceptance criteria:**
- [ ] Exact name match with correct type scores ≥ 0.85
- [ ] Fuzzy name match ("Arav" → "Aarav") scores in disambiguation range
- [ ] No candidates → create new decision
- [ ] Multiple high-scoring candidates → force disambiguation
- [ ] Scoring weights are configurable
- [ ] Unit tests for each signal independently
- [ ] Integration test for full resolution flow

### Task 4.5: Implement Context Window

**File to create:**
- `pebble-assimilation/src/main/java/com/anytypeio/anytype/pebble/assimilation/context/ContextWindow.kt`

**Behavior:**
- Ring buffer of last N (default: 10) `AssimilationResult` objects.
- Provides `recentEntities()` — entities created or matched in recent inputs.
- Injected into LLM prompt as additional context.
- Injected into `EntityResolver` for boosting recent entities.

**Acceptance criteria:**
- [ ] Sequential inputs share entity context
- [ ] "Aarav" in input 2 resolves to the Person created in input 1
- [ ] Buffer wraps correctly at capacity

### Task 4.6: Implement Plan Generator

**File to create:**
- `pebble-assimilation/src/main/java/com/anytypeio/anytype/pebble/assimilation/plan/PlanGenerator.kt`

**Behavior:**
1. Take `ExtractionResult` + resolution decisions.
2. For each entity that needs creation → `ChangeOperation(CreateObject, ...)`.
3. For each entity attribute → `ChangeOperation(SetDetails, ...)`.
4. For each relationship → `ChangeOperation(AddRelation, ...)` + `ChangeOperation(SetDetails, ...)` to set OBJECT relation value.
5. Wrap in `ChangeSet(status = PENDING, ...)`.
6. Apply topological ordering (from Phase 2).

**Acceptance criteria:**
- [ ] "Aarav has a basketball game on Friday" → ChangeSet with ~5 operations (create Person, set Person details, create Event, set Event details, link them)
- [ ] Existing entity resolution → SetDetails/AddRelation on existing object (no Create)
- [ ] Operations are correctly ordered (creates before details/relations)
- [ ] Unit test with known inputs

### Task 4.7: Implement AssimilationPipeline (Orchestrator)

**File to create:**
- `pebble-assimilation/src/main/java/com/anytypeio/anytype/pebble/assimilation/AssimilationEngine.kt`

**Behavior (full pipeline):**
1. `EntityExtractor.extract(input, contextWindow)`
2. `EntityResolver.resolve(extractionResult, space)`
3. `PlanGenerator.generate(resolvedResult, space)`
4. Update `ContextWindow` with result.
5. Return `AssimilationResult` containing the `ChangeSet`.

Implements the `AssimilationPipeline` interface from `pebble-core`.

**Acceptance criteria:**
- [ ] End-to-end: text input → ChangeSet with correct operations
- [ ] Handles LLM failures gracefully (returns error result)
- [ ] Handles entity resolution failures gracefully
- [ ] Latency < 10 seconds for simple inputs (with real LLM)
- [ ] Unit test with mocked LLM and search

### Task 4.8: Resolution Feedback Store

**File to create:**
- `pebble-assimilation/src/main/java/com/anytypeio/anytype/pebble/assimilation/resolution/ResolutionFeedbackStore.kt`

**Behavior:**
- Tracks user corrections (disambiguation choices).
- `recordResolution(entityName, entityType, resolvedObjectId, wasCorrect)`.
- `getFrequencyBoost(entityName, entityType, candidateId): Float` — returns frequency-based score boost.
- Stored in Room (lightweight, local-only).

**Acceptance criteria:**
- [ ] Past resolutions boost future scoring for same name → object pairs
- [ ] Unit test: resolve "Aarav" → object X three times → frequency boost increases

---

## Phase 5: UI Layer

**Goal:** Build the Compose UI screens for input history, plan approval, change log, rollback, and settings.

**Depends on:** Phases 1–4 for all underlying functionality

### Task 5.1: Navigation Setup

**Files to create:**
- `feature-pebble-ui/src/main/res/navigation/nav_pebble.xml` — navigation graph for all pebble screens

**File to modify:**
- `app/src/main/res/navigation/graph.xml` — add `<include app:graph="@navigation/nav_pebble" />`

**Destinations:**
- `pebbleHome` — Dashboard/entry point
- `inputHistory` — List of voice inputs and their status
- `approvalScreen` — Review and approve/reject a pending plan
- `changeLog` — Chronological list of applied/rolled-back change sets
- `changeSetDetail` — Detailed view of a single change set's operations
- `pebbleSettings` — Webhook config, LLM provider, thresholds

**Acceptance criteria:**
- [ ] Navigation graph compiles
- [ ] Only 1 line added to `graph.xml`
- [ ] All destinations accessible

### Task 5.2: Pebble Dashboard Screen

**File to create:**
- `feature-pebble-ui/src/main/java/com/anytypeio/anytype/feature/pebble/ui/dashboard/PebbleDashboardScreen.kt`
- `feature-pebble-ui/src/main/java/com/anytypeio/anytype/feature/pebble/ui/dashboard/PebbleDashboardViewModel.kt`

**Screen content:**
- Webhook status indicator (running/stopped, port, connection count)
- Pending approvals count with "Review" button
- Recent inputs summary (last 5 with status badges)
- Quick links: Input History, Change Log, Settings

**Acceptance criteria:**
- [ ] Renders correctly with mock data
- [ ] Navigates to sub-screens
- [ ] Follows `core-ui` design system (colors, typography, spacing)

### Task 5.3: Input History Screen

**Files to create:**
- `InputHistoryScreen.kt` — Compose screen
- `InputHistoryViewModel.kt` — ViewModel

**Screen content:**
- Chronological list of voice inputs
- Each item shows: raw text (truncated), timestamp, status badge (queued/processing/processed/failed)
- Tap to expand: full text, processing details, link to change set
- Pull-to-refresh

**Acceptance criteria:**
- [ ] Displays paginated list of inputs from queue + processed store
- [ ] Status badges reflect current processing state
- [ ] Tap navigates to associated change set detail (if processed)

### Task 5.4: Approval Screen

**Files to create:**
- `ApprovalScreen.kt` — Compose screen
- `ApprovalViewModel.kt` — ViewModel

**Three-tier view (per research):**

**Summary view (default):**
- Source voice note text in a card
- Operation count: "Will create 2 objects and 1 link"
- Confidence indicator (high/medium/low)
- \[Approve\] \[Reject\] \[Review Details\] buttons

**Detail view (expandable):**
- Expandable list of operations with type, target, confidence
- Disambiguation choices with candidate list and radio buttons
- "Create new" option for each disambiguation

**Acceptance criteria:**
- [ ] Summary view shows correct operation counts and confidence
- [ ] Detail view expands to show individual operations
- [ ] Disambiguation choices are interactive (radio selection)
- [ ] Approve triggers execution, Reject sets status
- [ ] Handles multi-plan queue (swipe between pending plans)

### Task 5.5: Change Log Screen

**Files to create:**
- `ChangeLogScreen.kt` — Compose screen
- `ChangeLogViewModel.kt` — ViewModel

**Screen content:**
- Chronological list of change sets (most recent first)
- Each item: summary, timestamp, status badge (Applied/Rolled Back/Partial/Failed)
- Tap to expand: operation details with before/after state
- \[Rollback\] button on each applied change set
- Filter by status

**Acceptance criteria:**
- [ ] Displays change sets from `ChangeStore`
- [ ] Rollback button triggers confirmation dialog → rollback engine
- [ ] Conflict resolution dialog appears when conflicts detected during rollback
- [ ] Before/after state shown for each operation

### Task 5.6: Settings Screen

**Files to create:**
- `PebbleSettingsScreen.kt` — Compose screen
- `PebbleSettingsViewModel.kt` — ViewModel

**Settings:**

| Setting | Type | Default |
|---------|------|---------|
| Webhook enabled | Toggle | true |
| Webhook port | Number input | 8391 |
| Webhook auth token | Password field | (generated) |
| LLM provider | Dropdown (Anthropic/OpenAI) | Anthropic |
| LLM API key | Password field | (empty) |
| LLM model | Dropdown | claude-sonnet |
| Auto-approve threshold | Slider (0.0–1.0) | 0.85 |
| Auto-approve enabled | Toggle | false |
| Create-new threshold | Slider (0.0–1.0) | 0.50 |

**Acceptance criteria:**
- [ ] All settings persist (DataStore or SharedPreferences)
- [ ] Webhook restarts on port change
- [ ] API key stored in EncryptedSharedPreferences
- [ ] Threshold sliders update in real-time

### Task 5.7: Fragment Shells & DI Wiring

Create the Fragment shells in the `app` module that host the Compose screens (following AnyType's pattern of app-hosted fragments for feature module screens).

**Files to create in `app` module:**
```
app/.../ui/pebble/PebbleDashboardFragment.kt
app/.../ui/pebble/InputHistoryFragment.kt
app/.../ui/pebble/ApprovalFragment.kt
app/.../ui/pebble/ChangeLogFragment.kt
app/.../ui/pebble/PebbleSettingsFragment.kt
```

Each fragment:
- Obtains its ViewModel from the DI graph via `ComponentManager.pebbleComponent`.
- Sets Compose content from the corresponding feature-pebble-ui screen.

**Acceptance criteria:**
- [ ] All fragments compile and resolve dependencies
- [ ] Navigation between screens works end-to-end

---

## Phase 6: Integration & End-to-End Testing

**Goal:** Wire all phases together and validate the full pipeline from webhook to graph mutation.

**Depends on:** Phases 0–5

### Task 6.1: Full Pipeline Integration

Connect the webhook service → input processor → assimilation engine → change control → AnyType graph.

**Wiring:**
- `InputProcessor` in `pebble-webhook` gets injected with the `AssimilationPipeline` from `pebble-assimilation`.
- `AssimilationEngine` produces `ChangeSet` objects.
- `InputProcessor` passes change sets to `ChangeStore` and triggers approval flow or auto-apply.
- `ChangeExecutor` applies approved change sets via `PebbleGraphService`.

**Acceptance criteria:**
- [ ] POST to webhook → input queued → LLM extraction → entity resolution → change set created → change set applied → objects exist in AnyType graph
- [ ] Rollback of the change set removes created objects
- [ ] Auto-approve mode: high-confidence plan applied without user interaction

### Task 6.2: End-to-End Test Scenarios

Write integration tests for key scenarios:

| # | Scenario | Expected Result |
|---|----------|----------------|
| 1 | "Aarav has a basketball game on Friday" | Creates Person(Aarav), Event(basketball game, date=Friday), links them |
| 2 | Same input again | Resolves existing Aarav (no duplicate), creates new Event, links |
| 3 | "Remind me to call Dr. Patel" | Creates Reminder, resolves existing Person(Dr. Patel) |
| 4 | "Meeting with Sarah at Cafe Luce tomorrow at 3pm" | Creates Meeting, resolves/creates Person(Sarah), resolves/creates Place(Cafe Luce) |
| 5 | Rollback scenario 1 | Event deleted, Person deleted (or kept if linked elsewhere), relation removed |
| 6 | Conflict rollback: apply → user edits Person name → rollback | Conflict detected on Person, skip or force |
| 7 | Offline: webhook received while offline | Input queued, processed when online |
| 8 | Taxonomy bootstrap on fresh space | All custom types and relations created |

**Acceptance criteria:**
- [ ] All 8 scenarios pass
- [ ] Tests are repeatable (clean up after each)
- [ ] `make test_debug_all` passes

### Task 6.3: Notification Integration

- Pending plan notification: "Voice input processed — tap to review"
- Auto-applied notification: "Applied changes from 'Aarav has a basketball game...' — [Undo]"
- Undo from notification triggers rollback via PendingIntent → ChangeExecutor.

**Acceptance criteria:**
- [ ] Notifications appear at correct times
- [ ] Undo action triggers rollback
- [ ] Notification cleared after user action

### Task 6.4: Entry Point — Home Screen Access

Add an entry point to the pebble dashboard from AnyType's home screen. Options (in order of preference):
1. FAB (floating action button) or menu item on home screen → navigate to pebble dashboard.
2. Bottom navigation tab (if bottom nav exists).
3. Deep link from notification.

**Acceptance criteria:**
- [ ] User can reach pebble dashboard from normal app flow
- [ ] Minimal changes to existing home screen code

---

## Phase Summary & Dependencies

```
Phase 0: Scaffolding & DI Bridge    ───────────────────────┐
    │                                                       │
    ├──→ Phase 1: Taxonomy & Schema Bootstrap               │
    │        │                                              │
    ├──→ Phase 2: Change Control Layer                      │
    │        │                                              │  All phases feed into
    ├──→ Phase 3: Webhook Service                           │  Phase 6
    │        │                                              │
    └──→ Phase 4: Assimilation Engine                       │
             │                                              │
             └──→ Phase 5: UI Layer ────────────────────────┘
                      │                                     
                      └──→ Phase 6: Integration & E2E Testing
```

**Parallelization opportunities:**
- Phases 1, 2, 3 can proceed in parallel after Phase 0 completes.
- Phase 4 depends on Phase 1 (taxonomy) and Phase 2 (change set model) for data models.
- Phase 5 can start UI scaffolding after Phase 0 but needs Phases 1–4 for real data.
- Phase 6 requires all prior phases.

---

## Risk Register

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| Upstream AnyType merge conflicts | Medium | Medium | Region-bracketed changes, ≤35 lines in 7 files, automated merge check |
| Ktor CIO incompatibility with project's Kotlin/Android versions | High | Low | Verify early in Phase 3; fallback to NanoHTTPD if needed |
| LLM extraction accuracy below 90% | High | Medium | Iterative prompt tuning; fallback to more capable model; human-in-the-loop for all plans initially |
| AnyType search latency too high for entity resolution | Medium | Low | Hot cache via subscriptions; batch candidate retrieval |
| Room + AnyType dual storage consistency | Medium | Medium | AnyType is source of truth; Room is a disposable cache rebuilt on mismatch |
| Custom type creation fails (middleware limitations) | High | Low | Verify in Phase 1 with prototype; fallback to using generic Page type with tags |
| Change set storage overhead at scale (100s of change sets as AnyType objects) | Low | Medium | Archival policy; lazy loading in UI; Room cache for queries |

---

## Implementation Order (Recommended)

For a coding agent executing iteratively:

1. **Phase 0** (Tasks 0.1 → 0.5) — must be first and complete
2. **Phase 1** (Tasks 1.1 → 1.5) — enables taxonomy for all downstream work
3. **Phase 2** (Tasks 2.1 → 2.6) — change control is the backbone
4. **Phase 3** (Tasks 3.1 → 3.5) — webhook service (can overlap with Phase 2)
5. **Phase 4** (Tasks 4.1 → 4.8) — assimilation engine (heaviest phase)
6. **Phase 5** (Tasks 5.1 → 5.7) — UI layer
7. **Phase 6** (Tasks 6.1 → 6.4) — integration and polish

Total estimated tasks: **36 tasks across 7 phases.**
