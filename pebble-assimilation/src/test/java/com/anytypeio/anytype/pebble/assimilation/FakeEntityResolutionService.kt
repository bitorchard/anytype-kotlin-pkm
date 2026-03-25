package com.anytypeio.anytype.pebble.assimilation

import com.anytypeio.anytype.pebble.assimilation.model.ExtractionResult
import com.anytypeio.anytype.pebble.assimilation.resolution.EntityResolutionService
import com.anytypeio.anytype.pebble.assimilation.resolution.EntityResolver
import com.anytypeio.anytype.pebble.core.PebbleSpaceId

/**
 * Test fake for [EntityResolutionService].
 *
 * Avoids the Mockito inline-value-class matcher incompatibility with [PebbleSpaceId]
 * (which is `@JvmInline value class SpaceId`): the actual JVM argument is an
 * unboxed String, causing `any<SpaceId>()` matchers to not match.
 */
class FakeEntityResolutionService : EntityResolutionService {

    private var result: EntityResolver.ResolutionResult? = null
    var resolveCallCount: Int = 0

    fun willReturn(result: EntityResolver.ResolutionResult) {
        this.result = result
    }

    override suspend fun resolve(
        extractionResult: ExtractionResult,
        space: PebbleSpaceId
    ): EntityResolver.ResolutionResult {
        resolveCallCount++
        return result ?: EntityResolver.ResolutionResult(
            resolved = emptyList(),
            pendingDisambiguation = emptyList()
        )
    }
}
