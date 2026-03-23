# Research: Data Assimilation & Entity Resolution

## Executive Summary

The recommended data assimilation architecture is a **hybrid LLM pipeline** using a cloud LLM (Claude or GPT-4) with structured JSON output for entity extraction and relationship identification, combined with **deterministic post-processing** for entity resolution against the AnyType graph. Entity resolution uses a **multi-signal scoring system** combining name similarity, type match, relationship proximity, recency, and frequency — with configurable confidence thresholds that route high-confidence matches to auto-resolution and low-confidence matches to user disambiguation. AnyType's `SearchObjects` API provides adequate full-text and filtered search capabilities for entity matching, and `ObjectSearchSubscriptionContainer` enables a hot cache of frequently-referenced entities. The pipeline processes inputs individually (not batched) to minimize latency from voice-to-knowledge, with a context window of recent inputs available to improve cross-reference resolution.

## Context & Constraints

### Hard Constraints
- Input is **transcribed text** from a Pebble Ring via the Pebble phone app — expect informal speech, sentence fragments, implied context, and potential transcription errors.
- The pipeline must produce an **assimilation plan** (ordered sequence of AnyType operations) rather than directly modifying the graph.
- Entity resolution must search the **existing AnyType graph** in the user's space.
- Privacy sensitivity: voice notes may contain personal information (health, financial, family).
- Latency target: < 10 seconds from webhook receipt to plan generation for simple inputs.
- Must work with the taxonomy defined in `05-research-taxonomy.md`.
- AnyType's search operates via Go middleware (`Rpc.Object.Search`) — not a local Kotlin index.

### AnyType Search Capabilities

From codebase analysis:

**`SearchObjects` use case:**
- Parameters: `space`, `filters` (DVFilter list), `sorts` (DVSort list), `fulltext` (string), `offset`, `limit`, `keys` (projection).
- Filter conditions: `EQUAL`, `NOT_EQUAL`, `GREATER`, `LESS`, `LIKE`, `NOT_LIKE`, `IN`, `NOT_IN`, `EMPTY`, `NOT_EMPTY`, `ALL_IN`, `NOT_ALL_IN`, `EXACT_IN`, `NOT_EXACT_IN`, `EXISTS`.
- Full-text search: Yes — the `fulltext` parameter is sent as `fullText` on `Rpc.Object.Search.Request` to the Go middleware. Behavior (tokenization, ranking) is Go-side.
- Filter by type: `DVFilter(relation = Relations.TYPE, condition = IN, value = listOf(typeId))`.
- Filter by relation value: Any relation key can be filtered.
- Default limit: 1000 objects.

**`SearchWithMeta`:**
- Same search parameters plus `withMeta` flag.
- Returns per-hit metadata: `highlight` (matched text), `ranges` (highlight positions), `source` (which relation or block matched), `dependencies` (related object details).
- Useful for showing users why an entity was matched.

**Subscription system:**
- `ObjectSearchSubscriptionContainer` provides live-updating result sets.
- Backed by `ObjectStore` (in-memory, mutex-protected).
- Can maintain hot caches of frequently-queried object sets (e.g., all Person objects in the space).

**Performance characteristics (estimated):**
- Search goes through: Kotlin use case → repository → middleware → JNI → Go → local index.
- Expected latency: 10-50ms for simple filtered queries; 50-200ms for full-text with large object count.
- The Go middleware maintains its own index; the Kotlin layer adds minimal overhead.

## Options Analysis

### 1. NLP Pipeline Architecture

#### Option A: LLM-Based Extraction (Cloud API)

Use a cloud LLM (Claude, GPT-4, Gemini) with structured output to extract entities and relationships from transcribed text.

**Architecture:**
```
Transcribed text
    ↓
Taxonomy prompt + input → Cloud LLM API
    ↓
Structured JSON response:
{
  "entities": [
    { "type": "Person", "name": "Aarav", "attributes": {} },
    { "type": "Event", "name": "basketball game", "attributes": { "date": "Friday" } }
  ],
  "relationships": [
    { "type": "participatesIn", "from": "Aarav", "to": "basketball game" }
  ],
  "temporal_context": { "reference_date": "next Friday" }
}
```

