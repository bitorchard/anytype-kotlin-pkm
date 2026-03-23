# Research: PKM Taxonomy & Evolution

## Executive Summary

AnyType's type system treats both object types and relations as first-class objects in the graph, identified by unique keys (e.g., `ot-page`, `ot-human`) with details stored as `Struct` maps. The recommended approach is a **hybrid taxonomy** combining AnyType-native types where built-in types exist (Human, Task, Note, Bookmark, Project) with custom types for PKM-specific concepts (Event, Reminder, Place, Organization, Topic, TimeEntry), all defined in code as a Kotlin sealed hierarchy that maps to AnyType type/relation unique keys. Relations follow the same pattern — reuse built-in relations where available and create custom OBJECT-format relations for PKM-specific links. Schema evolution uses a versioned migration system with forward-only additive changes and taxonomy-driven LLM prompt injection.

## Context & Constraints

### Hard Constraints
- AnyType types and relations are **objects** — they have IDs, live in a space, and are managed through the middleware RPC layer.
- Object details are stored as `Struct` (`Map<Id, Any?>`) with string keys from the `Relations` constants.
- Types carry `recommendedRelations` (featured, sidebar, hidden, file) as lists of relation **object IDs**.
- Relations have a `RelationFormat` enum: SHORT_TEXT, LONG_TEXT, NUMBER, DATE, STATUS, TAG, CHECKBOX, URL, EMAIL, PHONE, OBJECT, FILE, EMOJI, RELATIONS.
- Custom types are created via `Rpc.Object.CreateObjectType` with details struct; custom relations via `Rpc.Object.CreateRelation`.
- The taxonomy must serve as the schema for the LLM-based assimilation engine.
- Changes to taxonomy must not break existing objects or require complex data migrations.

### AnyType's Built-in Types

From `ObjectTypeIds.kt`:

| Unique Key | Description | Relevance to PKM |
|-----------|-------------|-------------------|
| `ot-page` | Generic page | General notes |
| `ot-note` | Quick note | Fleeting thoughts |
| `ot-task` | Task with checkbox | Action items |
| `ot-human` | Person | People tracking |
| `ot-profile` | User profile / person | Current user |
| `ot-bookmark` | Web bookmark | Reference links |
| `ot-project` | Project | Project organization |
| `ot-set` | Object set (query) | Dynamic collections |
| `ot-collection` | Manual collection | Curated groups |
| `ot-date` | Date object | Temporal references |

**Missing for PKM:** Event, Reminder, Place, Organization, Topic/Area, Meeting, Contact Info, Asset, MaintenanceRecord, Expense, HealthMetric, MediaItem, Decision.

### AnyType's Built-in Relations

From `Relations.kt` (partial — the file has 230+ constants):

| Key | Type | PKM Use |
|-----|------|---------|
| `name` | SHORT_TEXT | Object name |
| `description` | LONG_TEXT | Description |
| `type` | OBJECT | Object type reference |
| `tag` | TAG | Tagging |
| `status` | STATUS | Status tracking |
| `assignee` | OBJECT | Task assignment |
| `dueDate` | DATE | Deadlines |
| `createdDate` | DATE | Creation timestamp |
| `done` | CHECKBOX | Task completion |
| `source` | URL | Source URL |
| `phone` | PHONE | Phone number |
| `email` | EMAIL | Email address |
| `url` | URL | Web URL |
| `priority` | NUMBER | Priority level |

**Missing for PKM:** `participatesIn` (OBJECT), `scheduledFor` (DATE/OBJECT), `locatedAt` (OBJECT), `belongsTo` (OBJECT), `context` (TAG/OBJECT), `area` (OBJECT), `attendees` (OBJECT), `person` (OBJECT), `activity` (TAG), `date` (DATE), `duration` (NUMBER), `recurrence` (SHORT_TEXT). *(All except `scheduledFor` and `recurrence` are addressed in custom relations below.)*

## Options Analysis

### 1. Initial Taxonomy Design

#### Core Object Types

**Tier 1 — Reuse Built-in Types (no creation needed):**

