package com.anytypeio.anytype.pebble.changecontrol.model

import com.anytypeio.anytype.pebble.core.PebbleId
import kotlinx.serialization.Serializable

/**
 * A ChangeSet groups all AnyType graph mutations produced by a single voice input.
 * The status field follows the state machine:
 *
 *   PENDING → APPROVED → APPLYING → APPLIED
 *                                  ↘ APPLY_FAILED
 *                      → ROLLING_BACK → ROLLED_BACK
 *                                      ↘ PARTIALLY_ROLLED_BACK
 *          → REJECTED
 */
@Serializable
data class ChangeSet(
    val id: PebbleId,
    /** Back-link to the VoiceInput object that generated this change set. */
    val inputId: PebbleId,
    /**
     * Propagated from [com.anytypeio.anytype.pebble.webhook.model.RawInput.traceId]
     * for end-to-end observability across pipeline stages.
     */
    val traceId: String,
    val status: ChangeSetStatus,
    val summary: String,
    val operations: List<ChangeOperation>,
    val metadata: ChangeSetMetadata,
    val createdAt: Long,
    val appliedAt: Long? = null,
    val rolledBackAt: Long? = null,
    /** Populated when status is APPLY_FAILED or PARTIALLY_ROLLED_BACK. */
    val errorMessage: String? = null,
    /**
     * JSON-serialised list of disambiguation choices that could not be auto-resolved.
     * Stored as an opaque string so [pebble-changecontrol] has no compile-time
     * dependency on the assimilation-layer types.
     *
     * Use the extension functions in `pebble-assimilation` (`ChangeSetExtensions.kt`)
     * to encode/decode the typed [DisambiguationChoice] list.
     */
    val disambiguationChoicesJson: String = ""
)

@Serializable
enum class ChangeSetStatus {
    /** Produced by assimilation, awaiting UI review. */
    PENDING,
    /** User approved; ready for execution. */
    APPROVED,
    /** Execution in progress. */
    APPLYING,
    /** All operations applied successfully. */
    APPLIED,
    /** Execution failed mid-way; partial changes may exist. */
    APPLY_FAILED,
    /** User dismissed; never applied. */
    REJECTED,
    /** Rollback in progress. */
    ROLLING_BACK,
    /** All operations successfully rolled back. */
    ROLLED_BACK,
    /** Some operations could not be rolled back (conflicts). */
    PARTIALLY_ROLLED_BACK
}

@Serializable
data class ChangeSetMetadata(
    val spaceId: String,
    val sourceText: String,
    val modelVersion: String = "",
    val extractionConfidence: Float = 0f
)
