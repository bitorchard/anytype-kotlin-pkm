# Research: Change Control

## Executive Summary

The recommended change control approach uses an **operation log (oplog)** model with **per-input granularity** — each voice input produces one change set containing an ordered list of atomic operations with pre-computed inverse operations. Change sets are stored in **AnyType-native objects** (a custom `ChangeSet` type and `ChangeOperation` type) so they participate in AnyType's sync and backup. Rollback applies inverse operations in reverse order, creating **new forward changes** (compensating transactions) rather than attempting to undo history — this is compatible with AnyType's CRDT-based sync layer. AnyType's built-in `Undo/Redo` mechanism operates at the document/block level and cannot be leveraged for our cross-object, graph-level changes. The approval workflow uses a simple state machine (Pending → Approved → Applied / Rejected) with optional auto-apply for high-confidence plans.

## Context & Constraints

### Hard Constraints
- AnyType uses CRDT-based sync managed by the Go middleware (`anytype-heart`) — the Kotlin layer has no visibility into or control over CRDT merge operations.
- `Undo/Redo` operates per-document (`contextId`) via `Rpc.Object.Undo` — it undoes the most recent block-level change within a single object, not cross-object operations.
- All object state is managed by the Go middleware; the Kotlin layer performs operations via RPC (`CreateObject`, `SetObjectDetails`, `AddRelationToObject`, etc.) and receives events.
- Our change control must work **on top of** AnyType's existing data layer, not modify it.
- Changes must be auditable: who/what created them, when, what the before/after state was.
- Rollback must handle the case where an object has been independently modified between application and rollback.

### AnyType's Change Infrastructure (Codebase Analysis)

**`changes.proto` — Internal Change Model:**
AnyType has an internal `Change` protobuf message with a `Content` oneof covering `BlockCreate`, `BlockUpdate`, `BlockRemove`, `BlockMove`, `DetailsSet`, `DetailsUnset`, `RelationAdd`, `RelationRemove`, `ObjectTypeAdd`, `ObjectTypeRemove`, etc. This is the **Go middleware's internal change tree** — used for history/CRDT, not exposed as a Kotlin API. We cannot directly leverage this.

**`Undo/Redo` — Per-Document Only:**
- `Undo` use case: `repo.undo(Command.Undo(context = documentId))` → `Rpc.Object.Undo.Request(contextId)`.
- Returns `Payload` with events, or `UndoRedoExhaustedException` when history is empty.
- Scoped to a single `contextId` (one object/document).
- History depth exposed via `ObjectView.HistorySize` (undo/redo counters).
- **Not usable for our purposes:** We need to undo operations across multiple objects (creating a Person, creating an Event, linking them).

**Event-Based State Updates:**
- Detail changes arrive as `Event.Command.Details.Set/Amend/Unset`.
- `Struct.process(event)` applies these to in-memory state.
- Object relations arrive as `Event.Command.ObjectRelations.Amend/Remove`.
- These events are **consumed** by the client but not stored — they flow from middleware to UI and are discarded.

**CRDT Layer:**
- Kotlin has no CRDT implementation; sync semantics are in the Go middleware.
- `SpaceSyncUpdate` and `P2PStatusUpdate` provide sync status but no conflict details.
- The Go layer uses `Change.Snapshot` with `logHeads` for change tree convergence.

### Implication for Our Design
We must build change control **entirely in the application layer**, using AnyType's RPC operations as the atomic building blocks. Our system doesn't integrate with AnyType's internal change tree — it operates above it, treating each AnyType RPC call as an atomic operation.

## Options Analysis

### 1. Change Representation

#### Option A: Operation Log (Recommended)

An ordered list of atomic operations, each with parameters and a pre-computed inverse operation.

