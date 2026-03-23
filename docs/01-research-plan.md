# Research Plan: AnyType Pebble Ring PKM Assimilation

This document defines the research plan for each major technical challenge. Each research area will produce an artifact (markdown document) containing analysis of options, trade-offs, evidence, and a recommended approach. These artifacts will inform the project plan and implementation decisions.

---

## Research Area 1: Extensibility & Isolation

**File:** `02-research-extensibility.md`

### Motivation
We are forking the AnyType Android client. Our modifications (webhook, assimilation, change control, UI) must be isolated so that upstream AnyType updates can be merged with minimal conflict. This is the foundational architectural decision — get it wrong and every future merge becomes painful.

### Questions to Answer

1. **Module strategy:** What is the optimal Gradle module structure for our new services? Should we use a single `feature-pebble` module or multiple modules (`feature-webhook`, `feature-assimilation`, `feature-change-control`, `feature-pebble-ui`)? What are the trade-offs in build time, coupling, and merge risk?

2. **Isolation interface design:** How should our services interact with AnyType's core? Options include:
   - Direct dependency on `BlockRepository` and domain use cases.
   - A thin **adapter/facade module** that wraps AnyType APIs and provides a stable contract our services depend on.
   - A plugin/extension architecture with service discovery.
   
   What are the trade-offs in stability, performance, and implementation cost?

3. **DI integration:** How do we register our components with AnyType's Dagger 2 graph while minimizing changes to `MainComponent` and `ComponentManager`? Can we use separate Dagger components with bridge dependencies? Should we consider a different DI approach (e.g., Koin) for our modules while bridging to the existing Dagger graph?

4. **UI integration:** How do we add new screens (input history, approval, rollback) to the app's navigation graph without modifying core navigation files? Options: deep links, separate navigation graph, bottom sheet overlays, a dedicated activity.

5. **Background service architecture:** AnyType has no WorkManager or persistent background services. What's the best approach for our webhook listener and processing queue? Android Foreground Service, WorkManager, or something else? How does this interact with AnyType's lifecycle management (`AppStateService`)?

6. **Merge strategy:** What git workflow and tooling will minimize merge conflicts? Techniques: keeping our changes in clearly delineated files/modules, using git merge strategies, automated conflict detection.