| Aspect | Assessment |
|--------|-----------|
| **Accuracy** | High — LLMs handle informal speech, implied context, and ambiguity well. |
| **Latency** | 1-5 seconds per API call (acceptable within 10s budget). |
| **Cost** | ~$0.01-0.05 per input (structured output with small context window). |
| **Privacy** | Data leaves device — voice notes sent to cloud API. Mitigated by: (a) using provider DPAs, (b) no audio sent (only text), (c) user consent toggle. |
| **Offline** | Does NOT work offline. |
| **Maintainability** | High — prompt-based; taxonomy changes auto-update prompt. |

**Structured Output Support (2025-2026):**
- OpenAI: Native JSON schema adherence with `response_format: { type: "json_schema" }` — 100% guaranteed.
- Anthropic: Tool use / function calling with structured output — ~99%+ reliability.
- Google Gemini: Response schema in generation config.

All major providers now support guaranteed structured output, eliminating JSON parsing failures.

#### Option B: Local LLM (llama.cpp / ONNX)

Run a small language model (Phi-3, Llama-3-8B quantized) on-device for extraction.

| Aspect | Assessment |
|--------|-----------|
| **Accuracy** | Moderate — smaller models struggle with nuanced extraction, especially temporal resolution and multi-entity disambiguation. |
| **Latency** | 5-30 seconds on mobile hardware (GPU/NPU dependent). |
| **Cost** | Zero marginal cost. |
| **Privacy** | Excellent — all processing on-device. |
| **Offline** | Full offline capability. |
| **Maintainability** | Complex — model management, updates, device compatibility testing. |
| **Size** | 2-8 GB model file — significant app size impact. |

#### Option C: Rule-Based / NER Pipeline

Use SpaCy, Stanford NLP, or Android-compatible NER (e.g., ML Kit Entity Extraction) for deterministic extraction.

| Aspect | Assessment |
|--------|-----------|
| **Accuracy** | Low-moderate for our use case — custom types (Event, Reminder) not in standard NER models; temporal expression parsing is reasonable but relationship extraction is primitive. |
| **Latency** | < 1 second. |
| **Cost** | Zero. |
| **Privacy** | Excellent. |
| **Offline** | Yes. |
| **Maintainability** | Low — requires custom training data, rule updates for each taxonomy change; brittle on informal speech. |

#### Option D: Hybrid — Cloud LLM + Deterministic Post-Processing (Recommended)

Cloud LLM for semantic extraction (what entities and relationships exist in the text) + deterministic Kotlin code for validation, entity resolution, temporal normalization, and plan generation.

```
Input text
    ↓
┌─────────────────────────────┐
│ LLM Extraction (cloud API)  │  ← Taxonomy prompt injected
│ → entities, relationships   │
└─────────┬───────────────────┘
          ↓
┌─────────────────────────────┐
│ Deterministic Post-Process  │  ← Kotlin code
│ 1. Validate against taxonomy│
│ 2. Normalize temporal refs  │
│ 3. Resolve entities (search)│
│ 4. Score candidates         │
│ 5. Generate plan            │
└─────────┬───────────────────┘
          ↓
Assimilation Plan
```

| Aspect | Assessment |
|--------|-----------|
| **Accuracy** | High — LLM handles interpretation; Kotlin handles validation. |
| **Latency** | 2-7 seconds (LLM call + search + scoring). |
| **Cost** | ~$0.01-0.05 per input. |
| **Privacy** | Text goes to cloud; resolution stays local. |
| **Offline** | Graceful degradation: queue inputs for later processing when offline. |
| **Maintainability** | High — prompt auto-generated from taxonomy; post-processing is testable Kotlin. |

**Recommendation: Option D (Hybrid).** The LLM handles the hard part (natural language understanding) while deterministic code handles the precise part (entity resolution, validation). Offline inputs are queued and processed when connectivity returns.

**Future path to Option B:** If on-device models improve sufficiently, the LLM extraction step can be swapped to a local model without changing the post-processing pipeline.

### 2. Entity Resolution Strategy

#### Multi-Signal Scoring Algorithm (Recommended)

When the LLM extracts an entity reference (e.g., `{ type: "Person", name: "Aarav" }`), the resolver must determine if this matches an existing object or requires creation.

**Step 1: Candidate Retrieval**