```kotlin
data class ChangeSet(
    val id: Id,
    val inputId: Id,                    // source voice input
    val createdAt: Long,
    val status: ChangeSetStatus,        // PENDING, APPROVED, APPLIED, ROLLED_BACK, REJECTED
    val operations: List<ChangeOperation>,
    val summary: String,                // human-readable summary
    val metadata: ChangeSetMetadata
)

enum class ChangeSetStatus { PENDING, APPROVED, APPLIED, ROLLED_BACK, REJECTED, PARTIALLY_ROLLED_BACK }

data class ChangeOperation(
    val id: Id,
    val ordinal: Int,
    val type: OperationType,
    val params: OperationParams,
    val inverse: OperationParams?,      // pre-computed inverse
    val beforeState: Struct?,           // snapshot of affected fields before execution
    val afterState: Struct?,            // snapshot after execution (filled after apply)
    val status: OperationStatus         // PENDING, APPLIED, ROLLED_BACK, FAILED
)

sealed class OperationType {
    object CreateObject : OperationType()
    object DeleteObject : OperationType()
    object SetDetails : OperationType()
    object AddRelation : OperationType()
    object RemoveRelation : OperationType()
}

sealed class OperationParams {
    data class CreateObjectParams(
        val space: SpaceId,
        val typeKey: TypeKey,
        val details: Struct,
        val resultObjectId: Id? = null  // filled after creation
    ) : OperationParams()
    
    data class DeleteObjectParams(
        val objectId: Id,
        val beforeState: Struct? = null  // for inverse: recreate with this state
    ) : OperationParams()
    
    data class SetDetailsParams(
        val objectId: Id,
        val details: Struct,
        val previousValues: Struct? = null  // for inverse: restore these
    ) : OperationParams()
    
    data class AddRelationParams(
        val objectId: Id,
        val relationKey: String
    ) : OperationParams()
    
    data class RemoveRelationParams(
        val objectId: Id,
        val relationKey: String
    ) : OperationParams()
}
```

| Aspect | Assessment |
|--------|-----------|
| **Forward execution** | Direct — iterate operations in order, call corresponding AnyType RPC. |
| **Rollback** | Iterate operations in reverse, execute inverse for each. |
| **Audit** | Each operation has before/after state — full audit trail. |
| **Complexity** | Moderate — must pre-compute inverses; must handle execution failures. |
| **Storage** | Moderate — each operation stored with parameters and snapshots. |

#### Option B: Snapshot-Based

Capture full before/after snapshots of all affected objects.

| Aspect | Assessment |
|--------|-----------|
| **Forward execution** | Diff computation needed; less direct. |
| **Rollback** | Restore before-snapshots. Simple conceptually but may conflict with independent changes. |
| **Audit** | Excellent — full state at every point. |
| **Storage** | High — full object snapshots are large. |
| **Conflict handling** | Poor — restoring a snapshot overwrites all changes to an object, including user edits. |

#### Option C: Event Sourcing

Append-only log of domain events (PersonCreated, EventScheduled, RelationEstablished).

| Aspect | Assessment |
|--------|-----------|
| **Forward execution** | Event handlers apply domain events to state. |
| **Rollback** | Requires compensating events or state reconstruction from replay. |
| **Audit** | Excellent — the log IS the audit trail. |
| **Complexity** | High — requires event handler infrastructure, projections, eventual consistency management. |
| **Overkill factor** | This is a personal mobile app, not a distributed microservice — event sourcing overhead is not justified. |

**Recommendation: Option A (Operation Log).** It directly maps to AnyType's RPC model (each operation corresponds to one middleware call), supports both forward execution and rollback, and provides clear audit trails without the infrastructure overhead of full event sourcing.

### 2. Ordering and Dependencies

#### Dependency Rules

Operations within a change set have natural ordering dependencies:

| Dependency | Example | Rule |
|-----------|---------|------|
| Create before use | Must create Person before linking Event → Person | CreateObject must precede any SetDetails/AddRelation referencing that object |
| Create before set details | Must create Event before setting its date | CreateObject before SetDetails with same objectId |
| Create relation type before using it | Custom relation must exist before adding to object | CreateRelation before AddRelation using that key |
| Link targets must exist | Can't add OBJECT relation value pointing to non-existent object | Both referenced objects must be created first |

#### Topological Sort

The plan generator produces operations in dependency order using topological sort:

```kotlin
fun orderOperations(operations: List<ChangeOperation>): List<ChangeOperation> {
    // Build dependency graph
    val deps = mutableMapOf<Id, MutableSet<Id>>()
    val objectCreators = mutableMapOf<Id, Id>()  // objectId → operationId that creates it
    
    operations.forEach { op ->
        when (op.params) {
            is CreateObjectParams -> objectCreators[op.params.resultObjectId ?: op.id] = op.id
            is SetDetailsParams -> {
                val creatorOp = objectCreators[op.params.objectId]
                if (creatorOp != null) {
                    deps.getOrPut(op.id) { mutableSetOf() }.add(creatorOp)
                }
            }
            // ... similar for AddRelation, etc.
        }
    }
    
    return topologicalSort(operations, deps)
}
```