| Type | AnyType Key | Layout | Key Relations |
|------|-------------|--------|---------------|
| Person | `ot-human` | PROFILE | name, email, phone, organization, tags |
| Task | `ot-task` | TODO | name, assignee, dueDate, done, priority, context |
| Note | `ot-note` | NOTE | name, description, tags, source |
| Project | `ot-project` | BASIC | name, description, status, dueDate, members |
| Bookmark | `ot-bookmark` | BOOKMARK | name, source, description, tags |

**Tier 2 — Custom Types (must create):**

| Type | Unique Key | Layout | Key Relations |
|------|-----------|--------|---------------|
| Event | `ot-pkm-event` | BASIC | name, date, endDate, location, attendees, description |
| Reminder | `ot-pkm-reminder` | TODO | name, dueDate, relatedTo, done, context |
| Place | `ot-pkm-place` | BASIC | name, address, coordinates, description |
| Organization | `ot-pkm-org` | BASIC | name, description, members, website |
| Topic | `ot-pkm-topic` | BASIC | name, description, relatedTopics, tags |
| Meeting | `ot-pkm-meeting` | BASIC | name, date, attendees, notes, actionItems |
| VoiceInput | `ot-pkm-voice-input` | NOTE | rawText, processedDate, status, changeSetId |
| TimeEntry | `ot-pkm-time-entry` | BASIC | person, activity, date, duration, startDate, endDate, relatedTo, rawText |
| Asset | `ot-pkm-asset` | BASIC | name, make, model, year, serialNumber, purchaseDate, purchasePrice, warrantyExpiry, registrationExpiry, insuranceExpiry |
| MaintenanceRecord | `ot-pkm-maintenance-record` | BASIC | asset, serviceType, date, mileage, cost, provider, description, rawText |
| Expense | `ot-pkm-expense` | BASIC | category, cost, merchant, date, relatedTo, rawText |
| HealthMetric | `ot-pkm-health-metric` | BASIC | metric, value, unit, date, person, rawText |
| MediaItem | `ot-pkm-media-item` | BASIC | name, mediaType, mediaStatus, rating, person, area, description |
| Decision | `ot-pkm-decision` | NOTE | name, rationale, alternatives, date, relatedTo, rawText |

#### Core Relations

**Reuse Built-in:**
- `name`, `description`, `tag`, `status`, `assignee`, `dueDate`, `done`, `source`, `phone`, `email`, `url`, `priority`, `createdDate`

**Custom Relations (must create):**

