package com.anytypeio.anytype.pebble.changecontrol.engine

import com.anytypeio.anytype.pebble.changecontrol.model.ChangeOperation
import com.anytypeio.anytype.pebble.changecontrol.model.OperationParams

/**
 * Determines the execution order for a list of [ChangeOperation]s within a change set.
 *
 * Rules:
 * 1. A CREATE_OBJECT(localRef = "X") must precede any operation whose params reference
 *    that localRef as an objectId.
 * 2. For operations with no dependency relationships the original [ChangeOperation.ordinal]
 *    is used as the tiebreaker.
 * 3. [reverseOrder] is used by [ChangeRollback] to undo operations in the correct order.
 *
 * Implemented as Kahn's algorithm (BFS-based topological sort).
 */
object OperationOrderer {

    /**
     * Returns operations sorted in execution order (dependencies first).
     *
     * @throws CircularDependencyException if a cycle is detected (should be impossible with
     *   well-formed change sets but acts as a safety net).
     */
    fun executionOrder(operations: List<ChangeOperation>): List<ChangeOperation> {
        if (operations.size <= 1) return operations.sortedBy { it.ordinal }

        val nodeIds = operations.map { it.id }
        // localRef → operation id for CREATE_OBJECT ops
        val localRefToId: Map<String, String> = operations
            .filter { it.params is OperationParams.CreateObjectParams }
            .mapNotNull { op ->
                val ref = (op.params as OperationParams.CreateObjectParams).localRef
                if (ref != null) ref to op.id else null
            }
            .toMap()

        // adjacency: prerequisiteId → set of dependent operation ids
        val prereqs: MutableMap<String, MutableSet<String>> = nodeIds.associateWith { mutableSetOf<String>() }.toMutableMap()
        // in-degree
        val inDegree: MutableMap<String, Int> = nodeIds.associateWith { 0 }.toMutableMap()

        for (op in operations) {
            val deps = resolveDependencies(op, localRefToId)
            for (dep in deps) {
                if (dep != op.id) {
                    prereqs.getOrPut(dep) { mutableSetOf() }.add(op.id)
                    inDegree[op.id] = (inDegree[op.id] ?: 0) + 1
                }
            }
        }

        val opById = operations.associateBy { it.id }
        // Start with nodes that have no prerequisites; break ties with ordinal
        val queue = ArrayDeque(
            inDegree.entries
                .filter { it.value == 0 }
                .map { it.key }
                .sortedBy { opById[it]?.ordinal ?: Int.MAX_VALUE }
        )
        val result = mutableListOf<ChangeOperation>()

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val op = opById[current] ?: continue
            result.add(op)

            val dependents = prereqs[current]?.sortedBy { opById[it]?.ordinal ?: Int.MAX_VALUE } ?: continue
            for (dep in dependents) {
                val newDegree = (inDegree[dep] ?: 1) - 1
                inDegree[dep] = newDegree
                if (newDegree == 0) queue.add(dep)
            }
        }

        if (result.size != operations.size) {
            val missing = operations.map { it.id } - result.map { it.id }.toSet()
            throw CircularDependencyException(
                "Circular dependency detected in change set. Stuck on operations: $missing"
            )
        }
        return result
    }

    /** Returns operations in the order needed for rollback (reverse of execution order). */
    fun reverseOrder(operations: List<ChangeOperation>): List<ChangeOperation> =
        executionOrder(operations).reversed()

    // ── Dependency resolution ─────────────────────────────────────────────────

    /**
     * Returns the IDs of operations that [op] depends on.
     *
     * A SET_DETAILS or ADD_RELATION on objectId "X" depends on the CREATE_OBJECT whose
     * localRef resolved to "X".
     */
    private fun resolveDependencies(
        op: ChangeOperation,
        localRefToId: Map<String, String>
    ): List<String> {
        return when (val p = op.params) {
            is OperationParams.CreateObjectParams -> emptyList()
            is OperationParams.SetDetailsParams -> listOfNotNull(localRefToId[p.objectId])
            is OperationParams.AddRelationParams -> buildList {
                localRefToId[p.objectId]?.let { add(it) }
                p.value?.let { v -> localRefToId[v]?.let { add(it) } }
            }
            is OperationParams.RemoveRelationParams -> listOfNotNull(localRefToId[p.objectId])
            is OperationParams.DeleteObjectParams -> listOfNotNull(localRefToId[p.objectId])
        }
    }
}

class CircularDependencyException(message: String) : Exception(message)