#### Rollback Ordering

Rollback reverses the topological order:
1. Remove relations first (unlink entities).
2. Unset details (restore previous values).
3. Delete created objects (if safe — see conflict handling).

```kotlin
fun rollbackOrder(operations: List<ChangeOperation>): List<ChangeOperation> {
    return operations.reversed()
}
```

#### Conflict During Rollback

The critical question: what if an object has been modified by the user between application and rollback?

**Strategy: Detect-and-Warn**

```kotlin
sealed class RollbackResult {
    data class Success(val rolledBack: List<ChangeOperation>) : RollbackResult()
    data class Conflict(
        val operation: ChangeOperation,
        val currentState: Struct,
        val expectedState: Struct,      // afterState from when we applied
        val resolution: ConflictResolution
    ) : RollbackResult()
}

enum class ConflictResolution {
    FORCE_ROLLBACK,     // overwrite user changes
    SKIP_OPERATION,     // leave this operation's changes in place
    ABORT_ROLLBACK      // stop rollback entirely
}
```

Before rolling back each operation:
1. Fetch the current state of the affected object.
2. Compare with `afterState` (what we expect it to look like).
3. If they differ, a user has modified the object since our change — flag conflict.
4. Present conflict to user with options: force, skip, or abort.

For auto-rollback mode, the default resolution is `SKIP_OPERATION` — preserve user changes, roll back only our untouched changes.

### 3. CRDT Interaction

#### Key Decision: Compensating Transactions (New Forward Changes)

Our rollback creates **new forward operations** (compensating transactions) rather than attempting to "undo" history in the CRDT sense.

**Why this is correct for CRDT:**
- CRDTs are designed for append-only, convergent operations. There is no "undo" in a CRDT — only new operations that produce the desired state.
- AnyType's Go middleware applies our `SetObjectDetails` as a new change in its change tree. A rollback `SetObjectDetails` (restoring previous values) is also a new change. The CRDT merges both changes; the latest-writer-wins semantics of detail fields means the rollback's values take effect.
- This is consistent with how AnyType's own `Undo` works: the proto shows `Rpc.Object.Undo.Response` returns a `ResponseEvent` — it's generating new events, not erasing history.

**Why NOT use AnyType's `Undo`:**
- `Undo` operates per-document (`contextId`), not cross-object.
- `Undo` undoes the most recent change to a single object — if the user has made their own edits since our change, `Undo` would undo the user's edit, not ours.
- We need precise rollback of specific operations, not stack-based undo.

**Sync safety:**
- Our compensating operations are normal AnyType operations — they sync to other devices via the existing CRDT mechanism.
- No special CRDT handling needed.
- If the object was deleted on another device (sync conflict), our rollback operation may fail — handle gracefully with error reporting.

### 4. Storage

#### Option A: AnyType-Native Objects (Recommended)

Store change sets and operations as AnyType objects using custom types.

| Type | Unique Key | Fields |
|------|-----------|--------|
| ChangeSet | `ot-pebble-changeset` | status, inputId, summary, createdAt, operationIds, version |
| ChangeOperation | `ot-pebble-changeoperation` | ordinal, operationType, params (JSON), inverse (JSON), beforeState (JSON), afterState (JSON), status |
| VoiceInput | `ot-pebble-voice-input` | rawText, processedDate, status, changeSetId |

```kotlin
// Creating a ChangeSet as an AnyType object
val changeSetId = graphService.createObject(
    space = space,
    typeKey = TypeKey("ot-pebble-changeset"),
    details = mapOf(
        Relations.NAME to "Changes from: '$inputSummary'",
        "pebble-changeSetStatus" to ChangeSetStatus.PENDING.name,
        "pebble-inputId" to inputId,
        "pebble-operationCount" to operations.size,
        "pebble-createdAt" to System.currentTimeMillis().toDouble()
    )
)
```

