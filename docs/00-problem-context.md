# Problem Context: AnyType Pebble Ring PKM Assimilation

## 1. Problem Statement

We are building a voice-driven personal knowledge management (PKM) pipeline on top of the AnyType Android client. Audio captured by a **Pebble Ring** is transcribed to text by the Pebble phone app, then delivered via webhook to our forked AnyType mobile app. The app must decompose each transcribed note into structured knowledge — objects, attributes, and relationships — and assimilate it into the user's AnyType graph, with full change control for auditing and rollback.

**Example:** The input *"Aarav has a basketball game on Friday"* should:
1. Resolve or create a **Person** object for "Aarav" (disambiguating against existing people).
2. Create an **Event** object for the basketball game, with a date of Friday.
3. Establish a relationship between Aarav and the event.
4. Present the proposed changes for approval (or auto-apply with rollback capability).

The core challenge is building an intelligent **assimilation pipeline** — context-aware entity resolution, relationship extraction, and a change-control layer — while keeping the fork **extensible** and easy to merge with upstream AnyType releases.

## 2. System Architecture Overview

```
Pebble Ring (audio) → Pebble Phone App (STT) → Webhook → Forked AnyType App
                                                              │
                                                    ┌─────────┴──────────┐
                                                    │   Webhook Service   │
                                                    └─────────┬──────────┘
                                                              │
                                                    ┌─────────┴──────────┐
                                                    │ Assimilation Engine │
                                                    │  (NLP / LLM-based) │
                                                    └─────────┬──────────┘
                                                              │
                                                    ┌─────────┴──────────┐
                                                    │  Change Control     │
                                                    │  (plan + execute)   │
                                                    └─────────┬──────────┘
                                                              │
                                                    ┌─────────┴──────────┐
                                                    │   AnyType Core      │
                                                    │ (Go middleware/JNI) │
                                                    └─────────────────────┘
```

## 3. New Service Components

### 3.1 Webhook Service
- Receives HTTP webhook events from the Pebble phone app containing transcribed text.
- Queues inputs for processing by the assimilation engine.
- Persists raw inputs for history and audit.

### 3.2 Assimilation Engine
- Decomposes natural language inputs into structured operations against the PKM taxonomy.
- **Entity extraction:** Identifies objects (people, events, places, etc.) and their attributes.
- **Entity resolution:** Searches existing AnyType objects to determine if references match existing entities or require new creation. Must handle ambiguity (e.g., multiple people named "Aarav") using contextual signals — relationship proximity, recency, frequency, and semantic similarity.
- **Relationship extraction:** Identifies connections between entities and maps them to AnyType relations.
- **Plan generation:** Produces an ordered sequence of AnyType operations (create object, set details, add relation) as a reviewable assimilation plan.

### 3.3 Change Control
- Wraps every assimilation plan in a transactional change set.
- Provides fine-grained logging: each atomic operation (create, update, link) is recorded with before/after state.
- Supports **approval workflow**: plans can be auto-applied or held for user review.
- Supports **rollback**: reversing a change set by applying inverse operations in reverse order.
- Must maintain data cohesion during both integration and rollback (ordering dependencies).

### 3.4 UI Layer
- View history of voice inputs and their processing status.
- View and approve/decline assimilation plans before execution.
- View change log with per-operation detail.
- Initiate rollback of specific change sets.

### 3.5 PKM Taxonomy
- Defines the object types, relation types, and structural conventions for the knowledge graph.
- Must be evolvable — adding new types or relations should not break existing data or the assimilation engine.

## 4. Existing AnyType Architecture (Codebase Analysis)

### 4.1 Module Structure
The app is a 30+ module Gradle project following Clean Architecture:

| Layer | Modules | Role |
|-------|---------|------|
| Core models | `core-models`, `core-ui`, `core-utils` | Shared types, UI components, utilities |
| Domain | `domain` | Use cases, repository interfaces, in-memory stores (plain Kotlin, no Android) |
| Data | `data` | Repository implementations |
| Middleware | `middleware`, `protocol` | Go backend bridge via JNI + protobuf |
| Presentation | `presentation` | ViewModels, editor state |
| Features | `feature-chats`, `feature-object-type`, `feature-properties`, etc. | Self-contained feature slices |
| App | `app` | DI wiring, navigation, Android entry points |

