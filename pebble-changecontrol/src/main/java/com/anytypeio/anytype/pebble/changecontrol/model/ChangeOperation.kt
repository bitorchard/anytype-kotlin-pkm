package com.anytypeio.anytype.pebble.changecontrol.model

import com.anytypeio.anytype.pebble.core.PebbleId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single atomic mutation within a [ChangeSet].
 *
 * Each operation tracks both its params (what to do) and its inverse (how to undo it).
 * [beforeState] and [afterState] are captured at execution time to support conflict detection
 * during rollback.
 */
@Serializable
data class ChangeOperation(
    val id: PebbleId,
    val changeSetId: PebbleId,
    /** Execution order within the change set; lower ordinal executes first. */
    val ordinal: Int,
    val type: OperationType,
    val params: OperationParams,
    /** Inverse operation params; populated after execution. */
    val inverse: OperationParams? = null,
    /** Snapshot of the object's details before this operation was applied. */
    val beforeState: Map<String, String>? = null,
    /** Snapshot of the object's details after this operation was applied. */
    val afterState: Map<String, String>? = null,
    val status: OperationStatus = OperationStatus.PENDING,
    /** The AnyType ID of the object created by a CREATE_OBJECT operation. */
    val resultObjectId: PebbleId? = null
)

enum class OperationType {
    CREATE_OBJECT,
    DELETE_OBJECT,
    SET_DETAILS,
    ADD_RELATION,
    REMOVE_RELATION
}

enum class OperationStatus {
    PENDING,
    APPLIED,
    FAILED,
    ROLLED_BACK
}

/**
 * Sealed hierarchy for operation parameters.
 *
 * Each subclass carries exactly the data needed for one [OperationType].
 * Serialisation uses a [type] discriminator so JSON round-trips are unambiguous.
 */
@Serializable
sealed class OperationParams {

    @Serializable
    @SerialName("CREATE_OBJECT")
    data class CreateObjectParams(
        val spaceId: String,
        val typeKey: String,
        val details: Map<String, String> = emptyMap(),
        /**
         * Stable local reference used within the change set to identify this object
         * before its real AnyType ID is known (e.g. "local-1").
         */
        val localRef: String? = null
    ) : OperationParams()

    @Serializable
    @SerialName("DELETE_OBJECT")
    data class DeleteObjectParams(
        val objectId: PebbleId
    ) : OperationParams()

    @Serializable
    @SerialName("SET_DETAILS")
    data class SetDetailsParams(
        val objectId: PebbleId,
        /** New (or restored) relation values; String-encoded for serialisability. */
        val details: Map<String, String>
    ) : OperationParams()

    @Serializable
    @SerialName("ADD_RELATION")
    data class AddRelationParams(
        val objectId: PebbleId,
        val relationKey: String,
        /** Optional value to set immediately after adding the relation. */
        val value: String? = null
    ) : OperationParams()

    @Serializable
    @SerialName("REMOVE_RELATION")
    data class RemoveRelationParams(
        val objectId: PebbleId,
        val relationKey: String
    ) : OperationParams()
}
