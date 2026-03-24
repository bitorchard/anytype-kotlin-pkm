package com.anytypeio.anytype.pebble.core

import com.anytypeio.anytype.core_models.Id

/**
 * Pebble's simplified view of an AnyType object. Intentionally decoupled from
 * [com.anytypeio.anytype.core_models.ObjectWrapper] so downstream pebble modules do not
 * need a direct dependency on AnyType internals.
 */
data class PebbleObject(
    val id: Id,
    val name: String,
    /** The unique key of this object's type (e.g. "ot-task", "ot-pkm-event"). */
    val typeKey: String,
    /** Full details map — raw relation key → value from the AnyType graph. */
    val details: Map<String, Any?>,
    val lastModifiedDate: Long?
)

/** Returned by create operations; carries the new object's ID and its type key. */
data class PebbleObjectResult(
    val objectId: Id,
    val typeKey: String? = null
)

/** A search hit with optional highlight snippets for full-text matches. */
data class PebbleSearchResult(
    val obj: PebbleObject,
    val highlights: List<String> = emptyList()
)
