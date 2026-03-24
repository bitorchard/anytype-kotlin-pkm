package com.anytypeio.anytype.pebble.core

import com.anytypeio.anytype.core_models.Id
import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.core_models.primitives.TypeKey

/**
 * Re-exports of core AnyType types for use within pebble modules, plus Pebble-specific constants.
 * Downstream modules depend on pebble-core rather than core-models directly, so this file is the
 * single place to change if the underlying type strategy ever shifts.
 */
typealias PebbleId = Id
typealias PebbleSpaceId = SpaceId
typealias PebbleTypeKey = TypeKey

object PebbleConstants {
    /** Default port the embedded Ktor webhook server listens on. */
    const val DEFAULT_WEBHOOK_PORT = 8391

    /** Prefix reserved for PKM custom object types (e.g. "ot-pkm-event"). */
    const val PEBBLE_TYPE_KEY_PREFIX = "ot-pkm-"

    /** Prefix reserved for PKM custom relations (e.g. "pkm-relatedTo"). */
    const val PEBBLE_RELATION_KEY_PREFIX = "pkm-"

    /** Maximum time (ms) to wait for an LLM extraction response. */
    const val DEFAULT_LLM_TIMEOUT_MS = 10_000L

    /** Confidence threshold above which entity resolution auto-resolves. */
    const val AUTO_RESOLVE_THRESHOLD = 0.85f

    /** Confidence threshold below which a new object is created rather than matched. */
    const val CREATE_NEW_THRESHOLD = 0.50f
}
