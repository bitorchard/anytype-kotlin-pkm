package com.anytypeio.anytype.pebble.core.taxonomy

import com.anytypeio.anytype.pebble.core.PebbleConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaxonomyHierarchyTest {

    // ── PkmObjectType tests ────────────────────────────────────────────────────

    @Test
    fun `all() returns 19 types`() {
        assertEquals(19, PkmObjectType.all().size)
    }

    @Test
    fun `builtIn() returns 5 types`() {
        val builtIn = PkmObjectType.builtIn()
        assertEquals(5, builtIn.size)
        assertTrue(builtIn.all { it.isBuiltIn })
    }

    @Test
    fun `custom() returns 14 types`() {
        val custom = PkmObjectType.custom()
        assertEquals(14, custom.size)
        assertTrue(custom.all { !it.isBuiltIn })
    }

    @Test
    fun `all custom type keys use ot-pkm- prefix`() {
        PkmObjectType.custom().forEach { type ->
            assertTrue(
                "Custom type ${type.uniqueKey} must start with '${PebbleConstants.PEBBLE_TYPE_KEY_PREFIX}'",
                type.uniqueKey.startsWith(PebbleConstants.PEBBLE_TYPE_KEY_PREFIX)
            )
        }
    }

    @Test
    fun `no built-in type uses ot-pkm- prefix`() {
        PkmObjectType.builtIn().forEach { type ->
            assertFalse(
                "Built-in type ${type.uniqueKey} must NOT start with '${PebbleConstants.PEBBLE_TYPE_KEY_PREFIX}'",
                type.uniqueKey.startsWith(PebbleConstants.PEBBLE_TYPE_KEY_PREFIX)
            )
        }
    }

    @Test
    fun `no type uses deprecated ot-pebble- prefix`() {
        PkmObjectType.all().forEach { type ->
            assertFalse(
                "Type ${type.uniqueKey} must not use deprecated 'ot-pebble-' prefix",
                type.uniqueKey.startsWith("ot-pebble-")
            )
        }
    }

    @Test
    fun `all types have non-empty displayName`() {
        PkmObjectType.all().forEach { type ->
            assertTrue("Type ${type.uniqueKey} must have a non-empty displayName", type.displayName.isNotEmpty())
        }
    }

    @Test
    fun `all required relations in each type are valid PkmRelation entries`() {
        val allRelationKeys = PkmRelation.all().map { it.key }.toSet()
        PkmObjectType.all().forEach { type ->
            (type.requiredRelations + type.optionalRelations).forEach { rel ->
                assertTrue(
                    "Type ${type.uniqueKey} references unknown relation ${rel.key}",
                    allRelationKeys.contains(rel.key)
                )
            }
        }
    }

    @Test
    fun `byKey() returns correct type`() {
        assertNotNull(PkmObjectType.byKey("ot-pkm-event"))
        assertEquals("Event", PkmObjectType.byKey("ot-pkm-event")?.displayName)
        assertEquals(null, PkmObjectType.byKey("nonexistent"))
    }

    @Test
    fun `each type uniqueKey is unique`() {
        val keys = PkmObjectType.all().map { it.uniqueKey }
        assertEquals("Duplicate type keys found", keys.size, keys.distinct().size)
    }

    // ── PkmRelation tests ──────────────────────────────────────────────────────

    @Test
    fun `custom() returns 30 custom relations`() {
        assertEquals(30, PkmRelation.custom().size)
    }

    @Test
    fun `builtIn() returns 13 built-in relations`() {
        assertEquals(13, PkmRelation.builtIn().size)
    }

    @Test
    fun `all custom relation keys use pkm- prefix`() {
        PkmRelation.custom().forEach { relation ->
            assertTrue(
                "Custom relation ${relation.key} must start with '${PebbleConstants.PEBBLE_RELATION_KEY_PREFIX}'",
                relation.key.startsWith(PebbleConstants.PEBBLE_RELATION_KEY_PREFIX)
            )
        }
    }

    @Test
    fun `no relation uses deprecated pebble- prefix`() {
        PkmRelation.all().forEach { relation ->
            assertFalse(
                "Relation ${relation.key} must not use deprecated 'pebble-' prefix",
                relation.key.startsWith("pebble-")
            )
        }
    }

    @Test
    fun `each relation key is unique`() {
        val keys = PkmRelation.all().map { it.key }
        assertEquals("Duplicate relation keys found", keys.size, keys.distinct().size)
    }

    @Test
    fun `objectRelations() returns only OBJECT format relations`() {
        PkmRelation.objectRelations().forEach { rel ->
            assertEquals(
                "objectRelations() must contain only OBJECT format, got ${rel.key}",
                com.anytypeio.anytype.core_models.Relation.Format.OBJECT,
                rel.format
            )
        }
    }

    // ── TaxonomyVersion tests ──────────────────────────────────────────────────

    @Test
    fun `CURRENT version is at least 1`() {
        assertTrue(TaxonomyVersion.CURRENT >= 1)
    }

    @Test
    fun `sentinel key uses ot-pkm- prefix`() {
        assertTrue(TaxonomyVersion.SENTINEL_KEY.startsWith(PebbleConstants.PEBBLE_TYPE_KEY_PREFIX))
    }

    // ── Migration tests ────────────────────────────────────────────────────────

    @Test
    fun `pending migrations from version 0 includes initial bootstrap`() {
        val pending = TaxonomyMigrations.pending(0)
        assertTrue("Should have at least one pending migration", pending.isNotEmpty())
        assertEquals(0, pending.first().fromVersion)
    }

    @Test
    fun `pending migrations from current version is empty`() {
        val pending = TaxonomyMigrations.pending(TaxonomyVersion.CURRENT)
        assertTrue("No migrations should be pending when already at CURRENT", pending.isEmpty())
    }

    @Test
    fun `initial migration creates all 14 custom types`() {
        val v0ToV1 = TaxonomyMigrations.all.first { it.fromVersion == 0 }
        val createTypeSteps = v0ToV1.steps.filterIsInstance<MigrationStep.CreateType>()
        assertEquals(14, createTypeSteps.size)
    }

    @Test
    fun `initial migration creates all 30 custom relations`() {
        val v0ToV1 = TaxonomyMigrations.all.first { it.fromVersion == 0 }
        val createRelationSteps = v0ToV1.steps.filterIsInstance<MigrationStep.CreateRelation>()
        assertEquals(30, createRelationSteps.size)
    }
}