| Relation | Key | Format | Object Types | Description |
|----------|-----|--------|-------------|-------------|
| participatesIn | `pkm-participatesIn` | OBJECT | Event, Meeting | Links Person → Event/Meeting |
| locatedAt | `pkm-locatedAt` | OBJECT | Place | Links Event/Org → Place |
| belongsTo | `pkm-belongsTo` | OBJECT | Organization | Links Person → Organization |
| attendees | `pkm-attendees` | OBJECT | Person | Links Event/Meeting → Person(s) |
| relatedTo | `pkm-relatedTo` | OBJECT | (any) | Generic relationship |
| context | `pkm-context` | TAG | — | GTD-style context (@home, @work) |
| area | `pkm-area` | OBJECT | Topic | PARA-style area of responsibility |
| startDate | `pkm-startDate` | DATE | — | Event/meeting start |
| endDate | `pkm-endDate` | DATE | — | Event/meeting end |
| location | `pkm-location` | OBJECT | Place | Event/meeting location |
| rawText | `pkm-rawText` | LONG_TEXT | — | Original transcribed text |
| changeSetId | `pkm-changeSetId` | SHORT_TEXT | — | Link to change control record |
| person | `pkm-person` | OBJECT | Person | Links TimeEntry → Person (who performed the activity) |
| activity | `pkm-activity` | TAG | — | Activity type (@work, @video-games, @exercise, etc.) — TAG ensures filterable enum |
| date | `pkm-date` | DATE | — | Actual date of activity (distinct from createdDate which is log date) |
| duration | `pkm-duration` | NUMBER | — | Duration in decimal hours; computed from startDate/endDate or stated directly |
| list | `pkm-list` | OBJECT | Collection | Links Task/ListItem → parent Collection; makes list membership queryable via SearchObjects |
| quantity | `pkm-quantity` | SHORT_TEXT | — | Item quantity with unit ("2 lbs", "1 dozen") for list items |
| asset | `pkm-asset` | OBJECT | Asset | Links MaintenanceRecord/Task/Note → parent Asset object |
| mileage | `pkm-mileage` | NUMBER | — | Odometer reading at time of service (MaintenanceRecord) or current reading (Asset) |
| cost | `pkm-cost` | NUMBER | — | Monetary cost in local currency; reusable on MaintenanceRecord and other expense-bearing types |
| category | `pkm-category` | TAG | — | Spending/activity category (groceries, fuel, dining, utilities, etc.) |
| merchant | `pkm-merchant` | SHORT_TEXT | — | Where money was spent; name of store, vendor, or payee |
| metric | `pkm-metric` | TAG | — | What was measured (weight, blood-pressure, sleep, steps, calories, etc.) |
| value | `pkm-value` | NUMBER | — | Numeric measurement; paired with pkm-unit for meaning |
| unit | `pkm-unit` | SHORT_TEXT | — | Unit of measurement (lbs, hours, mmHg, steps, etc.) |
| mediaType | `pkm-mediaType` | TAG | — | Media format (book, movie, show, podcast, article) |
| mediaStatus | `pkm-mediaStatus` | STATUS | — | Consumption status: want / in-progress / completed / abandoned |
| rating | `pkm-rating` | NUMBER | — | User rating 1–5; applies to MediaItem and any reviewable object |
| rationale | `pkm-rationale` | LONG_TEXT | — | Why a decision was made; the reasoning captured at decision time |
| alternatives | `pkm-alternatives` | LONG_TEXT | — | What options were considered but not chosen |

### 2. Mapping to AnyType's Type System

#### Option A: Reuse Built-in Types Exclusively
Map all PKM concepts to existing AnyType types (e.g., Event → Page with specific relations).

| Aspect | Assessment |
|--------|-----------|
| **Pros** | No custom type creation; zero risk of type/schema conflicts with upstream. |
| **Cons** | Loses semantic precision; "Person" is `ot-human` but "Event" is just a `ot-page` with no distinguishing type; harder for the assimilation engine to reason about types. |

#### Option B: Custom Types for Everything
Create custom types for all PKM concepts, even where built-in types exist.

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Full control; clean namespace; no dependency on built-in type behavior. |
| **Cons** | Duplicates existing types (users may have `ot-human` objects they expect to be recognized); loses AnyType's built-in templates, icons, and recommended relations for known types. |

#### Option C: Hybrid — Reuse Where Possible, Custom for Gaps (Recommended)
Use built-in types when they match semantically (`ot-human` for Person, `ot-task` for Task), create custom types only for concepts AnyType doesn't natively model (Event, Reminder, Place, Organization, Topic).

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Leverages AnyType's existing UX for known types; users see familiar icons/layouts; custom types only where needed; assimilation engine can reference both. |
| **Cons** | Must handle built-in type evolution (if AnyType adds an Event type upstream, we need to migrate). |

**Recommendation: Option C.** The hybrid approach respects the user's existing AnyType experience while extending it. The `pkm-` prefix on custom type unique keys prevents conflicts.

### 3. Taxonomy Representation

#### Option A: Code-Defined (Kotlin Sealed Hierarchy)
Define the taxonomy as Kotlin types with compile-time validation:

```kotlin
sealed class PkmObjectType(
    val uniqueKey: String,
    val displayName: String,
    val isBuiltIn: Boolean,
    val layout: ObjectType.Layout,
    val requiredRelations: List<PkmRelation>,
    val optionalRelations: List<PkmRelation>
) {
    object Person : PkmObjectType("ot-human", "Person", true, Layout.PROFILE, ...)
    object Event : PkmObjectType("ot-pkm-event", "Event", false, Layout.BASIC, ...)
    object Task : PkmObjectType("ot-task", "Task", true, Layout.TODO, ...)
    // ...
}
```

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Type-safe; IDE support; compile-time validation; easy to generate LLM prompt schema from; easy to test. |
| **Cons** | Requires code change + rebuild to evolve; users can't customize without forking. |
| **Maintainability** | High — clear, explicit, documented in code. |