| Aspect | Assessment |
|--------|-----------|
| **Sync** | Yes — change sets sync to other devices via AnyType's normal sync. |
| **Backup** | Yes — part of AnyType's backup/export. |
| **Queryability** | Good — can use `SearchObjects` to find change sets by status, date, etc. |
| **Storage** | Moderate — JSON-serialized operation params in text fields. |
| **Survivability** | Excellent — survives app updates, reinstalls (if AnyType data is preserved). |
| **Privacy** | Same as other AnyType data — encrypted at rest by middleware. |

**Concern: Large JSON in details fields.** Operation parameters (especially `beforeState`/`afterState` snapshots) could be large. Mitigation: Store operation details as blocks within the ChangeSet object (using the block editor model), or use `LONG_TEXT` relation format which handles larger strings.

#### Option B: Local Room Database

Dedicated SQLite database separate from AnyType's data.

| Aspect | Assessment |
|--------|-----------|
| **Sync** | No — local only. Lost if user switches devices. |
| **Backup** | Only if we implement our own backup. |
| **Queryability** | Excellent — full SQL. |
| **Storage** | Efficient — proper schema. |
| **Survivability** | Moderate — survives app updates but not data wipe. |

#### Option C: Flat Files (JSON/Protobuf)

Serialized files in app storage.

| Aspect | Assessment |
|--------|-----------|
| **Sync** | No. |
| **Queryability** | Poor — must deserialize to search. |
| **Implementation** | Simple. |

**Recommendation: Option A (AnyType-native objects)** with **Option B as a performance index.** Primary storage in AnyType objects ensures sync/backup/encryption. A lightweight Room table caches the current state of change sets for fast local queries (avoiding middleware roundtrips for status checks).

```kotlin
// Room cache for fast local queries
@Entity(tableName = "pebble_change_sets")
data class ChangeSetCache(
    @PrimaryKey val id: String,
    val anytypeObjectId: String,
    val status: String,
    val inputId: String,
    val createdAt: Long,
    val operationCount: Int,
    val summary: String
)
```

### 5. Granularity

#### Option A: Per-Input (Recommended)

One change set per voice input. All objects/relations created from a single voice note are grouped.

| Aspect | Assessment |
|--------|-----------|
| **User mental model** | Natural — "I said X, and the system did Y." Rolling back means "undo everything from that voice note." |
| **Atomicity** | Good — either the entire voice note's changes are applied or none. |
| **Partial rollback** | Not natively supported but can be extended (mark individual operations as "keep" during rollback). |
| **Audit trail** | Clear — each change set links to its source input. |

#### Option B: Per-Operation

Each create/update/link is independently reversible.

| Aspect | Assessment |
|--------|-----------|
| **User mental model** | Complex — "I want to undo the creation of Event X but keep Person Y that was created from the same input." |
| **Atomicity** | Per-operation — very granular but may leave graph in inconsistent state (orphaned relations, etc.). |
| **Flexibility** | Maximum. |

#### Option C: Per-Entity

All changes to one entity grouped.

| Aspect | Assessment |
|--------|-----------|
| **User mental model** | Sometimes natural ("undo everything about Aarav") but cross-cutting (an Event involves multiple entities). |
| **Implementation** | Complex — must handle overlapping entity scopes. |

**Recommendation: Option A (per-input)** with the ability to **selectively exclude operations** during rollback. The per-input grouping matches the user's mental model (voice note → changes) and keeps the approval UX simple. Power users can expand a change set to see individual operations and selectively keep some during rollback.

### 6. Approval UX Patterns

#### Research: Review-Before-Commit Patterns

| Pattern | Example | Applicability |
|---------|---------|--------------|
| **Git staging** | Staged changes → diff review → commit | Good conceptual model but too technical for non-technical users |
| **Document comparison** | Track changes in Word/Google Docs | Good visual model — show additions in green, removals in red |
| **Permission approval** | Android app permission dialogs | Good for binary (allow/deny) but too simple for multi-operation review |
| **Shopping cart review** | Review items before purchase | Good metaphor — "review what the system wants to add to your knowledge base" |
| **PR review** | GitHub PR with file changes | Too complex for mobile |

#### Recommended Approval UX

**Three-tier approval model:**

