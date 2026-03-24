package com.anytypeio.anytype.pebble.core.taxonomy

/**
 * Version constants for the PKM taxonomy schema.
 *
 * Each version bump corresponds to one or more [TaxonomyMigration] steps.
 * Migrations are additive-only: existing types and relations are never renamed or removed,
 * only new types/relations are added in later versions.
 */
object TaxonomyVersion {

    /** The taxonomy version that will be written to a space on first bootstrap. */
    const val INITIAL = 1

    /** The taxonomy version represented by the current codebase. */
    const val CURRENT = 1

    /**
     * AnyType object unique key used to store the taxonomy version sentinel object.
     * One object per space; its `name` detail holds the integer version as a string.
     */
    const val SENTINEL_KEY = "ot-pkm-taxonomy-version"

    /**
     * Detail key on the sentinel object where the version integer is stored.
     * Stored as a string to remain compatible with AnyType's SHORT_TEXT relation format.
     */
    const val SENTINEL_VERSION_DETAIL = "pkm-taxonomyVersion"
}
