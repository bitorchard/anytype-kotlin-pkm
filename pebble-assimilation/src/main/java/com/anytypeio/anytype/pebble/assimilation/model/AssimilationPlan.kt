package com.anytypeio.anytype.pebble.assimilation.model

import com.anytypeio.anytype.pebble.changecontrol.model.ChangeOperation
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetMetadata

/**
 * The complete, ordered plan produced by [PlanGenerator] and ready to be turned into
 * a [com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet] by the assimilation engine.
 *
 * @param operations  Topologically ordered list of operations to execute.
 * @param metadata    Metadata for the resulting change set.
 * @param disambiguationChoices Any choices still requiring user input before execution.
 */
data class AssimilationPlan(
    val operations: List<ChangeOperation>,
    val metadata: ChangeSetMetadata,
    val disambiguationChoices: List<DisambiguationChoice> = emptyList()
) {
    /** True when there are no unresolved disambiguation choices. */
    val isReadyToExecute: Boolean get() = disambiguationChoices.isEmpty()

    /** Aggregate confidence across all operations (mean of entity confidences). */
    val confidence: Float get() = metadata.extractionConfidence
}
