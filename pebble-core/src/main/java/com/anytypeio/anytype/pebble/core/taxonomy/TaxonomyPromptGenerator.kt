package com.anytypeio.anytype.pebble.core.taxonomy

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Serialises the PKM taxonomy into a structured LLM system prompt.
 *
 * The prompt teaches the LLM which object types and relations exist so it can
 * extract structured entities from free-form voice input. The prompt is generated
 * from the sealed hierarchy so that any new type added to [PkmObjectType] or
 * [PkmRelation] is automatically reflected without any other code change.
 */
object TaxonomyPromptGenerator {

    private val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US)

    /**
     * Generates the full taxonomy system prompt, including today's date for
     * temporal reference (e.g. resolving "Friday" or "tomorrow").
     *
     * @param currentDate Injected for testability; defaults to now.
     */
    fun generateTaxonomyPrompt(currentDate: Date = Date()): String = buildString {
        appendLine("You are an expert personal knowledge management assistant.")
        appendLine("Today's date is: ${dateFormat.format(currentDate)}")
        appendLine()
        appendLine("Your task is to extract structured entities and relationships from voice input text.")
        appendLine("Return a JSON object following the schema described below.")
        appendLine()
        appendLine("═══════════════════════════════════════════════════════")
        appendLine("AVAILABLE OBJECT TYPES")
        appendLine("═══════════════════════════════════════════════════════")
        appendLine()

        for (type in PkmObjectType.all()) {
            appendLine(type.toPromptDescription())
        }

        appendLine()
        appendLine("═══════════════════════════════════════════════════════")
        appendLine("AVAILABLE RELATIONS (links between objects)")
        appendLine("═══════════════════════════════════════════════════════")
        appendLine()

        for (relation in PkmRelation.objectRelations()) {
            appendLine(relation.toPromptDescription())
        }

        appendLine()
        appendLine("═══════════════════════════════════════════════════════")
        appendLine("EXTRACTION RULES")
        appendLine("═══════════════════════════════════════════════════════")
        appendLine()
        appendLine("1. Extract ALL entities mentioned — people, places, events, tasks, expenses, etc.")
        appendLine("2. Map each entity to exactly one object type. If unsure, use 'ot-note'.")
        appendLine("3. Extract relationships between entities using the relation keys above.")
        appendLine("4. Normalise dates to ISO 8601 (YYYY-MM-DD). Use today's date as anchor.")
        appendLine("5. Assign a confidence score 0.0–1.0 per entity.")
        appendLine("6. Include the original text that was the source for each entity.")
        appendLine()
        appendLine("═══════════════════════════════════════════════════════")
        appendLine("OUTPUT FORMAT")
        appendLine("═══════════════════════════════════════════════════════")
        appendLine()
        appendLine("""
{
  "entities": [
    {
      "tempId": "e1",
      "typeKey": "<object type uniqueKey>",
      "name": "<display name>",
      "attributes": {
        "<relationKey>": "<value>"
      },
      "confidence": 0.9,
      "sourceText": "<span of original text>"
    }
  ],
  "relationships": [
    {
      "relationKey": "<pkm-* relation key>",
      "fromTempId": "e1",
      "toTempId": "e2",
      "confidence": 0.85
    }
  ]
}""".trimIndent())
    }

    // ── Extension helpers ──────────────────────────────────────────────────────

    private fun PkmObjectType.toPromptDescription(): String = buildString {
        append("▸ ${displayName} (key: ${uniqueKey}, layout: ${layout.name})")
        val allRelations = (requiredRelations + optionalRelations)
            .joinToString(", ") { "${it.key} [${it.format.name}]" }
        if (allRelations.isNotEmpty()) {
            appendLine()
            append("  Fields: $allRelations")
        }
        if (!isBuiltIn) {
            append(" [CUSTOM]")
        }
        appendLine()
    }

    private fun PkmRelation.toPromptDescription(): String =
        "▸ ${displayName} (key: ${key}) — format: ${format.name}"
}
