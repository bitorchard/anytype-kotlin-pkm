package com.anytypeio.anytype.pebble.assimilation.context

import com.anytypeio.anytype.pebble.assimilation.model.ExtractionResult
import com.anytypeio.anytype.pebble.assimilation.model.ExtractedEntity
import com.anytypeio.anytype.pebble.assimilation.model.ResolvedEntity
import com.anytypeio.anytype.pebble.assimilation.model.ResolutionDecision
import com.anytypeio.anytype.pebble.core.PebbleId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A fixed-capacity ring buffer of recent assimilation results.
 *
 * Provides entity context to:
 * - [EntityExtractor] — injected into the LLM system prompt so the model can refer to
 *   entities mentioned in previous inputs ("him", "that event", etc.).
 * - [EntityResolver] — used to boost scoring for entities created in recent inputs.
 *
 * Thread-safe via `@Synchronized` (resolution happens on a single coroutine).
 */
@Singleton
class ContextWindow @Inject constructor() {

    private val capacity = DEFAULT_CAPACITY

    /**
     * Compact record of a resolved entity kept in the context window.
     */
    data class RecentEntity(
        val name: String,
        val typeKey: String,
        /** The real AnyType object ID if resolution succeeded; null for new creates. */
        val objectId: PebbleId?,
        val inputIndex: Int
    )

    private val entries = ArrayDeque<List<RecentEntity>>(capacity)
    private var inputCounter = 0

    /**
     * Record the resolved entities from one assimilation pass.
     * Oldest entry is evicted when capacity is exceeded.
     */
    @Synchronized
    fun record(resolved: List<ResolvedEntity>) {
        val recentEntities = resolved.mapNotNull { re ->
            when (val d = re.decision) {
                is ResolutionDecision.Resolved -> RecentEntity(
                    name = re.entity.name,
                    typeKey = re.entity.typeKey,
                    objectId = d.objectId,
                    inputIndex = inputCounter
                )
                is ResolutionDecision.CreateNew -> RecentEntity(
                    name = re.entity.name,
                    typeKey = re.entity.typeKey,
                    objectId = null,
                    inputIndex = inputCounter
                )
                ResolutionDecision.Skipped -> null
            }
        }
        if (entries.size >= capacity) entries.removeFirst()
        entries.addLast(recentEntities)
        inputCounter++
    }

    /**
     * Returns all entities from recent inputs (most recent first) as a flat list.
     */
    @Synchronized
    fun recentEntities(): List<RecentEntity> =
        entries.reversed().flatten()

    /**
     * Returns a human-readable summary for injection into the LLM system prompt.
     * Blank when the window is empty.
     */
    @Synchronized
    fun recentEntitySummary(): String {
        val recent = recentEntities()
        if (recent.isEmpty()) return ""
        return buildString {
            appendLine("═══════════════════════════════════════════════════════")
            appendLine("RECENT CONTEXT (entities from your last ${entries.size} input(s))")
            appendLine("═══════════════════════════════════════════════════════")
            appendLine()
            recent.forEach { entity ->
                appendLine("▸ ${entity.name} (type: ${entity.typeKey})")
            }
            appendLine()
            appendLine("If the user refers to any of the above entities by pronoun or short form,")
            appendLine("resolve them to the listed entity rather than creating a duplicate.")
        }
    }

    /**
     * Clears the window (e.g. on space change or explicit reset).
     */
    @Synchronized
    fun clear() {
        entries.clear()
        inputCounter = 0
    }

    companion object {
        const val DEFAULT_CAPACITY = 10
    }
}