1. **Summary view** (default): A card showing:
   - Source voice note text
   - Count of operations: "Will create 2 objects and 1 link"
   - Confidence indicator (high/medium/low)
   - [Approve] [Reject] [Review Details] buttons

2. **Detail view** (on "Review Details"): Expandable list of operations:
   ```
   ✅ Create Person: "Aarav"
      Type: Person | Confidence: 95%
   
   ✅ Create Event: "basketball game"
      Type: Event | Date: Friday Mar 28
      Confidence: 88%
   
   ✅ Link: Aarav → participatesIn → basketball game
      Confidence: 90%
   
   ⚠️ Disambiguation needed:
      "Aarav" matches:
      ○ Aarav Patel (your son) — 92% match
      ○ Aarav Kumar (colleague) — 45% match
      ○ Create new person
   ```

3. **Change log view** (post-execution): Chronological list of applied change sets with:
   - Status badges (Applied, Rolled Back, Partial)
   - Expandable operation details with before/after state
   - [Rollback] button per change set

**Auto-apply mode:** When enabled, high-confidence plans (all operations above threshold) are applied automatically. A notification shows what was applied with an "Undo" action (which triggers rollback).

## Recommended Approach

### Change Set Lifecycle

```
Voice Input
    ↓
Assimilation Engine → Change Set (PENDING)
    ↓
[Auto-approve?] ──Yes──→ Execute → Change Set (APPLIED)
    │                                    ↓
    No                              [User requests rollback]
    ↓                                    ↓
User reviews plan              Rollback Engine
    ↓                              ↓
[Approve?] ──Yes──→ Execute   Change Set (ROLLED_BACK)
    │
    No
    ↓
Change Set (REJECTED)
```

### State Machine

```kotlin
enum class ChangeSetStatus {
    PENDING,              // Created, awaiting approval
    APPROVED,             // User approved, ready for execution
    APPLYING,             // Execution in progress
    APPLIED,              // Successfully executed
    APPLY_FAILED,         // Execution failed (partial state possible)
    REJECTED,             // User rejected
    ROLLING_BACK,         // Rollback in progress
    ROLLED_BACK,          // Successfully rolled back
    PARTIALLY_ROLLED_BACK // Some operations rolled back, some skipped (conflict)
}
```

### Execution Engine

```kotlin
class ChangeExecutor(
    private val graphService: PebbleGraphService,
    private val changeStore: ChangeStore
) {
    suspend fun execute(changeSet: ChangeSet): ExecutionResult {
        changeStore.updateStatus(changeSet.id, APPLYING)
        
        val results = mutableListOf<OperationResult>()
        for (op in changeSet.operations.sortedBy { it.ordinal }) {
            try {
                val before = captureBeforeState(op)
                val result = executeOperation(op)
                val after = captureAfterState(op, result)
                changeStore.updateOperation(op.id, APPLIED, before, after, result)
                results.add(OperationResult.Success(op, result))
            } catch (e: Exception) {
                changeStore.updateOperation(op.id, FAILED, error = e.message)
                changeStore.updateStatus(changeSet.id, APPLY_FAILED)
                return ExecutionResult.PartialFailure(results, op, e)
            }
        }
        
        changeStore.updateStatus(changeSet.id, APPLIED)
        return ExecutionResult.Success(results)
    }
    
    suspend fun rollback(changeSet: ChangeSet, strategy: ConflictStrategy): RollbackResult {
        changeStore.updateStatus(changeSet.id, ROLLING_BACK)
        
        val applied = changeSet.operations
            .filter { it.status == APPLIED }
            .sortedByDescending { it.ordinal }  // reverse order
        
        val results = mutableListOf<RollbackOperationResult>()
        for (op in applied) {
            val conflict = detectConflict(op)
            if (conflict != null) {
                when (strategy) {
                    FORCE -> executeInverse(op)
                    SKIP -> { results.add(Skipped(op, conflict)); continue }
                    ABORT -> return RollbackResult.Aborted(results, conflict)
                }
            }
            executeInverse(op)
            changeStore.updateOperation(op.id, ROLLED_BACK)
            results.add(RollbackOperationResult.Success(op))
        }
        
        val finalStatus = if (results.any { it is Skipped }) PARTIALLY_ROLLED_BACK else ROLLED_BACK
        changeStore.updateStatus(changeSet.id, finalStatus)
        return RollbackResult.Complete(results, finalStatus)
    }
}
```