#### Option B: Configuration-Defined (JSON/YAML)
Taxonomy defined in a configuration file loaded at runtime.

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Runtime flexibility; users can customize without code changes. |
| **Cons** | No compile-time validation; schema drift risk; harder to test; must write a parser. |

#### Option C: AnyType-Native
Read the taxonomy directly from AnyType's own type/relation objects at runtime.

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Always in sync with actual AnyType state; users can create types in AnyType UI and they automatically become part of the taxonomy. |
| **Cons** | No control over what types exist; assimilation engine can't know about types until they're created; bootstrapping problem (who creates the initial types?); must handle arbitrary user-created types. |

#### Option D: Hybrid — Code Core + AnyType Extension (Recommended)
Core taxonomy defined in code (sealed hierarchy). At runtime, the taxonomy is **augmented** by reading AnyType's actual type/relation objects — user-created types can be discovered and optionally included.

```kotlin
class TaxonomyProvider(
    private val storeOfObjectTypes: StoreOfObjectTypes,
    private val storeOfRelations: StoreOfRelations
) {
    val coreTypes: List<PkmObjectType> = PkmObjectType.all()  // code-defined
    
    suspend fun effectiveTypes(space: SpaceId): List<EffectiveType> {
        val anytypeTypes = storeOfObjectTypes.getAll()
        return coreTypes.map { core ->
            val anytypeType = anytypeTypes.find { it.uniqueKey == core.uniqueKey }
            EffectiveType(
                definition = core,
                anytypeId = anytypeType?.id,  // null if not yet created in space
                exists = anytypeType != null
            )
        }
    }
}
```

| Aspect | Assessment |
|--------|-----------|
| **Pros** | Best of both worlds — compile-time safety for core taxonomy, runtime discovery for extensions; assimilation engine gets a well-defined schema; user customization possible. |
| **Cons** | Slightly more complex; must handle "core type not yet created in space" bootstrapping. |

**Recommendation: Option D.** The code-defined core ensures the assimilation engine always has a well-known schema, while AnyType-native extension allows the taxonomy to grow organically.

### 4. Schema Evolution Strategy

#### Principles

1. **Additive only**: New types and relations are added; existing ones are never removed or have their format changed.
2. **Versioned taxonomy**: Each taxonomy release has a version number. The `TaxonomyProvider` knows its version and can detect if the persisted taxonomy in a space is outdated.
3. **Forward migration**: On version mismatch, a migration function creates missing types and relations.
4. **No destructive migration**: Renaming → create new + mark old as deprecated. Format changes → create new relation with new format.

#### Migration Mechanism

```kotlin
data class TaxonomyMigration(
    val fromVersion: Int,
    val toVersion: Int,
    val steps: List<MigrationStep>
)

sealed class MigrationStep {
    data class CreateType(val type: PkmObjectType) : MigrationStep()
    data class CreateRelation(val relation: PkmRelation) : MigrationStep()
    data class AddRelationToType(val typeKey: String, val relationKey: String) : MigrationStep()
    data class DeprecateType(val typeKey: String, val replacementKey: String?) : MigrationStep()
}
```

**Version tracking**: Store the current taxonomy version as a detail on a sentinel "PebbleTaxonomyConfig" object in the space (or in a local Room table in `pkm-core`).

**When migration runs**: On space open, `TaxonomyProvider.ensureSchema(space)` checks version and applies pending migrations.

#### Evolution Scenarios

