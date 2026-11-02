package engineering.lightning.wavelength

import kotlin.io.encoding.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * An immutable byte sequence with value equality. On the wire it is standard
 * base64, which is how Go's encoding/json represents `[]byte`.
 */
@Serializable(with = ByteStringSerializer::class)
public class ByteString private constructor(private val bytes: ByteArray) {
    /** The number of bytes. */
    public val size: Int get() = bytes.size

    /** Returns a copy of the bytes. */
    public fun toByteArray(): ByteArray = bytes.copyOf()

    /** Standard (RFC 4648) base64 with padding. */
    public fun base64(): String = Base64.encode(bytes)

    /** Lower-case hex. */
    public fun hex(): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    override fun equals(other: Any?): Boolean = other is ByteString && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    /** Never prints the content: these bytes are often secret (seeds, passwords). */
    override fun toString(): String = "ByteString(size=$size)"

    public companion object {
        /** The empty byte string. */
        public val EMPTY: ByteString = ByteString(ByteArray(0))

        /** Wraps a copy of [bytes]. */
        public fun of(bytes: ByteArray): ByteString = ByteString(bytes.copyOf())

        /** Decodes standard base64. */
        public fun fromBase64(base64: String): ByteString = ByteString(Base64.decode(base64))
    }
}

internal object ByteStringSerializer : KSerializer<ByteString> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("engineering.lightning.wavelength.ByteString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteString) {
        encoder.encodeString(value.base64())
    }

    override fun deserialize(decoder: Decoder): ByteString = ByteString.fromBase64(decoder.decodeString())
}
