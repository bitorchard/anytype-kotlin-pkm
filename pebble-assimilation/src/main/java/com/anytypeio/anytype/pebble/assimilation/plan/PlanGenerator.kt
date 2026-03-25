package com.anytypeio.anytype.pebble.assimilation.plan

import com.anytypeio.anytype.pebble.assimilation.model.AssimilationPlan
import com.anytypeio.anytype.pebble.assimilation.model.DisambiguationChoice
import com.anytypeio.anytype.pebble.assimilation.model.ExtractionResult
import com.anytypeio.anytype.pebble.assimilation.model.ResolutionDecision
import com.anytypeio.anytype.pebble.assimilation.model.ResolvedEntity
import com.anytypeio.anytype.pebble.changecontrol.engine.OperationOrderer
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeOperation
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetMetadata
import com.anytypeio.anytype.pebble.changecontrol.model.OperationParams
import com.anytypeio.anytype.pebble.changecontrol.model.OperationType
import java.util.UUID
import javax.inject.Inject

/**
 * Converts resolved entities and extraction relationships into an [AssimilationPlan]
 * containing ordered [ChangeOperation]s ready for the change control layer.
 *
 * Operation generation rules:
 * - `CreateNew` decision → `CREATE_OBJECT` + `SET_DETAILS` for each attribute.
 * - `Resolved` decision → `SET_DETAILS` for new/changed attributes only.
 * - Each [ExtractedRelationship] → `ADD_RELATION` with the relation key and target ID.
 * - All operations are topologically ordered (creates before their dependents).
 */
class PlanGenerator @Inject constructor() {

    /**
     * Generate an [AssimilationPlan] from resolved entities and extraction metadata.
     *
     * @param resolved            Entities with resolution decisions applied.
     * @param extraction          The original extraction result (for relationships).
     * @param pendingDisambiguation Choices still requiring user input.
     * @param spaceId             AnyType space ID for CREATE_OBJECT params.
     * @param sourceText          Original voice-input text for metadata/audit.
     * @param modelVersion        LLM model that produced the extraction.
     * @param extractionConfidence Overall confidence score from the LLM.
     */
    fun generate(
        resolved: List<ResolvedEntity>,
        extraction: ExtractionResult,
        pendingDisambiguation: List<DisambiguationChoice> = emptyList(),
        spaceId: String,
        sourceText: String,
        modelVersion: String = "",
        extractionConfidence: Float = 1.0f
    ): AssimilationPlan {
        val operations = mutableListOf<ChangeOperation>()
        var ordinal = 0

        // Map from entity localRef → objectId (real or local placeholder)
        // Used to wire relationship operations.
        val localRefToObjectId = mutableMapOf<String, String>()

        for (re in resolved) {
            val entity = re.entity
            when (val decision = re.decision) {
                is ResolutionDecision.CreateNew -> {
                    val localRef = entity.localRef
                    // CREATE_OBJECT
                    operations.add(
                        ChangeOperation(
                            id = UUID.randomUUID().toString(),
                            changeSetId = "",   // filled in by AssimilationEngine
                            ordinal = ordinal++,
                            type = OperationType.CREATE_OBJECT,
                            params = OperationParams.CreateObjectParams(
                                spaceId = spaceId,
                                typeKey = entity.typeKey,
                                details = mapOf("name" to entity.name) + entity.attributes,
                                localRef = localRef
                            )
                        )
                    )
                    localRefToObjectId[localRef] = localRef  // placeholder until execution
                }

                is ResolutionDecision.Resolved -> {
                    val objectId = decision.objectId
                    localRefToObjectId[entity.localRef] = objectId

                    // SET_DETAILS for any new attributes
                    if (entity.attributes.isNotEmpty()) {
                        operations.add(
                            ChangeOperation(
                                id = UUID.randomUUID().toString(),
                                changeSetId = "",
                                ordinal = ordinal++,
                                type = OperationType.SET_DETAILS,
                                params = OperationParams.SetDetailsParams(
                                    objectId = objectId,
                                    details = entity.attributes
                                )
                            )
                        )
                    }
                }

                ResolutionDecision.Skipped -> {
                    // No operations for skipped entities
                }
            }
        }

        // Relationships → ADD_RELATION operations
        for (rel in extraction.relationships) {
            val fromId = localRefToObjectId[rel.fromLocalRef] ?: continue
            val toId = localRefToObjectId[rel.toLocalRef] ?: continue

            operations.add(
                ChangeOperation(
                    id = UUID.randomUUID().toString(),
                    changeSetId = "",
                    ordinal = ordinal++,
                    type = OperationType.ADD_RELATION,
                    params = OperationParams.AddRelationParams(
                        objectId = fromId,
                        relationKey = rel.relationKey,
                        value = toId
                    )
                )
            )
        }

        // Topological sort (creates before their dependents)
        val ordered = OperationOrderer.executionOrder(operations)

        val metadata = ChangeSetMetadata(
            spaceId = spaceId,
            sourceText = sourceText,
            modelVersion = modelVersion,
            extractionConfidence = extractionConfidence
        )

        return AssimilationPlan(
            operations = ordered,
            metadata = metadata,
            disambiguationChoices = pendingDisambiguation
        )
    }
}
