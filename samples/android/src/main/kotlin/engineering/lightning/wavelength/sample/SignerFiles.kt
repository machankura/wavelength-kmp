package engineering.lightning.wavelength.sample

import engineering.lightning.wavelength.RemoteSignerConfig
import engineering.lightning.wavelength.WavelengthException
import java.io.File

/**
 * Keeps a remote signer's address and credentials between launches. The daemon
 * opens the macaroon and certificate by path on every start, so they must be real
 * files: an Android content URI would not do. Point [dir] at noBackupFilesDir so
 * they never leave the device.
 */
internal class SignerFiles(private val dir: File) {
    private val hostFile = File(dir, "host")
    private val macaroonFile = File(dir, "signer.macaroon")
    private val tlsCertFile = File(dir, "tls.cert")

    fun load(): RemoteSignerConfig? = if (hostFile.exists()) config(hostFile.readText()) else null

    /** Validates what was pasted and writes it. The macaroon is binary, so it arrives as hex. */
    fun save(host: String, macaroonHex: String, tlsCert: String): RemoteSignerConfig {
        val address = host.trim()
        if (address.isEmpty()) invalid("the signer host is required")
        if ("BEGIN CERTIFICATE" !in tlsCert) invalid("the signer certificate must be PEM text")
        val macaroon = hexToBytes(macaroonHex.filterNot(Char::isWhitespace))

        dir.mkdirs()
        dir.restrictToOwner()
        writeOwnerOnly(hostFile, address.encodeToByteArray())
        writeOwnerOnly(macaroonFile, macaroon)
        writeOwnerOnly(tlsCertFile, tlsCert.encodeToByteArray())
        return config(address)
    }

    private fun config(host: String) = RemoteSignerConfig(host, macaroonFile.absolutePath, tlsCertFile.absolutePath)

    private fun writeOwnerOnly(file: File, bytes: ByteArray) {
        file.writeBytes(bytes)
        file.restrictToOwner()
    }

    private fun File.restrictToOwner() {
        setReadable(false, false)
        setReadable(true, true)
        setWritable(false, false)
        setWritable(true, true)
        if (isDirectory) {
            setExecutable(false, false)
            setExecutable(true, true)
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        if (hex.isEmpty() || hex.length % 2 != 0) invalid("the signer macaroon must be hex")
        return ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toIntOrNull(16)?.toByte() ?: invalid("the signer macaroon must be hex")
        }
    }

    private fun invalid(message: String): Nothing = throw WavelengthException.InvalidArgument(message)
}
