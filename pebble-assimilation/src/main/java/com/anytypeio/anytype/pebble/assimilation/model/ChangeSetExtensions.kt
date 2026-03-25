package com.anytypeio.anytype.pebble.assimilation.model

import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val changeSetJson = Json { ignoreUnknownKeys = true }

/**
 * Returns the typed [DisambiguationChoice] list decoded from [ChangeSet.disambiguationChoicesJson].
 * Returns an empty list if the field is blank or decoding fails.
 */
fun ChangeSet.disambiguationChoices(): List<DisambiguationChoice> {
    if (disambiguationChoicesJson.isBlank()) return emptyList()
    return runCatching {
        changeSetJson.decodeFromString<List<DisambiguationChoice>>(disambiguationChoicesJson)
    }.getOrDefault(emptyList())
}

/**
 * Returns a copy of this [ChangeSet] with [choices] encoded into [ChangeSet.disambiguationChoicesJson].
 */
fun ChangeSet.withDisambiguationChoices(choices: List<DisambiguationChoice>): ChangeSet =
    copy(disambiguationChoicesJson = if (choices.isEmpty()) "" else changeSetJson.encodeToString(choices))
