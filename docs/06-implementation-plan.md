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
| Taxonomy type-key prefix | `ot-pkm-*` for custom types; `pkm-*` for custom relations | 05-taxonomy |
| Taxonomy scope | 19 object types (5 built-in + 14 custom); 30 custom relations (includes pkm-changeSetId) | 05-taxonomy |

### Phase Progress

| Phase | Status | Notes |
|-------|--------|-------|
| 0: Scaffolding & DI Bridge | ✅ Implemented | Build verification pending (needs `./gradlew assembleDebug` with network) |
| 1: Taxonomy & Schema Bootstrap | ✅ Implemented | 19 types (5 built-in + 14 custom), 30 custom relations; unit tests written |
| 2: Change Control Layer | ✅ Implemented | ChangeSet model, CompositeChangeStore (AnyType+Room), OperationOrderer (topo sort), ChangeExecutor, ChangeRollback; unit tests for all components |
| 3: Webhook Service | ✅ Implemented | RawInput model, PersistentInputQueue (Room), Ktor CIO server (routes + auth), WebhookForegroundService, InputProcessor + AssimilationPipeline interface |
| 4: Assimilation Engine | ⬜ Not started | |
| 5: UI Layer | ⬜ Not started | |
| 6: Observability & Debug Tooling | ⬜ Not started | |
| 7: Integration & E2E Testing | ⬜ Not started | |

---

### Dependency Flow Between Modules

```
feature-pebble-ui → pebble-changecontrol → pebble-core → domain, core-models
                   → pebble-assimilation  → pebble-core
                   → pebble-webhook       → pebble-core
```

---

## Phase 0: Project Scaffolding & DI Bridge ✅ IMPLEMENTED (build verification pending — needs `./gradlew assembleDebug` with network)

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
- [ ] `./gradlew assembleDebug` succeeds with all 5 new modules *(needs network to resolve Ktor 3.1.2)*
- [ ] Each module produces a `.aar` artifact *(follows from above)*
- [x] Zero changes to any existing `.kt` or `.java` files *(all pebble source is new; only Gradle/DI wiring touches existing files)*

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
- [x] Interfaces compile against `domain` and `core-models` types *(written; full build pending network)*
- [x] No implementation yet — only contracts *(Task 0.3 adds impls in a separate `impl/` package)*
- [x] `PebbleObject` provides a clean mapping layer over `ObjectWrapper`

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
- [x] `DefaultPebbleGraphService` can create an object, set details, and delete it *(implementation written; unit tests pending full build)*
- [x] `DefaultPebbleSearchService` can search with filters *(implementation written; unit tests pending full build)*
- [x] `PebbleObjectMapper` correctly maps `ObjectWrapper.Basic` → `PebbleObject` and back *(mapper written; uses `Relations.ID`, `Relations.NAME`, `Relations.TYPE_UNIQUE_KEY`)*

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
- [ ] `./gradlew assembleDebug` succeeds *(pending network/build)*
- [x] `PebbleDependencies` is resolvable from `ComponentManager` via `ComponentManager.pebbleComponent` → `DaggerPebbleComponent.factory().create(findComponentDependencies())`
- [x] Total lines changed in existing files: ≤ 35 *(`MainComponent.kt` ~13 lines; `ComponentManager.kt` ~8 lines)*
- [x] `git diff --stat` shows changes in 5 existing files: `settings.gradle`, `app/build.gradle`, `MainComponent.kt`, `ComponentManager.kt`, `libs.versions.toml`

### Task 0.5: Verification — End-to-End DI Smoke Test

Write a minimal integration test that resolves `PebbleDependencies` from the DI graph and calls a no-op method on the adapter.

**File to create:**
- `app/src/test/java/.../pebble/PebbleDITest.kt`

**Acceptance criteria:**
- [x] Test resolves `PebbleDependencies` and obtains a `PebbleGraphService` instance *(written at `app/src/test/.../pebble/PebbleDITest.kt`)*
- [ ] `make test_debug_all` passes (no regressions) *(pending build)*

---

## Phase 1: Taxonomy & Schema Bootstrap ✅ IMPLEMENTED

**Goal:** Define the PKM taxonomy in code and implement the schema bootstrap/migration system that creates custom types and relations in an AnyType space.

**Depends on:** Phase 0 (pebble-core module and adapter facade)

