package com.anytypeio.anytype.pebble.core.observability

enum class PipelineStage {
    INPUT_RECEIVED,
    INPUT_QUEUED,
    LLM_EXTRACTING,
    LLM_EXTRACTED,
    ENTITY_RESOLVING,
    ENTITY_RESOLVED,
    PLAN_GENERATED,
    APPROVAL_PENDING,
    CHANGE_APPLYING,
    CHANGE_APPLIED,
    ROLLED_BACK,
    ERROR
}
