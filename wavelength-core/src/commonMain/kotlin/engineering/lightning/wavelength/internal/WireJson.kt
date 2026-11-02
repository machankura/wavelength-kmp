package engineering.lightning.wavelength.internal

import kotlinx.serialization.json.Json

/**
 * The JSON configuration for the gomobile facade boundary.
 *
 * - Unknown keys from a newer daemon are ignored.
 * - Go encodes nil slices and pointers as `null`; `coerceInputValues` turns a
 *   null for a non-null property into its default (an empty list, say).
 * - Requests omit nulls and defaults; Go decodes a missing key as its zero
 *   value, which is what the defaults are.
 */
internal val WireJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = false
}