```kotlin
suspend fun findCandidates(
    entityRef: ExtractedEntity,
    space: SpaceId
): List<Candidate> {
    // Search by name (full-text) + type filter
    val nameResults = searchService.searchObjects(
        space = space,
        filters = listOf(
            typeFilter(entityRef.type.anytypeTypeIds),
            notArchivedFilter()
        ),
        fulltext = entityRef.name,
        keys = relevantKeys(entityRef.type),
        limit = 20
    )
    
    // If few results, also try fuzzy name variants
    val fuzzyResults = if (nameResults.size < 3) {
        searchService.searchObjects(
            space = space,
            filters = listOf(
                typeFilter(entityRef.type.anytypeTypeIds),
                DVFilter(relation = Relations.NAME, condition = LIKE, value = entityRef.name)
            ),
            keys = relevantKeys(entityRef.type),
            limit = 10
        )
    } else emptyList()
    
    return (nameResults + fuzzyResults).distinctBy { it.id }
}
```

**Step 2: Multi-Signal Scoring**

Each candidate is scored across multiple dimensions:

| Signal | Weight | Calculation |
|--------|--------|-------------|
| **Name similarity** | 0.35 | Normalized Levenshtein distance + token overlap (handles "Dr. Patel" vs "Deepak Patel") |
| **Type match** | 0.15 | Exact type match = 1.0; related type = 0.5; unrelated = 0.0 |
| **Relationship proximity** | 0.20 | Graph distance from other entities in the same input. If the input mentions "Aarav's basketball game" and a Person "Aarav" is already linked to sports-related objects, score higher. |
| **Recency** | 0.15 | Decaying function of last modification date. Recently accessed objects are more likely to be referenced. |
| **Frequency** | 0.10 | How often this object appears in recent voice inputs. Frequently mentioned entities are more likely matches. |
| **Contextual attributes** | 0.05 | Attribute overlap — e.g., if the input mentions "Dr. Patel" and a Person has `role = "doctor"`, boost score. |

**Score formula:**
```
score = Σ (weight_i × signal_i)  where signal_i ∈ [0.0, 1.0]
```

**Step 3: Resolution Decision**

| Confidence Range | Action |
|-----------------|--------|
| score ≥ 0.85 | **Auto-resolve**: Use the matched object. Note in plan as "auto-matched". |
| 0.50 ≤ score < 0.85 | **Suggest with disambiguation**: Include top candidates in the plan; mark as "needs confirmation". |
| score < 0.50 | **Create new**: Propose creating a new object. |
| Multiple candidates with score ≥ 0.70 | **Force disambiguation**: Present all high-confidence candidates to user. |

#### Implementation: Relationship Proximity Calculation

Relationship proximity leverages AnyType's OBJECT-format relations to find graph neighbors:

```kotlin
suspend fun relationshipProximity(
    candidate: PebbleObject,
    coEntities: List<ExtractedEntity>,
    space: SpaceId
): Float {
    // Get all OBJECT-format relation values on the candidate
    val relatedIds = candidate.objectRelationValues()
    
    // Check if any co-entities in this input are already related to the candidate
    var proximityScore = 0f
    for (coEntity in coEntities) {
        val coEntityCandidates = findCandidates(coEntity, space)
        val bestMatch = coEntityCandidates.maxByOrNull { it.nameScore }
        if (bestMatch != null && bestMatch.id in relatedIds) {
            proximityScore += 0.5f  // Direct relation
        }
    }
    return (proximityScore / coEntities.size.coerceAtLeast(1)).coerceAtMost(1f)
}
```

### 3. Disambiguation and Confidence

#### Confidence Thresholds

The default thresholds (0.85 / 0.50) should be **user-configurable** via settings:

| Setting | Default | Description |
|---------|---------|-------------|
| Auto-resolve threshold | 0.85 | Above this, matches are automatic |
| Create-new threshold | 0.50 | Below this, new objects are created |
| Require approval for all | false | If true, all plans need manual approval |
| Learn from corrections | true | If true, corrections improve future scoring |

#### Disambiguation UX

When the resolver produces multiple candidates, the assimilation plan includes a `DisambiguationChoice`:

```kotlin
data class DisambiguationChoice(
    val entityRef: ExtractedEntity,
    val candidates: List<ScoredCandidate>,
    val recommendation: ScoredCandidate?,  // highest scoring, if above threshold
    val createNewOption: Boolean = true     // always offer "create new"
)

data class ScoredCandidate(
    val objectId: Id,
    val name: String,
    val type: String,
    val score: Float,
    val matchReasons: List<String>  // human-readable: "Name match: 92%", "Related to Event 'Basketball Game'"
)
```

The UI presents this as a card with candidate options, match reasons, and a "Create new" button.

#### Learning from Past Resolutions

Track user corrections in a lightweight local store:

```kotlin
data class ResolutionFeedback(
    val entityName: String,
    val entityType: String,
    val resolvedToObjectId: Id?,  // null if user chose "create new"
    val wasAutoResolved: Boolean,
    val wasCorrect: Boolean,  // user confirmed or corrected
    val timestamp: Long
)
```

Over time, use frequency data to boost known name → object mappings. This is a simple lookup cache, not ML — if "Aarav" has been resolved to object X three times, its base score gets a frequency bonus.

### 4. Taxonomy-Driven Decomposition

#### Prompt Engineering Strategy

The LLM receives a system prompt generated from the taxonomy:

```
You are a personal knowledge graph assistant. Given transcribed voice input, extract structured entities and relationships.

IMPORTANT RULES:
1. Extract ALL entities mentioned (people, events, tasks, places, organizations, topics).
2. Resolve temporal references relative to today ({{current_date}}).
3. For each entity, identify which type it best matches from the schema below.
4. For relationships, identify how entities connect using the available relation types.
5. If an entity doesn't match any type, use "Note" as a catch-all.
6. Preserve the original phrasing where possible for entity names.

SCHEMA:
{{taxonomy_prompt}}

INPUT: "{{transcribed_text}}"

Respond with JSON matching this schema:
{
  "entities": [
    {
      "ref_id": "string (temporary ID for this extraction)",
      "type": "string (type name from schema)",
      "name": "string",
      "attributes": { "key": "value" },
      "confidence": "number (0-1, how certain you are about this extraction)"
    }
  ],
  "relationships": [
    {
      "type": "string (relation name from schema)",
      "from_ref": "string (ref_id of source entity)",
      "to_ref": "string (ref_id of target entity)",
      "confidence": "number (0-1)"
    }
  ],
  "notes": "string (any ambiguities or uncertainties)"
}
```

#### Handling Inputs Outside Taxonomy

| Scenario | Strategy |
|----------|----------|
| Unknown entity type (e.g., "Restaurant") | LLM assigns closest type (Place) + adds a tag/note |
| Vague reference ("that thing we discussed") | LLM outputs low confidence; plan flags for user resolution |
| Multiple possible interpretations | LLM outputs top interpretation with note about alternatives |
| Transcription errors | Post-processing fuzzy matching handles "Arav" → "Aarav" |

### 5. AnyType Query Capabilities Assessment

| Capability | Supported | Notes |
|-----------|-----------|-------|
| Full-text search | Yes | `fulltext` parameter on `SearchObjects` |
| Filter by type | Yes | `DVFilter(relation = TYPE, condition = IN, value = [typeIds])` |
| Filter by relation value | Yes | Any relation key can be filtered |
| LIKE condition (fuzzy) | Yes | `condition = LIKE` — behavior depends on Go implementation |
| Multiple filter conditions | Yes | AND/OR operators via `DVFilter.Operator` |
| Nested filters | Yes | `nestedFilters` field on `DVFilter` |
| Sort by any field | Yes | `DVSort` with ASC/DESC/CUSTOM |
| Pagination | Yes | `offset` + `limit` parameters |
| Key projection | Yes | `keys` parameter limits returned fields |
| Live subscription | Yes | `ObjectSearchSubscriptionContainer` for hot caches |
| Search highlight | Yes | `SearchWithMeta` returns highlight + ranges |

#### Hot Cache Strategy

Maintain live subscriptions for frequently-needed entity sets:

