package engineering.lightning.wavelength.profiles

import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.RuntimeConfig
import engineering.lightning.wavelength.WavelengthException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** How a wallet is opened. */
@Serializable
public enum class WalletKind {
    /** createWallet / unlockWallet with a password. */
    PASSWORD,

    /** openWalletFromPasskey; [WalletProfile.credentialId] scopes the passkey. */
    PASSKEY,

    /** startExternalSeedWallet with host-derived entropy. */
    EXTERNAL_SEED,
}

/**
 * One wallet on this device. Each has its own daemon data directory; only one
 * runs at a time (the daemon is a per-process singleton). Nothing here is
 * secret: credential ids and identity keys are public.
 */
@Serializable
public data class WalletProfile(
    val id: String,
    val name: String,
    val network: Network,
    val kind: WalletKind,
    /** Absolute, final data directory for this wallet. */
    val dataDir: String,
    /** The passkey credential id (base64url) for [WalletKind.PASSKEY]. */
    val credentialId: String? = null,
    /** The wallet identity, pinned after first open (see ExternalSeedWalletRequest). */
    val identityPubKey: String? = null,
    val createdAt: Instant? = null,
) {
    /** [base] pointed at this wallet's network and data directory. */
    public fun config(base: RuntimeConfig): RuntimeConfig = base.copy(network = network, dataDir = dataDir)
}

/** Persists the profile list as one opaque string. */
public interface WalletProfileStore {
    public suspend fun read(): String?

    public suspend fun write(value: String)
}

/** A [WalletProfileStore] that forgets everything when the process ends. */
public class InMemoryWalletProfileStore(private var value: String? = null) : WalletProfileStore {
    override suspend fun read(): String? = value

    override suspend fun write(value: String) {
        this.value = value
    }
}

/**
 * A registry of the wallets on this device, mirroring the TypeScript demo
 * apps' wallet registry. Each profile gets `<rootDir>/wallets/<id>` as its
 * data directory; [rootDir] must be absolute (for example
 * `Wavelength.defaultDataDir(context)`).
 *
 * Removing a profile does not delete its data directory: the encrypted wallet
 * stays on disk until the app deletes it.
 */
@OptIn(ExperimentalUuidApi::class)
public class WalletProfiles(
    private val store: WalletProfileStore,
    private val rootDir: String,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(WalletProfile.serializer())

    init {
        if (!rootDir.startsWith("/")) throw WavelengthException.InvalidArgument("rootDir must be absolute")
    }

    public suspend fun list(): List<WalletProfile> = mutex.withLock { load() }

    public suspend fun get(id: String): WalletProfile? = list().firstOrNull { it.id == id }

    /** Adds a profile with a fresh id and data directory. */
    public suspend fun create(
        name: String,
        network: Network,
        kind: WalletKind,
        credentialId: String? = null,
    ): WalletProfile = mutex.withLock {
        val id = Uuid.random().toString()
        val profile = WalletProfile(
            id = id,
            name = name,
            network = network,
            kind = kind,
            dataDir = rootDir.trimEnd('/') + "/wallets/" + id,
            credentialId = credentialId,
            createdAt = Clock.System.now(),
        )
        save(load() + profile)
        profile
    }

    /** Replaces the profile with the same id (e.g. to pin [WalletProfile.identityPubKey]). */
    public suspend fun update(profile: WalletProfile): Unit = mutex.withLock {
        val all = load()
        if (all.none { it.id == profile.id }) throw WavelengthException.InvalidArgument("no wallet profile ${profile.id}")
        save(all.map { if (it.id == profile.id) profile else it })
    }

    public suspend fun remove(id: String): Unit = mutex.withLock {
        save(load().filterNot { it.id == id })
    }

    private suspend fun load(): List<WalletProfile> =
        store.read()?.takeIf { it.isNotBlank() }?.let { json.decodeFromString(serializer, it) }.orEmpty()

    private suspend fun save(profiles: List<WalletProfile>) {
        store.write(json.encodeToString(serializer, profiles))
    }
}
