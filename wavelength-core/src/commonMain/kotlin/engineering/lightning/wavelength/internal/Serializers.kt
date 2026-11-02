package engineering.lightning.wavelength.internal

import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Serializes an enum by its string wire value; unknown values map via [fromWire]. */
internal abstract class StringEnumSerializer<T : Enum<T>>(
    serialName: String,
    internal val fromWire: (String) -> T,
    internal val toWire: (T) -> String,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeString(toWire(value))
    }

    override fun deserialize(decoder: Decoder): T = fromWire(decoder.decodeString())
}

/**
 * Wraps a [StringEnumSerializer] for a nullable field: Go's zero value for a
 * string enum is "", which means "unset", so both null and "" decode to null.
 */
internal abstract class OptionalStringEnumSerializer<T : Enum<T>>(
    private val inner: StringEnumSerializer<T>,
) : KSerializer<T?> {
    override val descriptor: SerialDescriptor = inner.descriptor.nullable

    override fun serialize(encoder: Encoder, value: T?) {
        if (value == null) encoder.encodeNull() else inner.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): T? {
        if (!decoder.decodeNotNullMark()) return decoder.decodeNull()
        val raw = decoder.decodeString()
        return if (raw.isEmpty()) null else inner.fromWire(raw)
    }
}

/** Serializes an enum by its integer wire value. */
internal abstract class IntEnumSerializer<T : Enum<T>>(
    serialName: String,
    private val fromWire: (Int) -> T,
    private val toWire: (T) -> Int,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeInt(toWire(value))
    }

    override fun deserialize(decoder: Decoder): T = fromWire(decoder.decodeInt())
}

/**
 * Go `time.Time` as RFC 3339. Go encodes the zero time (an unset timestamp)
 * as `0001-01-01T00:00:00Z`, which decodes to null.
 */
internal object GoTimeSerializer : KSerializer<Instant?> {
    private const val GO_ZERO_TIME_PREFIX = "0001-01-01T00:00:00"

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("engineering.lightning.wavelength.GoTime", PrimitiveKind.STRING).nullable

    override fun serialize(encoder: Encoder, value: Instant?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant? {
        if (!decoder.decodeNotNullMark()) return decoder.decodeNull()
        val raw = decoder.decodeString()
        if (raw.isEmpty() || raw.startsWith(GO_ZERO_TIME_PREFIX)) return null
        return Instant.parse(raw)
    }
}