```kotlin
class EntityCache(
    private val subscriptionContainer: ObjectSearchSubscriptionContainer,
    private val space: SpaceId
) {
    // Always-hot subscription for all Person objects
    val personCache: Flow<List<PebbleObject>> = subscriptionContainer.observe(
        space = space,
        subscription = "pebble-person-cache",
        filters = listOf(typeFilter(listOf(ObjectTypeIds.HUMAN))),
        keys = listOf(Relations.ID, Relations.NAME, Relations.TYPE, Relations.LAST_MODIFIED_DATE)
    ).map { sub -> sub.objects.mapNotNull { store.get(it) }.toPebbleObjects() }
    
    // Hot cache for recent objects (last 30 days, any type)
    val recentCache: Flow<List<PebbleObject>> = subscriptionContainer.observe(
        space = space,
        subscription = "pebble-recent-cache",
        filters = listOf(
            DVFilter(relation = Relations.LAST_MODIFIED_DATE, condition = GREATER, value = thirtyDaysAgo()),
            notArchivedFilter()
        ),
        sorts = listOf(DVSort(relationKey = Relations.LAST_MODIFIED_DATE, type = DESC)),
        keys = basicKeys,
        limit = 500
    ).map { /* ... */ }
}
```

This avoids per-input search latency for common entity types — the Person cache, for instance, is already in memory when a voice input mentions a name.

### 6. Batch vs. Streaming Processing

#### Recommendation: Individual Processing with Context Window

Process each voice input individually for minimum latency, but maintain a **context window** of the last N inputs (default: 10) to improve cross-reference resolution.

**Rationale:**
- Voice inputs arrive one at a time from the Pebble Ring — no natural batch boundary.
- Users expect near-real-time feedback (< 10 seconds from voice to plan).
- Batching would add unpredictable delay.

**Context window benefit:**
```
Input 1: "Aarav has a basketball game on Friday"
  → Creates Person(Aarav) + Event(basketball game)

Input 2: "Remind me to pick up Aarav's jersey before the game"
  → Context window contains Input 1's entities
  → Resolver: "Aarav" → high confidence match to Person just created
  → Resolver: "the game" → high confidence match to Event just created
  → Creates Reminder(pick up jersey) linked to both
```

The context window is implemented as a simple in-memory ring buffer of recent `AssimilationResult` objects, available to the LLM prompt as additional context:

```
RECENT CONTEXT (entities created/matched in recent inputs):
- Person "Aarav" (id: xyz123)
- Event "basketball game on Friday" (id: abc456)
```

### 7. Existing Techniques Survey

| Technique | Source | Applicability |
|-----------|--------|--------------|
| **Knowledge Graph Construction from Text (LLM)** | Neo4j LLM Graph Builder, LangChain `llm-graph-transformer` | High — same fundamental task. We adapt for personal (not enterprise) scale and mobile-first constraints. |
| **GraphRAG** | Microsoft Research, 2024-2026 | Medium — GraphRAG focuses on retrieval; we focus on construction. But the entity/relationship extraction stage is similar. |
| **Entity Linking (Wikipedia-scale)** | REL, BLINK, mGENRE | Low-medium — designed for linking to knowledge bases with millions of entities. Our graph is personal-scale (hundreds to thousands). |
| **Conversational AI Memory** | MemGPT, LangGraph memory modules | Medium — similar challenge of maintaining entity state across conversational turns. Our "context window" is inspired by this. |
| **Personal Information Extraction** | Google Now, Apple Siri, Cortana | High — same domain (personal events, tasks, people from natural language). Proprietary, but the public patterns (temporal parsing, entity resolution) apply. |
| **Structured Extraction with Function Calling** | OpenAI, Anthropic tool use | High — the exact mechanism we use. Well-documented, production-proven. |

## Recommended Approach

### Pipeline Architecture