| Scenario | Strategy |
|----------|----------|
| Add new type (e.g., "Habit") | Add to sealed hierarchy; add migration step `CreateType`; increment version |
| Add new relation to existing type | Add migration step `AddRelationToType`; increment version |
| Rename a type | Create new type; add migration step mapping old → new; keep old type for backward compat |
| Change relation format | Create new relation with new format; update taxonomy to use new key; old objects retain old key |
| User creates custom type | Discovered at runtime via `StoreOfObjectTypes`; not part of core taxonomy but available for entity resolution |

### 5. Taxonomy and the Assimilation Engine

#### LLM Prompt Injection (Recommended)

The taxonomy is serialized to a structured prompt that the LLM receives as system context:

```
You are a knowledge graph assistant. Extract entities and relationships from the input text.

Available object types:
- Person (fields: name, email, phone, organization)
- Event (fields: name, date, endDate, location, attendees, description)
- Task (fields: name, assignee, dueDate, priority, context)
- Reminder (fields: name, dueDate, relatedTo, context)
- Place (fields: name, address, description)
- Organization (fields: name, description, website)
- Topic (fields: name, description)
- Meeting (fields: name, date, attendees, notes, actionItems)
- TimeEntry (fields: person, activity, date, duration, startDate, endDate)
- Asset (fields: name, make, model, year, serialNumber, registrationExpiry, insuranceExpiry)
- MaintenanceRecord (fields: asset, serviceType, date, mileage, cost, provider, description)
- Expense (fields: category, cost, merchant, date)
- HealthMetric (fields: metric, value, unit, date, person)
- MediaItem (fields: name, mediaType, mediaStatus, rating, description)
- Decision (fields: name, rationale, alternatives, date)

Available relationships:
- participatesIn: Person → Event/Meeting
- attendees: Event/Meeting → Person
- locatedAt: Event/Organization → Place
- belongsTo: Person → Organization
- relatedTo: any → any
- assignee: Task → Person

Output format: JSON with entities[] and relationships[] arrays.
```

**Auto-generation**: The `PkmObjectType` sealed hierarchy includes a `toPromptDescription()` method:

```kotlin
fun PkmObjectType.toPromptDescription(): String = buildString {
    appendLine("- ${displayName} (fields: ${requiredRelations.joinToString { it.displayName }})")
}

fun generateTaxonomyPrompt(): String = buildString {
    appendLine("Available object types:")
    PkmObjectType.all().forEach { appendLine(it.toPromptDescription()) }
    appendLine()
    appendLine("Available relationships:")
    PkmRelation.objectRelations().forEach {
        appendLine("- ${it.displayName}: ${it.sourceTypes} → ${it.targetTypes}")
    }
}
```

This means **taxonomy evolution automatically updates the LLM prompt** — no separate prompt maintenance.

#### Handling Unknown Concepts

When the LLM extracts an entity type not in the taxonomy:
1. Map to closest known type (e.g., "Restaurant" → Place + tag "restaurant").
2. Flag as "unrecognized type" in the assimilation plan for user review.
3. Track frequency — if a concept appears repeatedly, suggest taxonomy extension.

### 6. PKM Framework Mapping

#### PARA (Projects, Areas, Resources, Archives)

| PARA Concept | AnyType Mapping |
|--------------|----------------|
| Projects | `ot-project` with `status` relation (active/completed) |
| Areas | `ot-pkm-topic` with `area` relation flag |
| Resources | `ot-note` / `ot-bookmark` / `ot-page` with topic links |
| Lists | `ot-collection` with `ot-task` items linked via `pkm-list`; e.g., grocery list, packing list |
| Archives | Objects with `isArchived = true` (built-in AnyType relation) |

#### Zettelkasten

| Concept | AnyType Mapping |
|---------|----------------|
| Fleeting notes | `ot-note` from voice input (ephemeral) |
| Literature notes | `ot-note` with `source` relation |
| Permanent notes | `ot-page` with `relatedTo` links |
| Connections | `relatedTo` OBJECT relations between notes |
| MOCs (Maps of Content) | `ot-collection` grouping related notes |

#### GTD (Getting Things Done)