### Inverse Operation Mapping

| Forward Operation | Inverse Operation | Notes |
|-------------------|-------------------|-------|
| `CreateObject(type, details) → id` | `DeleteObject(id)` | Must capture created ID during execution |
| `DeleteObject(id)` | `CreateObject(type, details)` from `beforeState` | Recreates with original state; ID will differ |
| `SetDetails(id, details)` | `SetDetails(id, previousValues)` | Restore previous values; handles partial updates |
| `AddRelation(id, key)` | `RemoveRelation(id, key)` | Symmetric |
| `RemoveRelation(id, key)` | `AddRelation(id, key)` | Symmetric |

### Storage Architecture

```
┌──────────────────────────────────┐
│     AnyType Object Graph         │
│                                  │
│  ChangeSet objects               │
│  ├── status, summary, inputId    │
│  ├── operationIds (list)         │
│  └── createdAt, version          │
│                                  │
│  ChangeOperation objects         │
│  ├── ordinal, type, status       │
│  ├── params (JSON text block)    │
│  ├── inverse (JSON text block)   │
│  └── beforeState, afterState     │
│                                  │
│  VoiceInput objects              │
│  ├── rawText, processedDate      │
│  └── status, changeSetId         │
└──────────────────────────────────┘
              ↕ sync
┌──────────────────────────────────┐
│     Local Room Cache             │
│  (pebble_change_sets table)      │
│  - Fast status queries           │
│  - Offline access                │
│  - Updated on AnyType events     │
└──────────────────────────────────┘
```

### Validation Criteria

| Criterion | Target | How to Measure |
|-----------|--------|---------------|
| Rollback correctness | 100% state restoration for unmodified objects | Integration test: apply → rollback → verify |
| Conflict detection | 100% detection of independently modified objects | Test: apply → modify → rollback → verify conflict flagged |
| Execution atomicity | Zero partial applies without error state | Test: inject failure mid-execution → verify APPLY_FAILED state |
| Audit completeness | Every operation has before/after state | Inspect change set objects after execution |
| Storage overhead | < 5KB per voice input (average change set) | Measure serialized size |
| Rollback latency | < 3 seconds for typical change set | Benchmark |

## Open Questions

1. **Change set retention**: How long should change sets be kept? Forever (for audit)? User-configurable retention period? Auto-archive after N days?
2. **Cascading rollback**: If rolling back Change Set A leaves orphaned references from Change Set B (which was applied later and references objects from A), should we detect and warn?
3. **Batch rollback**: Should users be able to roll back multiple change sets at once? What about ordering dependencies across change sets?
4. **Object deletion safety**: When rolling back a `CreateObject`, should we hard-delete or soft-archive? What if the user has linked other objects to the created object?
5. **Performance at scale**: With hundreds of change sets stored as AnyType objects, will `SearchObjects` queries for the change log UI remain performant?
6. **Cross-device rollback**: If a change set was applied on Device A, can it be rolled back on Device B (after sync)? The change set objects will sync, but are the operation params sufficient for remote rollback?
7. **Undo from notification**: For auto-applied plans, the notification "Undo" should trigger rollback. How do we handle this if the app is in the background? PendingIntent → Foreground Service?

## References

- AnyType `changes.proto` — internal change model (Go middleware)
- AnyType `Undo.kt` / `Redo.kt` — per-document undo use cases
- AnyType `Event.kt` — `Event.Command.Details.Set/Amend/Unset` event types
- AnyType `BlockRepository.kt` — full RPC surface
- AnyType `Middleware.kt` — RPC implementation
- [Event Sourcing for Local-First Sync](https://same.supply/note/event-sourcing-for-local-first-sync)
- [Two-Layer Event Sourcing Architecture](https://medium.com/@bnayae/the-two-layer-event-sourcing-architecture-d9873c94369d)
- [CQRS for Mobile Backends](https://mvpfactory.io/blog/event-sourcing-cqrs-mobile-backends)
- Martin Fowler, "Event Sourcing" — https://martinfowler.com/eaaDev/EventSourcing.html
- Pat Helland, "Immutability Changes Everything" — CIDR 2015
