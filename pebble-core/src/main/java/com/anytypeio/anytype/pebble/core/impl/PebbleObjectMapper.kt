package com.anytypeio.anytype.pebble.core.impl

import com.anytypeio.anytype.core_models.Relations
import com.anytypeio.anytype.pebble.core.PebbleObject

/**
 * Maps raw AnyType [Struct] (Map<String, Any?>) to [PebbleObject] and back.
 */
object PebbleObjectMapper {

    fun fromStruct(struct: Map<String, Any?>): PebbleObject {
        val id = struct[Relations.ID] as? String ?: ""
        val name = struct[Relations.NAME] as? String ?: ""
        val typeKey = resolveTypeKey(struct)
        val lastModified = when (val raw = struct[Relations.LAST_MODIFIED_DATE]) {
            is Double -> raw.toLong()
            is Long -> raw
            else -> null
        }
        return PebbleObject(
            id = id,
            name = name,
            typeKey = typeKey,
            details = struct,
            lastModifiedDate = lastModified
        )
    }

    fun toDetailsMap(obj: PebbleObject): Map<String, Any?> = buildMap {
        put(Relations.NAME, obj.name)
        putAll(obj.details.filterKeys { it != Relations.ID })
    }

    /**
     * Prefer [Relations.TYPE_UNIQUE_KEY] (populated when the search included a type-join)
     * and fall back to the first entry in the raw [Relations.TYPE] list.
     */
    private fun resolveTypeKey(struct: Map<String, Any?>): String {
        val directKey = struct[Relations.TYPE_UNIQUE_KEY] as? String
        if (!directKey.isNullOrBlank()) return directKey

        @Suppress("UNCHECKED_CAST")
        val typeList = struct[Relations.TYPE] as? List<*>
        return typeList?.firstOrNull() as? String ?: ""
    }
}
