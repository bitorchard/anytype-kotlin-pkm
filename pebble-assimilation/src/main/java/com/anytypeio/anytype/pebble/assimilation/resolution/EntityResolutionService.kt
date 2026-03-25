package com.anytypeio.anytype.pebble.assimilation.resolution

import com.anytypeio.anytype.pebble.assimilation.model.ExtractionResult
import com.anytypeio.anytype.pebble.core.PebbleSpaceId

/**
 * Contract for entity resolution (candidate lookup + scoring + decision).
 *
 * Allows tests to provide a fake implementation without Mockito,
 * avoiding the `@JvmInline` value-class / coroutine matcher incompatibility.
 */
interface EntityResolutionService {
    /**
     * Resolve all entities in [extractionResult] within [space].
     *
     * Auto-resolvable entities are immediately decided; ambiguous ones are returned
     * in [EntityResolver.ResolutionResult.pendingDisambiguation] for UI presentation.
     */
    suspend fun resolve(
        extractionResult: ExtractionResult,
        space: PebbleSpaceId
    ): EntityResolver.ResolutionResult
}
