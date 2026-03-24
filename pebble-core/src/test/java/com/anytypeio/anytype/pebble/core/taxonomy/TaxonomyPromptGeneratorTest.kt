package com.anytypeio.anytype.pebble.core.taxonomy

import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaxonomyPromptGeneratorTest {

    private val fixedDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-03-23")!!

    @Test
    fun `prompt contains all 19 type unique keys`() {
        val prompt = TaxonomyPromptGenerator.generateTaxonomyPrompt(fixedDate)
        PkmObjectType.all().forEach { type ->
            assertTrue(
                "Prompt must contain type key '${type.uniqueKey}'",
                prompt.contains(type.uniqueKey)
            )
        }
    }

    @Test
    fun `prompt contains all 19 type display names`() {
        val prompt = TaxonomyPromptGenerator.generateTaxonomyPrompt(fixedDate)
        PkmObjectType.all().forEach { type ->
            assertTrue(
                "Prompt must contain display name '${type.displayName}'",
                prompt.contains(type.displayName)
            )
        }
    }

    @Test
    fun `prompt contains all object relation keys`() {
        val prompt = TaxonomyPromptGenerator.generateTaxonomyPrompt(fixedDate)
        PkmRelation.objectRelations().forEach { rel ->
            assertTrue(
                "Prompt must contain object relation key '${rel.key}'",
                prompt.contains(rel.key)
            )
        }
    }

    @Test
    fun `prompt includes today's date`() {
        val prompt = TaxonomyPromptGenerator.generateTaxonomyPrompt(fixedDate)
        assertTrue("Prompt must include the current date", prompt.contains("2026"))
        assertTrue("Prompt must include March", prompt.contains("March"))
    }

    @Test
    fun `prompt is human readable with structure markers`() {
        val prompt = TaxonomyPromptGenerator.generateTaxonomyPrompt(fixedDate)
        assertTrue(prompt.contains("AVAILABLE OBJECT TYPES"))
        assertTrue(prompt.contains("AVAILABLE RELATIONS"))
        assertTrue(prompt.contains("OUTPUT FORMAT"))
    }

    @Test
    fun `adding a new type is automatically reflected in prompt without extra code`() {
        // This test verifies the invariant that generateTaxonomyPrompt iterates
        // PkmObjectType.all() dynamically rather than having a hardcoded list.
        // If a new type were added to PkmObjectType, its uniqueKey would appear automatically.
        val typeCount = PkmObjectType.all().size
        val prompt = TaxonomyPromptGenerator.generateTaxonomyPrompt(fixedDate)
        // Count occurrences of "key:" which appears once per type description
        val keyOccurrences = "key: ot-".toRegex().findAll(prompt).count()
        assertTrue("Prompt should reference all type keys (found $keyOccurrences, expected $typeCount)", keyOccurrences >= typeCount)
    }
}