| Concept | AnyType Mapping |
|---------|----------------|
| Inbox | `VoiceInput` objects pending processing |
| Next Actions | `ot-task` with `context` tag |
| Projects | `ot-project` |
| Contexts | `context` TAG relation (@home, @work, @phone, @errands) |
| Waiting For | `ot-task` with `status = "waiting"` |
| Someday/Maybe | `ot-task` with `status = "someday"` |
| Reference | `ot-note` / `ot-bookmark` |
| Checklists | `ot-collection` with `ot-task` items linked via `pkm-list`; voice input appends items to named list |

#### Asset Tracking Decision Framework

Not every piece of asset data needs a first-class object. Apply this tiered approach:

| Tier | Use When | Examples |
|------|----------|----------|
| Scalar relation on Asset | One value per asset, no relationships, quick-reference | VIN, license plate, registration expiry, insurance expiry, current mileage |
| Note linked via `pkm-relatedTo` | Prose/reference material you browse but do not query | Insurance policy details, warranty terms, dealer receipts |
| Task linked via `pkm-relatedTo` | Future actions or renewals with a due date | Registration renewal reminder, insurance renewal |
| MaintenanceRecord object | Multiple instances, cross-object queries, own attributes | Oil changes, tire rotations, repairs — "show all oil changes", "total spend this year" |

#### Synthesis: Our Taxonomy Supports All Three

The proposed taxonomy naturally accommodates PARA, Zettelkasten, and GTD patterns without forcing any single methodology. The user's voice input is processed through the assimilation engine, which creates objects using whichever types fit the content — tasks, events, notes, people — and the user can then organize them using their preferred methodology via AnyType's native Set/Collection features.

## Recommended Approach

### Concrete Initial Taxonomy

**Object Types (17):**

| # | Type | Unique Key | Built-in? | Layout |
|---|------|-----------|-----------|--------|
| 1 | Person | `ot-human` | Yes | PROFILE |
| 2 | Task | `ot-task` | Yes | TODO |
| 3 | Note | `ot-note` | Yes | NOTE |
| 4 | Project | `ot-project` | Yes | BASIC |
| 5 | Bookmark | `ot-bookmark` | Yes | BOOKMARK |
| 6 | Event | `ot-pkm-event` | No | BASIC |
| 7 | Reminder | `ot-pkm-reminder` | No | TODO |
| 8 | Place | `ot-pkm-place` | No | BASIC |
| 9 | Organization | `ot-pkm-org` | No | BASIC |
| 10 | Topic | `ot-pkm-topic` | No | BASIC |
| 11 | TimeEntry | `ot-pkm-time-entry` | No | BASIC |
| 12 | Asset | `ot-pkm-asset` | No | BASIC |
| 13 | MaintenanceRecord | `ot-pkm-maintenance-record` | No | BASIC |
| 14 | Expense | `ot-pkm-expense` | No | BASIC |
| 15 | HealthMetric | `ot-pkm-health-metric` | No | BASIC |
| 16 | MediaItem | `ot-pkm-media-item` | No | BASIC |
| 17 | Decision | `ot-pkm-decision` | No | NOTE |

**Custom Relations (29):**