### 4.2 Data Model
- **Objects** are represented as `Struct` (`Map<Id, Any?>`) with typed accessors via `ObjectWrapper` (sealed hierarchy: `Basic`, `Type`, `Relation`, `Option`, etc.).
- **Blocks** form a tree structure per document: `Block(id, children, content, fields)` with sealed `Content` types (Text, Link, File, DataView, etc.).
- **Relations** are first-class objects (`ObjectWrapper.Relation`) with a `RelationFormat` enum (SHORT_TEXT, NUMBER, DATE, OBJECT, TAG, STATUS, etc.).
- **Object types** are also objects (`ObjectWrapper.Type`) with recommended relations, default templates, and layouts.

### 4.3 CRUD Operations
All object/block operations flow through `BlockRepository` → `BlockDataRepository` → `BlockMiddleware` → `Middleware` (protobuf RPC) → Go backend. Key operations:
- **Create:** `CreateObject` use case → `Command.CreateObject` (space, type key, template, prefilled struct, internal flags).
- **Read:** `OpenObject` / `GetObject` use cases.
- **Update:** `SetObjectDetails` for metadata; block-level commands for editor content.
- **Delete:** `DeleteObjects` use case (hard delete); soft archive via `isArchived` relation.
- **Search:** `SearchObjects` (one-shot), `SearchWithMeta` (rich search with history), subscription-based live queries.

### 4.4 Dependency Injection
- Dagger 2 with `MainComponent` as root singleton.
- Feature subcomponents obtain dependencies via a multibound `Map<Class<ComponentDependencies>, ComponentDependencies>`.
- `ComponentManager` provides lifecycle management and parameterized scoping for subcomponents.

### 4.5 Event System
Two distinct event channels:
1. **Command events** (`Event.Command`): Editor/document mutations (block add/remove, detail changes). Delivered via `Payload` on mutation results and streamed from `EventHandler` → `EventProxy`.
2. **Subscription events** (`SubscriptionEvent`): Incremental search/index updates (amend, set, unset, add, remove, position, counters).

### 4.6 Existing Background Infrastructure
- **No existing webhook or HTTP server** infrastructure in the Android app.
- **No WorkManager usage** — background processing is coroutine-based (`DEFAULT_APP_COROUTINE_SCOPE`).
- **Firebase push** (`AnytypePushService`) handles FCM messages with decryption/verification.
- **App lifecycle** tracked via `AppStateService` (foreground/background state communicated to middleware).

## 5. Extensibility Constraints

Our modifications must be **isolated** from core AnyType functionality to enable clean merging of upstream updates. The strategy:

1. New Gradle modules for our services (following `feature-*` pattern).
2. An **isolation interface** between AnyType's core services and our new services — a stable API contract that survives upstream refactoring.
3. Minimal touch points in existing code (DI registration, navigation hooks, manifest entries).
4. Our UI additions should follow existing patterns (Jetpack Compose, design system in `core-ui`).

## 6. Design Principles

- **Measurability:** Every architectural decision should have measurable criteria, evidence, and rationale.
- **Incremental delivery:** Implementation broken into functional increments with tests at each step.
- **Change control as first-class:** Not an afterthought — the change log is the backbone of trust in automated assimilation.
- **Offline-first:** Consistent with AnyType's local-first architecture. Webhook queuing and assimilation should work without network connectivity to AnyType's sync layer.
- **Taxonomy evolution:** The system must gracefully handle schema changes without breaking existing data or requiring migration of the assimilation engine.

## 7. Key Technical Challenges

1. **Extensibility & Isolation** — How to structure new modules and interfaces to minimize coupling with upstream AnyType code while leveraging its DI, data model, and middleware.

2. **Change Control** — How to represent, order, execute, and reverse fine-grained changes to the AnyType object graph, potentially without an external database.

3. **Data Assimilation & Entity Resolution** — How to decompose natural language into structured PKM operations, resolve ambiguous entity references against the existing graph, and rank candidate matches.

4. **PKM Taxonomy & Evolution** — How to define, represent, and evolve the knowledge schema while keeping the assimilation engine aligned.

## 8. Research-Then-Build Methodology

1. **Research phase:** Produce deep research artifacts for each technical challenge (see `01-research-plan.md`).
2. **Planning phase:** Use research artifacts to create a detailed project plan and implementation plan tracked in markdown.
3. **Implementation phase:** Iterative coding agent cycles — each selects a logical batch of work, implements with tests, and updates the plan. Coding artifacts guide remaining work when new learnings emerge.