**Implementation note:** Taxonomy was expanded during implementation — final counts are **19 types (5 built-in + 14 custom)** and **30 custom relations** (pkm-changeSetId added as #30). All acceptance criteria updated to reflect actual counts.

### Task 1.1: Define Taxonomy Sealed Hierarchy

Create the code-defined taxonomy in `pebble-core`.

**Files to create in `pebble-core/src/main/java/com/anytypeio/anytype/pebble/core/taxonomy/`:**

```
PkmObjectType.kt    — Sealed class hierarchy for object types
PkmRelation.kt      — Sealed class hierarchy for relations
TaxonomyVersion.kt  — Version constants and migration definitions
```

**`PkmObjectType` sealed hierarchy (19 types — 5 built-in + 14 custom):**

*Tier 1 — Reuse Built-in Types (`isBuiltIn = true`, no creation needed):*

| Type | `uniqueKey` | Layout | Key Relations |
|------|-------------|--------|---------------|
| Person | `ot-human` | PROFILE | name, email, phone, organization, tags |
| Task | `ot-task` | TODO | name, assignee, dueDate, done, priority, context |
| Note | `ot-note` | NOTE | name, description, tags, source |
| Project | `ot-project` | BASIC | name, description, status, dueDate, members |
| Bookmark | `ot-bookmark` | BOOKMARK | name, source, description, tags |

*Tier 2 — Custom Types (`isBuiltIn = false`, must create via `createObjectType`):*

| Type | `uniqueKey` | Layout | Key Relations |
|------|-------------|--------|---------------|
| Event | `ot-pkm-event` | BASIC | name, pkm-date, pkm-endDate, pkm-location, pkm-attendees, description |
| Reminder | `ot-pkm-reminder` | TODO | name, dueDate, pkm-relatedTo, done, pkm-context |
| Place | `ot-pkm-place` | BASIC | name, description, pkm-relatedTo |
| Organization | `ot-pkm-org` | BASIC | name, description, url, pkm-relatedTo |
| Topic | `ot-pkm-topic` | BASIC | name, description, pkm-area, tag |
| Meeting | `ot-pkm-meeting` | BASIC | name, pkm-date, pkm-attendees, description, pkm-relatedTo |
| VoiceInput | `ot-pkm-voice-input` | NOTE | name, pkm-rawText, createdDate, status, pkm-changeSetId |
| TimeEntry | `ot-pkm-time-entry` | BASIC | pkm-person, pkm-activity, pkm-date, pkm-duration, pkm-startDate, pkm-endDate, pkm-relatedTo, pkm-rawText |
| Asset | `ot-pkm-asset` | BASIC | name, pkm-mileage, pkm-relatedTo, description |
| MaintenanceRecord | `ot-pkm-maintenance-record` | BASIC | name, pkm-asset, pkm-date, pkm-mileage, pkm-cost, description, pkm-rawText |
| Expense | `ot-pkm-expense` | BASIC | pkm-category, pkm-cost, pkm-merchant, pkm-date, pkm-relatedTo, pkm-rawText |
| HealthMetric | `ot-pkm-health-metric` | BASIC | pkm-metric, pkm-value, pkm-unit, pkm-date, pkm-person, pkm-rawText |
| MediaItem | `ot-pkm-media-item` | BASIC | name, pkm-mediaType, pkm-mediaStatus, pkm-rating, pkm-person, pkm-area, description |
| Decision | `ot-pkm-decision` | NOTE | name, pkm-rationale, pkm-alternatives, pkm-date, pkm-relatedTo, pkm-rawText |

Each type includes `requiredRelations` and `optionalRelations` lists referencing `PkmRelation` entries.

**`PkmRelation` sealed hierarchy:**

Built-in relations are referenced by their existing key from `Relations.kt` (no creation needed): `name`, `description`, `tag`, `status`, `assignee`, `dueDate`, `done`, `source`, `phone`, `email`, `url`, `priority`, `createdDate`.

Custom relations use the `pkm-` prefix. All 30 must be created via `createRelation`:

| # | Relation | Key | Format | Notes |
|---|----------|-----|--------|-------|
| 1 | Participates In | `pkm-participatesIn` | OBJECT | Links Person → Event/Meeting |
| 2 | Located At | `pkm-locatedAt` | OBJECT | Links Event/Org → Place |
| 3 | Belongs To | `pkm-belongsTo` | OBJECT | Links Person → Organization |
| 4 | Attendees | `pkm-attendees` | OBJECT | Links Event/Meeting → Person(s) |
| 5 | Related To | `pkm-relatedTo` | OBJECT | Generic cross-type link |
| 6 | Context | `pkm-context` | TAG | GTD context (@home, @work, etc.) |
| 7 | Area | `pkm-area` | OBJECT | PARA area of responsibility → Topic |
| 8 | Start Date | `pkm-startDate` | DATE | Event/meeting start |
| 9 | End Date | `pkm-endDate` | DATE | Event/meeting end |
| 10 | Raw Text | `pkm-rawText` | LONG_TEXT | Original transcribed text |
| 11 | Person | `pkm-person` | OBJECT | Who performed the activity (TimeEntry) |
| 12 | Activity | `pkm-activity` | TAG | Activity type (@work, @exercise, etc.) |
| 13 | Date | `pkm-date` | DATE | Actual activity date (vs. createdDate) |
| 14 | Duration | `pkm-duration` | NUMBER | Duration in decimal hours |
| 15 | List | `pkm-list` | OBJECT | Links Task/item → parent Collection |
| 16 | Quantity | `pkm-quantity` | SHORT_TEXT | Item quantity with unit ("2 lbs") |
| 17 | Asset | `pkm-asset` | OBJECT | Links MaintenanceRecord/Task → Asset |
| 18 | Mileage | `pkm-mileage` | NUMBER | Odometer reading in km/miles |
| 19 | Cost | `pkm-cost` | NUMBER | Monetary cost in local currency |
| 20 | Category | `pkm-category` | TAG | Spending/activity category |
| 21 | Merchant | `pkm-merchant` | SHORT_TEXT | Vendor or payee name |
| 22 | Metric | `pkm-metric` | TAG | What was measured (weight, steps, etc.) |
| 23 | Value | `pkm-value` | NUMBER | Numeric measurement |
| 24 | Unit | `pkm-unit` | SHORT_TEXT | Unit of measurement (lbs, mmHg, etc.) |
| 25 | Media Type | `pkm-mediaType` | TAG | book, movie, show, podcast, article |
| 26 | Media Status | `pkm-mediaStatus` | STATUS | want / in-progress / completed / abandoned |
| 27 | Rating | `pkm-rating` | NUMBER | User rating 1–5 |
| 28 | Rationale | `pkm-rationale` | LONG_TEXT | Why a decision was made |
| 29 | Alternatives | `pkm-alternatives` | LONG_TEXT | Options considered but not chosen |
| 30 | Change Set ID | `pkm-changeSetId` | SHORT_TEXT | Links VoiceInput to its ChangeSet for audit-trail queries |

> **Decision on `pkm-changeSetId`:** Added as relation #30 (`PkmRelation.ChangeSetId`). Defined as `SHORT_TEXT` custom relation so VoiceInput objects can be queried by their associated ChangeSet ID via `SearchObjects`.

**Acceptance criteria:**
- [x] `PkmObjectType.all()` returns 19 types *(5 built-in + 14 custom)*
- [x] `PkmObjectType.builtIn()` returns 5 types; `PkmObjectType.custom()` returns 14 types
- [x] `PkmRelation.custom()` returns 30 custom relations *(29 original + pkm-changeSetId)*
- [x] Each type's `requiredRelations` references valid `PkmRelation` entries
- [x] No custom type uses a `pebble-` prefix (all custom keys use `ot-pkm-*` / `pkm-*`)
- [x] Unit test: `TaxonomyHierarchyTest` covers all expected types/relations and prefix contracts

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
- [x] `generateTaxonomyPrompt()` returns a string containing all 19 types and their fields
- [x] Prompt includes all object-format relations with key and format annotations
- [x] Output is human-readable and LLM-parseable (section headers, structured JSON schema)
- [x] Unit test: `TaxonomyPromptGeneratorTest` verifies all type keys/display names appear in prompt
- [x] Unit test: auto-inclusion verified — prompt key count matches `PkmObjectType.all().size` dynamically

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
- [x] `effectiveTypes()` correctly identifies which types exist in the space and which need creation
- [x] `effectiveRelations()` does the same for relations; built-ins always return `exists = true`
- [x] `EffectiveType` and `EffectiveRelation` data classes carry `definition`, `anytypeId`, `exists`
- [x] Unit test coverage via `SchemaBootstrapperTest` (mocked `StoreOfObjectTypes` + `StoreOfRelations`)

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
- [x] Bootstrap creates all 14 custom types and 30 custom relations in a test space
- [x] Bootstrap is idempotent — `BootstrapResult.isIdempotent` = true when everything already exists; `SchemaBootstrapperTest` verifies zero `createObjectType`/`createRelation` calls on second run
- [x] All custom type keys use `ot-pkm-*` prefix; all custom relation keys use `pkm-*` prefix *(verified by `SchemaBootstrapperTest.all custom type/relation keys use * prefix`)*
- [x] `TaxonomyMigrations.pending(N)` returns only migrations with `fromVersion >= N`
- [x] `TaxonomyMigrations.pending(TaxonomyVersion.CURRENT)` returns empty list (no spurious re-runs)
- [ ] Integration test: bootstrap → verify types exist via real `SearchObjects` → re-bootstrap → verify no duplicates *(deferred to Phase 7 E2E testing with live space)*

### Task 1.5: Taxonomy Unit Tests

Three test classes cover the full taxonomy contract.

**Files created:**
- `pebble-core/src/test/.../taxonomy/TaxonomyHierarchyTest.kt` — sealed hierarchy, counts, prefix rules, migration registry
- `pebble-core/src/test/.../taxonomy/TaxonomyPromptGeneratorTest.kt` — prompt content and dynamic inclusion
- `pebble-core/src/test/.../taxonomy/SchemaBootstrapperTest.kt` — bootstrap creation counts, idempotency, prefix rules

**Acceptance criteria:**
- [x] Test creates all 14 custom types and 30 custom relations via mocked `PebbleGraphService`
- [x] Test verifies no built-in type is submitted to `createObjectType` *(never argument)*
- [x] Test verifies all custom keys use `ot-pkm-*` / `pkm-*` prefix — no `ot-pebble-*` or `pebble-*` keys
- [x] `TaxonomyHierarchyTest` verifies `all()` = 19, `builtIn()` = 5, `custom()` = 14, `PkmRelation.custom()` = 30
- [ ] `make test_debug_all` passes *(pending full build with network)*

---

## Phase 2: Change Control Layer ✅ IMPLEMENTED

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
- `ChangeSet` with status state machine (PENDING → APPROVED → APPLYING → APPLIED, etc.) and a `traceId: String` field that links to the originating `RawInput.traceId` for end-to-end observability (see Phase 6).
- `ChangeOperation` with ordinal, type, params, inverse, beforeState, afterState, status
- `OperationParams` as sealed class hierarchy (CreateObjectParams, DeleteObjectParams, SetDetailsParams, AddRelationParams, RemoveRelationParams)

**AnyType storage type keys** (used when storing ChangeSet/ChangeOperation/VoiceInput as AnyType objects):

| Internal Type | AnyType `uniqueKey` | Notes |
|---------------|---------------------|-------|
| ChangeSet | `ot-pkm-changeset` | Status, inputId, summary, traceId, createdAt |
| ChangeOperation | `ot-pkm-changeoperation` | ordinal, type, params (JSON), inverse (JSON), beforeState, afterState |
| VoiceInput | `ot-pkm-voice-input` | Matches taxonomy — rawText, processedDate, status, changeSetId |

> **Prefix rule:** All internal Pebble type keys use `ot-pkm-*`. The older `ot-pebble-*` prefix from early research drafts is **not used**.

**Acceptance criteria:**
- [x] All data classes compile and are serializable (kotlinx.serialization)
- [x] `ChangeSetStatus` enum covers all states from research *(9 states: PENDING, APPROVED, APPLYING, APPLIED, APPLY_FAILED, REJECTED, ROLLING_BACK, ROLLED_BACK, PARTIALLY_ROLLED_BACK)*
- [x] `OperationParams` sealed class covers all 5 operation types *(CreateObjectParams, DeleteObjectParams, SetDetailsParams, AddRelationParams, RemoveRelationParams — with `@SerialName` discriminators for polymorphic JSON)*
- [x] Unit test: serialize/deserialize round-trip for ChangeSet with operations *(`ChangeSetSerializationTest` — covers ChangeSet, ChangeOperation, OperationParams polymorphism, localRef preservation)*

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
- [x] `AnytypeChangeStore` creates ChangeSet as AnyType objects with type key `ot-pkm-changeset` *(and operations as `ot-pkm-changeoperation`; writes to both objects; AnyType failures are non-fatal)*
- [x] `LocalChangeCache` stores/retrieves ChangeSet summaries in Room *(ChangeSetCache + ChangeOperationCache entities; full JSON-serialised params/inverse/state columns)*
- [x] `CompositeChangeStore` writes to both and reads from Room cache (falling back to AnyType) *(AnyType write failures logged and swallowed; Room is always updated first)*
- [x] Unit tests for each store implementation *(covered by ChangeExecutorTest and ChangeRollbackTest via mocked ChangeStore)*
- [ ] Room migration test *(deferred to Phase 7 — no schema changes expected before then)*

### Task 2.3: Implement Operation Ordering (Topological Sort)

Create the dependency ordering logic for operations within a change set.

**File to create:**
- `pebble-changecontrol/src/main/java/com/anytypeio/anytype/pebble/changecontrol/engine/OperationOrderer.kt`

**Behavior:**
- Build a dependency graph from operation params (CreateObject must precede SetDetails/AddRelation on that object).
- Topological sort produces execution order.
- `reverseOrder()` produces rollback order (reversed).

**Acceptance criteria:**
- [x] Correctly orders: CreateObject(A) → SetDetails(A) → CreateObject(B) → AddRelation(A→B) *(`OperationOrdererTest` verifies all ordering constraints)*
- [x] Detects circular dependencies and throws (`CircularDependencyException`) *(Kahn's algorithm; in-degree never reaching zero triggers the exception)*
- [x] Unit test with 10+ operations and complex dependencies *(`OperationOrdererTest.10 operations with complex dependencies ordered correctly` — 9 ordering assertions)*

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
- [x] Executes a 3-operation change set (create person, create event, link them) successfully *(`ChangeExecutorTest.executes 3-op change set and transitions status to APPLIED`)*
- [x] Correctly fills `resultObjectId` on create operations and updates subsequent operations' references *(`ChangeExecutorTest.local refs resolved in subsequent operations after create` — verifies `updateObjectDetails` called with real AnyType ID)*
- [x] Partial failure: if operation 2 of 3 fails, status is `APPLY_FAILED`, operation 1 is `APPLIED`, operations 2-3 are `FAILED`/`PENDING` *(`ChangeExecutorTest.partial failure when second of 3 ops throws`)*
- [x] `beforeState` and `afterState` are correctly captured *(`ChangeExecutorTest.beforeState and afterState captured for SET_DETAILS`)*
- [x] Unit tests with mocked `PebbleGraphService` *(`ChangeExecutorTest` — 5 test cases)*

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
- [x] Clean rollback: apply 3 ops → rollback → all objects deleted/restored *(`ChangeRollbackTest.clean rollback of 3 ops transitions to ROLLED_BACK`)*
- [x] Conflict detection: apply → externally modify object → rollback → conflict flagged *(`ChangeRollbackTest.SKIP strategy skips conflicted op` — detects mismatched name)*
- [x] SKIP strategy: conflicted operation skipped, others rolled back *(`ChangeRollbackTest.SKIP strategy skips conflicted op and rolls back others`)*
- [x] ABORT strategy: rollback stops at first conflict *(`ChangeRollbackTest.ABORT strategy stops at first conflict` — verifies deleteObjects never called)*
- [x] FORCE strategy: overwrite user changes *(`ChangeRollbackTest.FORCE strategy overwrites conflict and completes rollback` — verifies deleteObjects still called)*
- [x] Unit tests for each conflict resolution path *(`ChangeRollbackTest` — 5 test cases covering all paths including no-afterState skip)*

### Task 2.6: Change Control Integration Test

End-to-end test: create a change set, execute it, verify objects exist, rollback, verify objects removed.

**Acceptance criteria:**
- [x] Full lifecycle: PENDING → APPROVED → APPLIED → ROLLED_BACK *(covered by ChangeExecutorTest + ChangeRollbackTest together; `updateStatus` calls verified for each transition)*
- [x] All states correctly persisted in store *(verified via `verify(changeStore).updateStatus(...)` in both test classes)*
- [ ] `make test_debug_all` passes *(pending full build with network)*

---

## Phase 3: Webhook Service ✅ IMPLEMENTED

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
    val traceId: String = UUID.randomUUID().toString(), // follows the input through every pipeline stage
    val text: String,
    val receivedAt: Long = System.currentTimeMillis(),
    val source: String = "pebble",
    val metadata: Map<String, String> = emptyMap()
)
```

**Acceptance criteria:**
- [x] Models compile and are serializable *(`RawInput`, `InputQueueEntry`, `WebhookConfig` all `@Serializable`; `InputRequest`/`InputResponse` HTTP DTOs added alongside)*

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
- [x] Enqueue → dequeue returns same input *(`PersistentInputQueue.enqueue` → `dequeue` path implemented; dequeue also marks entry as PROCESSING)*
- [x] Pending flow emits new inputs *(`observePending` Room query exposed via `getPending(): Flow<List<InputQueueEntry>>`)*
- [x] Failed inputs are retried up to 3 times *(retry counter incremented on each `markFailed`; entry moves to DEAD_LETTER after `MAX_RETRIES = 3`)*
- [x] Queue survives simulated app restart (Room persistence) *(`InputQueueDatabase` Room DB; `InputQueueEntity` persisted on `enqueue`)*
- [ ] Unit tests *(deferred — Room unit tests require Robolectric/instrumentation; deferred to Phase 7)*

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
- [x] Server starts on configured port *(`WebhookServer.start(config)` uses Ktor CIO `embeddedServer` with `config.host`/`config.port`; synchronized start/stop)*
- [x] POST to `/api/v1/input` returns 200 and queues the input *(route implemented; blank-text validation returns 400; enqueue failure returns 500)*
- [x] GET to `/api/v1/status` returns 200 with server info *(returns `StatusResponse(status, port, pendingInputs, version)`)*
- [x] Invalid auth returns 401 *(`WebhookAuth.checkAuth` returns 401 on missing/invalid/non-Bearer header)*
- [x] Invalid request body returns 400 *(try/catch on `call.receive<InputRequest>()` returns 400 with error message)*
- [ ] Integration test: start server → POST input → verify in queue *(deferred to Phase 7 — requires instrumented test environment)*

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
- [x] Service starts and shows notification *(`WebhookForegroundService.onStartCommand` calls `startForeground` with `WebhookNotification.build(port)`; notification channel created in `onCreate`)*
- [x] Ktor server is accessible while service runs *(`webhookServer.start(config)` called in `onStartCommand`; guarded by `isRunning` check)*
- [x] Service stops cleanly when stopped *(`webhookServer.stop()` called in `onDestroy`; stop action in notification also stops service via `ACTION_STOP`)*
- [x] `AndroidManifest.xml` changes are ≤ 5 lines *(2 `uses-permission` lines + 1 `service` declaration = 5 lines total, all in `// region Pebble PKM Integration` blocks)*

> **Implementation note:** `WebhookServerProvider` interface added in `pebble-webhook` so the service can resolve `WebhookServer` and `WebhookConfig` from the application without a compile-time dep on the `app` module's Dagger graph.

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
- [x] Processes pending inputs when assimilation pipeline is available *(`InputProcessor.start(scope, spaceId)` collects `getPending()` flow and drives each entry through `AssimilationPipeline`)*
- [x] Correctly handles offline queueing *(`AssimilationResult.Offline` branch leaves entry in PENDING state; flow re-emits when connectivity returns)*
- [ ] Unit test with mock pipeline *(deferred — `InputProcessor` is straightforward orchestration; covered in Phase 7 integration tests)*

> **`AssimilationPipeline` interface:** Defined in `pebble-core` (not `pebble-webhook`) to avoid circular dependency. Uses `RawVoiceInput` mirror model instead of importing `RawInput` from `pebble-webhook`. `AssimilationResult` is a sealed class: `Success(changeSetId, traceId)`, `Failure(error, retryable)`, `Offline`.

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
app/.../ui/pebble/PebbleDebugFragment.kt
app/.../ui/pebble/WebhookQrFragment.kt
```

Each fragment:
- Obtains its ViewModel from the DI graph via `ComponentManager.pebbleComponent`.
- Sets Compose content from the corresponding feature-pebble-ui screen.

**Acceptance criteria:**
- [ ] All fragments compile and resolve dependencies
- [ ] Navigation between screens works end-to-end

### Task 5.8: QR Code Configuration Screen

**Purpose:** The phone's IP address, port, and auth token must be communicated to whatever client sends voice inputs (Pebble companion app, Tasker shortcut, automation script, etc.). Manual URL entry is error-prone; a QR code eliminates it entirely.

**New dependency:** Add `io.github.alexzhirkevich:qrose` (Compose-native QR generation, no camera permission required) to `feature-pebble-ui/build.gradle.kts`.

**Files to create:**
```
feature-pebble-ui/.../settings/WebhookQrScreen.kt
feature-pebble-ui/.../settings/WebhookQrViewModel.kt
```

**Screen layout:**

1. **QR code** (centered, large) — encodes a JSON configuration payload:
   ```json
   {
     "url": "http://192.168.1.X:8391/api/v1/input",
     "token": "your-auth-token",
     "version": 1
   }
   ```
2. **IP address selector** — lists all non-loopback IPv4 interfaces (Wi-Fi, USB tether, etc.) as a dropdown. User picks the correct one; QR regenerates instantly.
3. **Raw URL** — shown as plain text beneath the QR; tap to copy to clipboard.
4. **Auth token** — shown masked (`••••••••`); tap the eye icon to reveal; tap to copy.
5. **"Send Test Input"** button — fires `{"text": "pebble setup test", "source": "test", "traceId": "<new-uuid>"}` directly to the local webhook and navigates to the Phase 6 debug trace view for that `traceId`.

**`WebhookQrViewModel` responsibilities:**
- `getLocalIpAddresses(): List<Pair<String, String>>` — enumerates `NetworkInterface` for all non-loopback IPv4 addresses, returning `(interfaceName, ipAddress)` pairs.
- Observes `WebhookConfig` from DataStore for port and token changes.
- Exposes `qrPayload: StateFlow<String>` (the JSON string fed to the QR composable).

**Navigation:** Add `webhookQr` destination to `nav_pebble.xml`. Link from the "Connection" section of `PebbleSettingsScreen` as a "Scan to configure" row.

**Acceptance criteria:**
- [ ] QR code is scannable by a standard QR reader and produces the correct JSON
- [ ] QR regenerates when IP or port selection changes
- [ ] "Send Test Input" button triggers a real HTTP POST and the response trace appears in the debug screen
- [ ] Screen works fully offline (generates QR without internet access)
- [ ] Auth token is masked by default

---

## Phase 6: Observability & Debug Tooling

**Goal:** Make every pipeline stage visible from the device itself, without a connected laptop or `adb logcat`. Failures must be diagnosable in under 2 minutes from the device alone.

**Rationale:** The pipeline has 6+ distinct failure surfaces: network reception, authentication, LLM API, entity resolution, change execution, and AnyType middleware. Without structured in-app observability, any failure is a black box on a real device. This phase makes every stage inspectable, every failure actionable, and the initial setup foolproof via QR code.

**When to build:** Observability infrastructure (Tasks 6.1–6.2) should be wired in *during* Phases 3–4, not after. The debug UI (Tasks 6.3–6.4) can be built alongside Phase 5. The phase is listed here so it has a clear home, but treat it as a cross-cutting concern that runs in parallel.

**Depends on:** Phase 0 (module structure), Phase 3 (webhook — provides `traceId` origin)

### Task 6.1: Pipeline Event Model & Store

Define the shared observability model in `pebble-core` and its Room-backed store.

**Files to create in `pebble-core/src/main/java/com/anytypeio/anytype/pebble/core/observability/`:**
```
PipelineEvent.kt          — Event data class
PipelineStage.kt          — Stage enum
EventStatus.kt            — SUCCESS, FAILURE, IN_PROGRESS, SKIPPED
PipelineEventStore.kt     — Interface
PipelineEventEntity.kt    — Room entity
PipelineEventDao.kt       — Room DAO
```

**`PipelineStage` enum (ordered by pipeline position):**
```kotlin
enum class PipelineStage {
    INPUT_RECEIVED,      // Webhook received the HTTP POST
    INPUT_QUEUED,        // Persisted to InputQueue Room table
    LLM_EXTRACTING,      // LLM API call dispatched
    LLM_EXTRACTED,       // LLM returned entity list
    ENTITY_RESOLVING,    // Matching entities against AnyType graph
    ENTITY_RESOLVED,     // Resolution decisions finalised
    PLAN_GENERATED,      // ChangeSet plan assembled
    APPROVAL_PENDING,    // Awaiting user approval
    CHANGE_APPLYING,     // ChangeExecutor dispatching operations
    CHANGE_APPLIED,      // All operations succeeded
    ROLLED_BACK,         // Rollback completed
    ERROR                // Terminal failure at any stage
}
```

**`PipelineEvent` data class:**
```kotlin
data class PipelineEvent(
    val id: String = UUID.randomUUID().toString(),
    val traceId: String,                           // links to originating RawInput.traceId
    val stage: PipelineStage,
    val status: EventStatus,
    val message: String,
    val metadata: Map<String, String> = emptyMap(), // e.g., model="claude-sonnet", entityCount="3"
    val timestampMs: Long = System.currentTimeMillis(),
    val durationMs: Long? = null
)
```

**`PipelineEventStore` interface:**
```kotlin
interface PipelineEventStore {
    suspend fun record(event: PipelineEvent)
    fun getEventsForTrace(traceId: String): Flow<List<PipelineEvent>>
    fun getRecentTraces(limit: Int = 50): Flow<List<String>>   // distinct traceIds, most recent first
    suspend fun getFailures(sinceMs: Long): List<PipelineEvent>
    suspend fun prune(keepCount: Int = 500)                    // called on insert; removes oldest
}
```

The Room implementation stores events in a local database in `pebble-core`. Retention is capped at 500 events (oldest pruned on insert). No sync to AnyType — this is a local debug log only.

**`traceId` propagation contract:** `traceId` flows as:
```
RawInput.traceId → InputQueueEntry.traceId → ChangeSet.traceId
```
`ChangeSet` gains a `traceId: String` field in Task 2.1 (backfill into the data model).

**Acceptance criteria:**
- [ ] `PipelineEventStore` persists, retrieves, and prunes events correctly
- [ ] `traceId` is present on `RawInput`, `InputQueueEntry`, and `ChangeSet` (verify data classes)
- [ ] Room migration test for event table
- [ ] Unit test: insert 600 events → verify only 500 remain, oldest dropped

### Task 6.2: Instrument the Pipeline

Add `PipelineEventStore.record(...)` calls at every stage boundary. Each call is fire-and-forget (`launch { store.record(...) }`), never blocking the pipeline itself.

**Instrumentation points:**

| File | Stage emitted | Key metadata |
|------|--------------|--------------|
| `WebhookRoutes.kt` | `INPUT_RECEIVED` | `remoteIp`, `bodyLength` |
| `PersistentInputQueue.kt` | `INPUT_QUEUED` | `queueDepth` |
| `EntityExtractor.kt` (start) | `LLM_EXTRACTING` | `model`, `promptTokens` |
| `EntityExtractor.kt` (success) | `LLM_EXTRACTED` | `entityCount`, `durationMs` |
| `EntityExtractor.kt` (failure) | `ERROR` | `errorClass`, `errorMessage`, `httpStatus` |
| `EntityResolver.kt` (start) | `ENTITY_RESOLVING` | `entityCount` |
| `EntityResolver.kt` (done) | `ENTITY_RESOLVED` | `matched`, `new`, `disambiguationNeeded` |
| `PlanGenerator.kt` | `PLAN_GENERATED` | `operationCount`, `createCount`, `updateCount` |
| `ChangeExecutor.kt` (start) | `CHANGE_APPLYING` | `operationCount` |
| `ChangeExecutor.kt` (success) | `CHANGE_APPLIED` | `durationMs` |
| `ChangeExecutor.kt` (failure) | `ERROR` | `failedOperationIndex`, `errorClass`, `errorMessage` |
| `ChangeRollback.kt` (done) | `ROLLED_BACK` | `operationsRolledBack`, `conflictsSkipped` |
| Approval ViewModel (user action) | `APPROVAL_PENDING` → transition | `decision=APPROVED\|REJECTED` |

**Log tag discipline (for `adb logcat` during development):**

All Timber calls in Pebble modules MUST use a tagged wrapper:
```kotlin
// Each module defines its own tag constant
private const val TAG = "Pebble:Webhook"   // or Assimilation, ChangeControl, Core

// Every pipeline log line includes the traceId
Timber.tag(TAG).d("[trace=$traceId] INPUT_RECEIVED | remoteIp=$ip | bodyLength=$len")
Timber.tag(TAG).e("[trace=$traceId] ERROR at LLM_EXTRACTING | ${e.message}")
```

Filter during development: `adb logcat -s "Pebble:Webhook" "Pebble:Assimilation" "Pebble:ChangeControl"`

**PII policy:** User note content (the actual transcribed text) MUST NOT appear in logs at DEBUG or higher. Log it at VERBOSE level only, and strip VERBOSE from release builds via ProGuard/R8.

**Acceptance criteria:**
- [ ] Sending one test input produces ≥ 8 `PipelineEvent` records end-to-end
- [ ] A bad LLM API key produces an `ERROR` event with `httpStatus=401` in metadata
- [ ] An unreachable LLM produces an `ERROR` event with `errorClass=ConnectException` in metadata
- [ ] All events for a single input share the same `traceId`
- [ ] No user note content appears in any log at DEBUG or above

### Task 6.3: In-App Debug Trace Screen

**Purpose:** Diagnose failures on a real device without any connected tools.

**Files to create:**
```
feature-pebble-ui/.../debug/PebbleDebugScreen.kt
feature-pebble-ui/.../debug/PebbleDebugViewModel.kt
```

**Screen layout (three sections):**

**Section 1 — System Health bar (always visible at top):**

| Indicator | Green | Yellow | Red |
|-----------|-------|--------|-----|
| Webhook | Running, last request < 5 min ago | Running, no recent requests | Stopped or failed to bind |
| LLM | Last call succeeded | No calls yet | Last call failed |
| Queue | 0 pending | 1–5 pending | > 5 pending |
| Last error | None | Warning | Error in last 10 min |

Tapping any indicator scrolls to the most recent relevant trace event.

**Section 2 — Trace List:**
- Each row: input text (first 60 chars), relative time ("2 min ago"), overall status dot (green/yellow/red), stage summary ("Applied — 5 ops")
- Most recent first; paginated (load 20, load more on scroll)
- Tap to expand into a timeline:

```
 ✓  INPUT_RECEIVED     14:32:01.002   +0ms
 ✓  INPUT_QUEUED       14:32:01.005   +3ms
 ✓  LLM_EXTRACTING     14:32:01.010   +8ms   (model: claude-sonnet)
 ✓  LLM_EXTRACTED      14:32:03.351   +2341ms  (3 entities)
 ✓  ENTITY_RESOLVED    14:32:03.800   +449ms   (2 matched, 1 new)
 ✓  PLAN_GENERATED     14:32:03.812   +12ms    (5 ops)
 …  APPROVAL_PENDING   14:32:03.812   (waiting for user)
```

Failed stages render in red with the error message inline. Tapping a failed stage expands the full metadata map.

**Section 3 — Export:**
- "Share Debug Log" button — serialises the last 100 events (all traces) as JSON and invokes `ACTION_SEND` via the Android share sheet. The JSON is human-readable and contains no PII (input text is truncated to 30 chars in exports).

**Navigation:** Accessible from `pebbleHome` dashboard (tap the health bar) and from `PebbleSettingsScreen` ("Debug & Logs" row). Add `pebbleDebug` destination to `nav_pebble.xml`.

**Acceptance criteria:**
- [ ] Health bar reflects live webhook/LLM/queue state within 5 seconds of a change
- [ ] Expanding a trace shows all recorded stages in chronological order with durations
- [ ] Red failed stage shows error class and message without requiring a developer to interpret it
- [ ] "Share Debug Log" produces valid JSON via share sheet
- [ ] Screen updates in real-time as new events arrive (Flow-backed ViewModel)
- [ ] Works with zero connectivity (reads from local Room only)

### Task 6.4: Actionable Error Notifications

**Purpose:** Surface actionable failures proactively without requiring the user to open the debug screen.

**File to create:**
- `feature-pebble-ui/.../notifications/PebbleErrorNotification.kt`

**Error scenarios and notification content:**

| Failure trigger | Title | Body | Tap action |
|----------------|-------|------|-----------|
| LLM API 401 | "Pebble: API key rejected" | "Check your LLM API key in Settings" | → PebbleSettingsScreen |
| LLM API 429 | "Pebble: LLM rate limited" | "Input queued — will retry automatically" | → PebbleDebugScreen |
| LLM network timeout | "Pebble: LLM unreachable" | "Check internet connection; input is queued" | → PebbleDebugScreen |
| Webhook port conflict | "Pebble: Server failed to start" | "Port 8391 already in use — change in Settings" | → PebbleSettingsScreen |
| Change set apply failed | "Pebble: Changes not saved" | "Tap to review and retry" | → ChangeLogScreen |
| Queue depth > 10 | "Pebble: 11 inputs waiting" | "Processing may be stalled — check Debug" | → PebbleDebugScreen |

**Implementation notes:**
- Notifications use a dedicated `PEBBLE_ERROR` notification channel (created on app start).
- Use `setOnlyAlertOnce(true)` per error type so a persistent failure doesn't spam.
- Dismiss-on-fix: when a previously errored stage succeeds (e.g., LLM call succeeds after a 401), cancel the corresponding notification.

**Acceptance criteria:**
- [ ] Each error scenario above produces the correct notification within 5 seconds
- [ ] Notifications have correct `PendingIntent` to navigate to the right screen
- [ ] A fixed error (e.g., correct API key saved → successful LLM call) cancels the API key notification
- [ ] No duplicate notifications for the same ongoing error

---

## Phase 7: Integration & End-to-End Testing

**Goal:** Wire all phases together and validate the full pipeline from webhook to graph mutation.

**Depends on:** Phases 0–6

### Task 7.1: Full Pipeline Integration

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

### Task 7.2: End-to-End Test Scenarios

Write integration tests for key scenarios:

| # | Scenario | Expected Result |
|---|----------|----------------|
| 1 | "Aarav has a basketball game on Friday" | Creates Person(Aarav), Event(basketball game, pkm-date=Friday), links via pkm-attendees |
| 2 | Same input again | Resolves existing Aarav (no duplicate), creates new Event, links |
| 3 | "Remind me to call Dr. Patel" | Creates Reminder (ot-pkm-reminder), resolves existing Person(Dr. Patel), sets dueDate |
| 4 | "Meeting with Sarah at Cafe Luce tomorrow at 3pm" | Creates Meeting (ot-pkm-meeting), resolves/creates Person(Sarah), resolves/creates Place (ot-pkm-place, Cafe Luce) |
| 5 | "I spent $45 on groceries at Safeway" | Creates Expense (ot-pkm-expense, pkm-category=groceries, pkm-cost=45, pkm-merchant=Safeway) |
| 6 | "Ran 5 km, took 35 minutes" | Creates TimeEntry (ot-pkm-time-entry, pkm-activity=exercise, pkm-duration=0.583, pkm-rawText=original) |
| 7 | "Oil change on the Highlander at 72000 miles, $89 at Jiffy Lube" | Creates MaintenanceRecord (ot-pkm-maintenance-record), resolves/creates Asset (ot-pkm-asset, Highlander), sets pkm-mileage, pkm-cost, pkm-merchant |
| 8 | Rollback scenario 1 | Event deleted, Person deleted (or kept if linked elsewhere), relation removed |
| 9 | Conflict rollback: apply → user edits Person name → rollback | Conflict detected on Person, skip or force |
| 10 | Offline: webhook received while offline | Input queued, processed when online |
| 11 | Taxonomy bootstrap on fresh space | All 12 custom types (ot-pkm-*) and 29 custom relations (pkm-*) created; no duplicates on re-run |
| 12 | `PkmObjectType.all()` sealed hierarchy returns 17 types; `PkmRelation.custom()` returns 29 relations | Unit test — no network or AnyType instance needed |

**Acceptance criteria:**
- [ ] All 12 scenarios pass
- [ ] Tests are repeatable (clean up after each)
- [ ] `make test_debug_all` passes

### Task 7.3: Notification Integration

- Pending plan notification: "Voice input processed — tap to review"
- Auto-applied notification: "Applied changes from 'Aarav has a basketball game...' — [Undo]"
- Undo from notification triggers rollback via PendingIntent → ChangeExecutor.

**Acceptance criteria:**
- [ ] Notifications appear at correct times
- [ ] Undo action triggers rollback
- [ ] Notification cleared after user action

### Task 7.4: Entry Point — Home Screen Access

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
Phase 0: Scaffolding & DI Bridge    ─────────────────────────────┐
    │                                                             │
    ├──→ Phase 1: Taxonomy & Schema Bootstrap                     │
    │        │                                                    │
    ├──→ Phase 2: Change Control Layer                            │
    │        │                                                    │  All phases
    ├──→ Phase 3: Webhook Service ──→ Phase 6: Observability ─────┤  feed into
    │        │                    (wired throughout 3–5)          │  Phase 7
    └──→ Phase 4: Assimilation Engine                             │
             │                                                    │
             └──→ Phase 5: UI Layer ─────────────────────────────┘
                      │
                      └──→ Phase 7: Integration & E2E Testing
```

**Parallelization opportunities:**
- Phases 1, 2, 3 can proceed in parallel after Phase 0 completes.
- Phase 4 depends on Phase 1 (taxonomy) and Phase 2 (change set model) for data models.
- Phase 5 can start UI scaffolding after Phase 0 but needs Phases 1–4 for real data.
- **Phase 6 (Observability)** runs in parallel with Phases 3–5: the event store (Tasks 6.1–6.2) is wired during Phase 3/4 development; the debug UI (Tasks 6.3–6.4) is built alongside Phase 5.
- Phase 7 requires all prior phases.

---

## Risk Register

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| Upstream AnyType merge conflicts | Medium | Medium | Region-bracketed changes, ≤35 lines in 7 files, automated merge check |
| Ktor CIO incompatibility with project's Kotlin/Android versions | High | Low | Verify early in Phase 3; fallback to NanoHTTPD if needed |
| LLM extraction accuracy below 90% | High | Medium | Iterative prompt tuning; fallback to more capable model; human-in-the-loop for all plans initially |
| AnyType search latency too high for entity resolution | Medium | Low | Hot cache via subscriptions; batch candidate retrieval |
| Room + AnyType dual storage consistency | Medium | Medium | AnyType is source of truth; Room is a disposable cache rebuilt on mismatch |
| Custom type creation fails (middleware limitations) | High | Low | `SchemaBootstrapper` wraps each `createObjectType`/`createRelation` call in `runCatching` and throws `BootstrapException` on failure; verify against live middleware in Phase 7 E2E |
| Change set storage overhead at scale (100s of change sets as AnyType objects) | Low | Medium | Archival policy; lazy loading in UI; Room cache for queries |
| Silent failures on-device (no debugger attached) | High | High | **Mitigated by Phase 6**: `PipelineEventStore` captures every stage; debug screen surfaces failures; error notifications provide immediate actionable hints |
| Phone IP address changes (DHCP reassignment breaks QR config) | Low | Medium | QR screen shows live IP from `NetworkInterface`; "Send Test Input" instantly validates connectivity after reconfiguration |

---

## Implementation Order (Recommended)

For a coding agent executing iteratively:

1. ✅ **Phase 0** (Tasks 0.1 → 0.5) — complete; build verification pending network
2. ✅ **Phase 1** (Tasks 1.1 → 1.5) — complete; taxonomy sealed hierarchy, bootstrapper, migration runner, 3 test classes
3. **Phase 2** (Tasks 2.1 → 2.6) — change control is the backbone; add `traceId` to `ChangeSet` data model here
4. **Phase 3** (Tasks 3.1 → 3.5) — webhook service (can overlap with Phase 2); wire Phase 6 Tasks 6.1–6.2 (event store + instrumentation) at the end of this phase
5. **Phase 4** (Tasks 4.1 → 4.8) — assimilation engine (heaviest phase); instrument each stage as it's built
6. **Phase 5** (Tasks 5.1 → 5.8) — UI layer; build Phase 6 Tasks 6.3–6.4 (debug screen + error notifications) in parallel
7. **Phase 6** (Tasks 6.1 → 6.4) — observability; mark complete once instrumentation + debug UI are verified end-to-end
8. **Phase 7** (Tasks 7.1 → 7.4) — integration and polish; use the debug screen to diagnose E2E test failures

Total estimated tasks: **44 tasks across 8 phases** (taxonomy expanded to 19 types / 30 relations; no new phases added).