| # | Relation | Key | Format | Target Types |
|---|----------|-----|--------|-------------|
| 1 | Participates In | `pkm-participatesIn` | OBJECT | Event, Meeting |
| 2 | Located At | `pkm-locatedAt` | OBJECT | Place |
| 3 | Belongs To | `pkm-belongsTo` | OBJECT | Organization |
| 4 | Attendees | `pkm-attendees` | OBJECT | Person |
| 5 | Related To | `pkm-relatedTo` | OBJECT | (any) |
| 6 | Context | `pkm-context` | TAG | — |
| 7 | Area | `pkm-area` | OBJECT | Topic |
| 8 | Start Date | `pkm-startDate` | DATE | — |
| 9 | End Date | `pkm-endDate` | DATE | — |
| 10 | Raw Text | `pkm-rawText` | LONG_TEXT | — |
| 11 | Person | `pkm-person` | OBJECT | Person |
| 12 | Activity | `pkm-activity` | TAG | — |
| 13 | Date | `pkm-date` | DATE | — |
| 14 | Duration | `pkm-duration` | NUMBER | — |
| 15 | List | `pkm-list` | OBJECT | Collection |
| 16 | Quantity | `pkm-quantity` | SHORT_TEXT | — |
| 17 | Asset | `pkm-asset` | OBJECT | Asset |
| 18 | Mileage | `pkm-mileage` | NUMBER | — |
| 19 | Cost | `pkm-cost` | NUMBER | — |
| 20 | Category | `pkm-category` | TAG | — |
| 21 | Merchant | `pkm-merchant` | SHORT_TEXT | — |
| 22 | Metric | `pkm-metric` | TAG | — |
| 23 | Value | `pkm-value` | NUMBER | — |
| 24 | Unit | `pkm-unit` | SHORT_TEXT | — |
| 25 | Media Type | `pkm-mediaType` | TAG | — |
| 26 | Media Status | `pkm-mediaStatus` | STATUS | — |
| 27 | Rating | `pkm-rating` | NUMBER | — |
| 28 | Rationale | `pkm-rationale` | LONG_TEXT | — |
| 29 | Alternatives | `pkm-alternatives` | LONG_TEXT | — |

### Representation: Kotlin Sealed Hierarchy + AnyType Runtime Extension

Code-defined core taxonomy in `pkm-core`, auto-discovered extensions from `StoreOfObjectTypes` / `StoreOfRelations`.

### Evolution: Versioned Additive Migrations

Version-tracked, forward-only migrations applied on space open.

### Assimilation Alignment: Auto-Generated LLM Prompts

Taxonomy sealed hierarchy generates prompt schema; unknown concepts handled gracefully with fallback mapping.

### Validation Criteria

| Criterion | Target | How to Measure |
|-----------|--------|---------------|
| Type coverage | ≥ 90% of test voice inputs map to a known type | Test with 50+ diverse inputs |
| Relation coverage | ≥ 85% of extracted relationships map to known relations | Same test set |
| Migration safety | Zero data loss on schema upgrade | Unit test: create objects → upgrade → verify objects intact |
| Prompt accuracy | LLM produces valid extraction ≥ 95% of the time | Eval set with ground truth |
| Bootstrapping time | < 5 seconds to create all custom types/relations in a space | Benchmark |

## Open Questions

1. **Type icon strategy**: AnyType types have icons (emoji or custom). What icons should our custom types use? Should we include icon definitions in the taxonomy?
2. **Relation multiplicity**: AnyType supports `maxCount` on relations. Should `attendees` be unbounded or capped? Does `multi: true` need explicit setting?
3. **Template support**: Should custom types have default templates with pre-configured blocks (e.g., Event template with date, location, attendees sections)?
4. **Built-in type discovery**: How do we reliably check if `ot-human` exists in a space before the first assimilation? Subscription on type store?
5. **Namespace collisions**: If AnyType adds `ot-event` upstream, how do we detect and migrate from our `ot-pkm-event`?
6. **Taxonomy sync across spaces**: If the user has multiple spaces, do we replicate the taxonomy to each? Or only the active space?

## References

- `core-models/src/main/java/.../ObjectTypeIds.kt` — built-in type unique keys
- `core-models/src/main/java/.../Relations.kt` — built-in relation keys (230+)
- `core-models/src/main/java/.../ObjectWrapper.kt` — Type, Relation, Basic wrappers
- `core-models/src/main/java/.../Relation.kt` — RelationFormat enum
- `domain/src/main/java/.../types/CreateObjectType.kt` — type creation use case
- `domain/src/main/java/.../relations/CreateRelation.kt` — relation creation use case
- `protocol/src/main/proto/commands.proto` — `Rpc.Object.CreateObjectType`, `Rpc.Object.CreateRelation`
- `protocol/src/main/proto/models.proto` — `Relation`, `RelationFormat` protobuf definitions
- Tiago Forte, *Building a Second Brain* — PARA methodology
- Sönke Ahrens, *How to Take Smart Notes* — Zettelkasten methodology
- David Allen, *Getting Things Done* — GTD methodology