### Research Methods
- Analyze AnyType's DI graph, navigation, and manifest for minimal integration points.
- Study Android modularization best practices (Google's guide to app architecture, Now in Android sample).
- Evaluate real-world examples of fork extensibility in large Android codebases.
- Prototype module isolation with a minimal "hello world" feature module to validate the DI bridge.

### Success Criteria
- A recommended module structure with rationale.
- A defined isolation interface contract.
- Verified DI integration approach with < 5 lines of change to existing AnyType DI files.
- Navigation integration approach that doesn't modify core navigation.

---

## Research Area 2: Change Control

**File:** `03-research-change-control.md`

### Motivation
Automated data assimilation is inherently risky — the system may misinterpret inputs, create wrong objects, or link to the wrong entities. Change control is the safety net. Users need to see exactly what will change, approve or reject it, and roll back mistakes. This must work on top of AnyType's existing data layer without introducing heavy external infrastructure.

### Questions to Answer

1. **Change representation:** How should we model a change set? Options:
   - **Operation log:** Ordered list of atomic operations (CreateObject, SetDetails, AddRelation, etc.) with parameters and inverse operations.
   - **Snapshot-based:** Before/after state snapshots of affected objects.
   - **Event sourcing:** Append-only log of domain events.
   
   What representation supports both forward execution and reliable rollback?

2. **Ordering and dependencies:** Operations have dependencies (e.g., must create an object before adding a relation to it). How do we model and enforce this ordering? How do we reverse-order for rollback? What happens if an intermediate object has been modified by the user between application and rollback?

3. **Conflict with CRDT:** AnyType uses CRDT-based sync. How does our change control interact with this?
   - Does rollback create a new "forward" change (preferred for CRDT) or attempt to undo history?
   - Can we leverage AnyType's existing undo/redo infrastructure (`Undo` / `Redo` use cases on `BlockRepository`)?
   - What are the risks of CRDT merge conflicts when we programmatically apply and then revert changes?

4. **Storage:** Where do we persist change sets?
   - In AnyType objects themselves (e.g., a special "ChangeLog" object type).
   - In a local SQLite database (Room) alongside but separate from AnyType's data.
   - In flat files (JSON/protobuf).
   
   Considerations: survivability across app updates, queryability, weight, and whether AnyType syncs our audit data.

5. **Granularity:** What's the right level of granularity? Per-input (one change set per voice note), per-operation (each create/update is independently reversible), or per-entity (all changes to one object grouped)?

6. **Approval UX patterns:** Review existing patterns for "review before commit" UX in mobile apps (e.g., git staging, document comparison, permission approval flows). What's effective for non-technical users reviewing structured data changes?

### Research Methods
- Study event sourcing and CQRS patterns adapted for mobile/local-first contexts.
- Analyze AnyType's existing `Undo`/`Redo` implementation in `BlockRepository` and middleware.
- Examine AnyType's CRDT sync behavior by studying `Event`, `Payload`, and middleware event handling.
- Evaluate lightweight persistence options (Room, DataStore, AnyType-native objects).
- Review change-review UX patterns in comparable applications.

### Success Criteria
- A change set data model with forward/reverse operation support.
- A dependency ordering strategy with rollback safety analysis.
- A clear answer on CRDT interaction (leverage vs. avoid AnyType undo).
- A recommended storage approach with trade-off analysis.
- A granularity recommendation with rationale.

---

## Research Area 3: Data Assimilation & Entity Resolution

**File:** `04-research-data-assimilation.md`

### Motivation
This is the core intelligence of the system. A voice note like *"Remind me to call Dr. Patel about Aarav's checkup results before Thursday"* must be decomposed into: a Reminder object, a reference to an existing Person (Dr. Patel), a reference to an existing Person (Aarav), an Event (checkup), and a temporal constraint (before Thursday). The system must disambiguate which Dr. Patel and which Aarav from potentially many in the PKM.

### Questions to Answer

1. **NLP pipeline architecture:** What's the best approach for extracting structured information from transcribed text?
   - **LLM-based extraction:** Use an LLM (local or API) with a structured output schema. Pros: flexible, handles ambiguity. Cons: latency, cost, privacy.
   - **Rule-based/NER pipeline:** SpaCy, Stanford NLP, or similar. Pros: fast, offline, deterministic. Cons: brittle, requires training data for custom types.
   - **Hybrid:** LLM for interpretation + deterministic post-processing for validation.
   
   Which approach best balances accuracy, latency, privacy, and maintainability?

2. **Entity resolution strategy:** How do we match extracted entities against the existing AnyType graph?
   - **Search-based:** Use AnyType's `SearchObjects` / `SearchWithMeta` with name matching and type filtering.
   - **Embedding-based:** Compute semantic embeddings for objects and match by similarity.
   - **Graph-based:** Use relationship proximity in the object graph as a signal (objects closely related to the current context are more likely matches).
   - **Scoring/ranking:** How do we combine multiple signals (name match, type match, relationship proximity, recency, frequency) into a confidence score?

3. **Disambiguation and confidence:** When entity resolution produces multiple candidates:
   - What confidence threshold triggers automatic resolution vs. user prompt?
   - How do we present disambiguation choices to the user efficiently?
   - Should the system learn from past resolutions to improve future accuracy?

4. **Taxonomy-driven decomposition:** How does the PKM taxonomy guide extraction?
   - Should the LLM/NLP be provided with the full taxonomy schema as context?
   - How do we handle inputs that reference concepts not in the current taxonomy?
   - Can we detect taxonomy evolution signals from input patterns?

5. **AnyType query capabilities:** What are the performance characteristics and limitations of AnyType's search?
   - `SearchObjects`: What filter/sort combinations are supported? Full-text search capability?
   - Subscription queries: Can we use live subscriptions for "hot" entity caches?
   - Latency: How fast are searches via the middleware → Go backend path?

6. **Batch vs. streaming:** Should inputs be processed one at a time or batched? Does batching enable cross-reference resolution (e.g., two inputs in the same batch reference the same new entity)?

7. **Existing techniques survey:** What techniques have been used for similar problems?
   - Knowledge graph population from unstructured text.
   - Personal information management and entity linking.
   - Conversational AI with memory/knowledge grounding.
   - Markov chain relevance extraction or similar probabilistic models.

### Research Methods
- Survey academic and industry literature on knowledge graph construction from natural language.
- Evaluate LLM APIs (OpenAI, Anthropic, local models via llama.cpp) for structured extraction tasks.
- Benchmark AnyType's `SearchObjects` performance with realistic data volumes.
- Prototype entity extraction + resolution with a sample taxonomy and test inputs.
- Analyze AnyType's `ObjectSearchSubscriptionContainer` and `StoreOfRelations` for hot-cache potential.

### Success Criteria
- A recommended NLP pipeline architecture with pros/cons and privacy analysis.
- An entity resolution algorithm design with scoring/ranking approach.
- A disambiguation strategy with confidence thresholds.
- Performance characterization of AnyType's search capabilities.
- A survey of related techniques with applicability assessment.

---

## Research Area 4: PKM Taxonomy & Evolution

**File:** `05-research-taxonomy.md`

### Motivation
The taxonomy defines the vocabulary of the knowledge graph — what types of objects exist, what relations connect them, and what attributes they carry. It must be both concrete enough for the assimilation engine to work with and flexible enough to evolve as the user's needs change. It bridges the human-understandable domain model with AnyType's type/relation system.

### Questions to Answer

1. **Initial taxonomy design:** What object types and relations do we need for a personal PKM?
   - Core types: Person, Event, Task, Reminder, Place, Organization, Note, Project, Topic.
   - Core relations: participates-in, scheduled-for, located-at, belongs-to, related-to, assigned-to.
   - What attribute relations does each type need (name, date, status, priority, etc.)?
   - How do we handle cross-cutting concerns (tags, contexts, areas of responsibility)?

2. **Mapping to AnyType's type system:** How do our taxonomy types map to AnyType constructs?
   - AnyType already has built-in types (Page, Note, Task, Human, etc.) and built-in relations. Should we reuse these or create custom types?
   - AnyType's `ObjectWrapper.Type` has `recommendedRelations` and `recommendedLayout`. How do we leverage this?
   - How do we create custom relation types (e.g., "participates-in" as an OBJECT-format relation)?

3. **Taxonomy representation:** Where and how is the taxonomy defined?
   - **Code-defined:** Kotlin enum/sealed class mapping types and relations. Simple but requires code changes to evolve.
   - **Configuration-defined:** JSON/YAML file loaded at runtime. Flexible but harder to validate.
   - **AnyType-native:** Use AnyType's own type/relation objects as the taxonomy source of truth. The assimilation engine reads the taxonomy from AnyType itself.
   - **Hybrid:** Core taxonomy in code, with AnyType-native extensions.
   
   What are the trade-offs in maintainability, evolvability, and consistency?

4. **Schema evolution:** How do we handle changes to the taxonomy over time?
   - Adding a new type or relation.
   - Renaming or restructuring existing types.
   - Changing a relation's format (e.g., from SHORT_TEXT to OBJECT).
   - Migrating existing objects to match the new schema.
   - Versioning: should the taxonomy be versioned?

5. **Taxonomy and the assimilation engine:** How tightly coupled should the taxonomy be to the extraction logic?
   - If using an LLM, the taxonomy can be injected as a system prompt schema.
   - If using rule-based NLP, the taxonomy shapes the extraction rules.
   - How do we ensure the engine stays aligned when the taxonomy evolves?

6. **Existing PKM frameworks:** What can we learn from established PKM methodologies?
   - PARA (Projects, Areas, Resources, Archives).
   - Zettelkasten (atomic notes with links).
   - GTD (Getting Things Done — contexts, next actions, projects).
   - How do these map to AnyType's object/relation model?

### Research Methods
- Catalog AnyType's built-in types and relations by examining `ObjectType`, `Relations`, and middleware protobuf definitions.
- Survey PKM frameworks and their ontological structures.
- Prototype creating custom types and relations via AnyType's API (`CreateObjectType`, `createRelation`).
- Evaluate schema migration patterns used in mobile apps (Room migrations, protobuf evolution).
- Test taxonomy injection into LLM prompts for extraction accuracy.

### Success Criteria
- A concrete initial taxonomy with types, relations, and attributes.
- A mapping strategy to AnyType's type/relation system.
- A recommended taxonomy representation approach.
- A schema evolution strategy with migration plan.
- Alignment strategy between taxonomy and assimilation engine.

---

## Research Execution Order

The research areas have dependencies that suggest a partially ordered execution:

```
┌──────────────┐     ┌──────────────────┐
│ 1. Extensi-  │     │ 4. Taxonomy &    │
│    bility    │     │    Evolution     │
└──────┬───────┘     └────────┬─────────┘
       │                      │
       │              ┌───────┴─────────┐
       │              │ 3. Data Assimi- │
       │              │    lation       │
       │              └───────┬─────────┘
       │                      │
       └──────────┬───────────┘
                  │
          ┌───────┴─────────┐
          │ 2. Change       │
          │    Control      │
          └─────────────────┘
```

**Recommended order:**
1. **Extensibility (1)** and **Taxonomy (4)** can be researched in parallel — they are independent.
2. **Data Assimilation (3)** depends on Taxonomy (4) for the schema it operates against.
3. **Change Control (2)** depends on understanding the operations that Assimilation (3) will produce and the module structure from Extensibility (1).

However, all four can proceed partially in parallel since early findings in each area will inform — but not block — the others. We recommend starting all four, with Extensibility and Taxonomy given a slight head start.

---

## Artifact Format

Each research artifact should follow this structure:

```
# Research: [Area Name]

## Executive Summary
- Key findings and recommendation (1 paragraph)

## Context & Constraints
- Problem framing specific to this area
- Hard constraints (AnyType architecture, Android platform, offline-first, etc.)

## Options Analysis
- For each option:
  - Description
  - Pros / Cons
  - Evidence / examples
  - Estimated implementation cost

## Recommended Approach
- Selected option(s) with rationale
- Measurable criteria for validation

## Open Questions
- Items needing further investigation or prototyping

## References
- Sources, documentation, prior art
```

---

## Tracking

| Research Area | File | Status | Dependencies |
|---|---|---|---|
| Extensibility & Isolation | `02-research-extensibility.md` | **Complete** | None |
| Change Control | `03-research-change-control.md` | **Complete** | 1, 3 (partial) |
| Data Assimilation & Entity Resolution | `04-research-data-assimilation.md` | **Complete** | 4 (partial) |
| PKM Taxonomy & Evolution | `05-research-taxonomy.md` | **Complete** | None |