```
┌─────────────────────────────────────────────────────────┐
│                  Assimilation Engine                      │
│                                                          │
│  ┌──────────┐   ┌───────────┐   ┌──────────────────┐   │
│  │ Taxonomy  │──→│   LLM     │──→│  Post-Processor  │   │
│  │ Prompt    │   │ Extractor │   │  (Kotlin)        │   │
│  │ Generator │   │ (Cloud)   │   │                  │   │
│  └──────────┘   └───────────┘   │  1. Validate     │   │
│                                  │  2. Normalize     │   │
│  ┌──────────┐                   │     temporal refs  │   │
│  │ Context   │─────────────────→│  3. Entity        │   │
│  │ Window    │                   │     Resolution    │   │
│  └──────────┘                   │  4. Score         │   │
│                                  │     candidates    │   │
│  ┌──────────┐                   │  5. Generate      │   │
│  │ Entity    │─────────────────→│     Plan          │   │
│  │ Cache     │                   └────────┬─────────┘   │
│  └──────────┘                             │              │
│                                           ↓              │
│                                  ┌──────────────────┐   │
│                                  │ Assimilation Plan │   │
│                                  └──────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### LLM Provider Strategy

**Primary:** Anthropic Claude (tool use for structured output) — strong at nuanced language understanding, good at following complex schemas.

**Fallback:** OpenAI GPT-4o-mini — guaranteed JSON schema adherence, cost-effective.

**Configuration:** Provider and model configurable in settings. API key stored in Android Keystore.

### Entity Resolution Algorithm

Multi-signal scoring with configurable thresholds:
- **Name similarity (0.35)**: Levenshtein + token overlap
- **Type match (0.15)**: Exact/related/unrelated
- **Relationship proximity (0.20)**: Graph neighbor analysis
- **Recency (0.15)**: Exponential decay on last-modified
- **Frequency (0.10)**: Voice input reference count
- **Context attributes (0.05)**: Attribute overlap

### Offline Behavior

```
Online:  Webhook → Queue → LLM Extract → Resolve → Plan → [Approve] → Execute
Offline: Webhook → Queue → (wait for connectivity) → Resume pipeline
```

The queue persists inputs to survive app restarts. When connectivity returns, queued inputs are processed in order with their original timestamps for temporal reference resolution.

### Validation Criteria

| Criterion | Target | How to Measure |
|-----------|--------|---------------|
| Extraction accuracy | ≥ 90% F1 on entity extraction | Eval set of 100 voice inputs with ground truth |
| Resolution precision | ≥ 85% correct entity matches (when match exists) | Manual review of resolution decisions |
| End-to-end latency | < 10 seconds (online) | Timed from webhook receipt to plan ready |
| Disambiguation rate | < 20% of inputs require disambiguation | Count plans with disambiguation choices |
| False creation rate | < 10% (creating new when match exists) | Manual audit of "create new" decisions |
| Cost per input | < $0.05 average | API billing tracking |

## Open Questions

1. **LLM provider selection**: Should we support multiple providers from day one, or start with one and add more later?
2. **API key management**: Where does the user configure their LLM API key? In-app settings? Environment variable? Should we provide a default key for initial use?
3. **Rate limiting**: How do we handle rapid-fire voice inputs? Debouncing? Queue with rate limit?
4. **Temporal reference resolution**: Should temporal normalization happen in the LLM (inject current date in prompt) or in post-processing (parse "next Friday" in Kotlin)?
5. **Multi-language support**: The Pebble Ring may transcribe in languages other than English. How does this affect extraction accuracy?
6. **Entity merge**: If the user corrects a disambiguation choice (merging two entities that were created separately), how do we handle this?
7. **Embedding-based search**: Should we explore vector embeddings for entity resolution? AnyType doesn't natively support vector search, but we could maintain a local embedding index.
8. **LLM context size**: For users with large graphs, the taxonomy prompt + context window might grow large. How do we manage token budgets?

## References

- AnyType `SearchObjects` use case — `domain/src/main/java/.../search/SearchObjects.kt`
- AnyType `SearchWithMeta` — `domain/src/main/java/.../search/SearchWithMeta.kt`
- AnyType `ObjectSearchSubscriptionContainer` — `domain/src/main/java/.../search/ObjectSearchSubscriptionContainer.kt`
- AnyType `DVFilter` / `DVSort` — `core-models/src/main/java/.../Block.kt`
- AnyType `ObjectStore` — `domain/src/main/java/.../objects/ObjectStore.kt`
- [OpenAI Structured Outputs](https://platform.openai.com/docs/guides/structured-outputs)
- [Anthropic Tool Use](https://docs.anthropic.com/en/docs/build-with-claude/tool-use)
- [Neo4j LLM Knowledge Graph Builder](https://neo4j.com/labs/genai-ecosystem/llm-graph-builder/)
- [Building Knowledge Graphs from Text with LLMs](https://building.theatlantic.com/building-knowledge-graphs-from-text-a-complete-guide-with-llms-02be1b0bce64)
- [GraphRAG: Microsoft Research](https://microsoft.github.io/graphrag/)
