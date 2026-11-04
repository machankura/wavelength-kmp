package engineering.lightning.wavelength.sample

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import engineering.lightning.wavelength.CreateWalletRequest
import engineering.lightning.wavelength.Destination
import engineering.lightning.wavelength.InvoiceAmount
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.ReceiveRequest
import engineering.lightning.wavelength.RuntimeConfig
import engineering.lightning.wavelength.SendRequest
import engineering.lightning.wavelength.WavelengthException
import engineering.lightning.wavelength.embedded.Wavelength
import engineering.lightning.wavelength.embedded.defaultDataDir
import engineering.lightning.wavelength.engine.WalletEngine
import engineering.lightning.wavelength.model.PrepareSendResult
import engineering.lightning.wavelength.model.WalletState
import java.io.File
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Screen-local state that is not part of the wallet snapshot. */
data class UiState(
    val busy: Boolean = false,
    val message: String? = null,
    /** Shown once after creating a wallet. */
    val mnemonic: List<String>? = null,
    val invoice: String? = null,
    val quote: PrepareSendResult? = null,
)

class WalletViewModel(app: Application) : AndroidViewModel(app) {
    private val signerFiles = SignerFiles(File(app.noBackupFilesDir, "signer"))
    private val baseConfig = RuntimeConfig.preset(Network.SIGNET, dataDir = Wavelength.defaultDataDir(app))
    private var config = baseConfig.copy(remoteSigner = signerFiles.load())

    /** All wallet state the UI renders; the engine keeps it current. */
    val engine = WalletEngine(Wavelength.client, viewModelScope, config, autoStart = true)

    private val lifecycleLock = Mutex()
    private var restartOnReturn = false

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun create(password: String) = run {
        val result = engine.createWallet(CreateWalletRequest(password))
        _ui.update { it.copy(mnemonic = result.mnemonic) }
    }

    fun restore(password: String, words: String) = run {
        val mnemonic = words.trim().split(Regex("\\s+"))
        engine.restoreWallet(CreateWalletRequest(password, mnemonic = mnemonic, recoverState = true))
    }

    fun restoreFromDescriptors(password: String, text: String, birthday: String) = run {
        val descriptors = text.lines().map(String::trim).filter(String::isNotEmpty)
        engine.restoreWallet(
            CreateWalletRequest(password, descriptors = descriptors, birthday = parseBirthday(birthday), recoverState = true),
        )
    }

    /**
     * Saves the signer, restarts the daemon with it, and opens the wallet. A failed
     * first open can leave a database behind, which only unlock opens, so this
     * follows what the restarted daemon reports. It also corrects a wrong signer.
     */
    fun openWatchOnly(password: String, bundle: String, host: String, macaroonHex: String, tlsCert: String) = run {
        config = baseConfig.copy(remoteSigner = signerFiles.save(host, macaroonHex, tlsCert))
        engine.stop()
        if (engine.start(config).walletState == WalletState.LOCKED) {
            engine.unlockWallet(password.encodeToByteArray())
        } else {
            engine.restoreWallet(CreateWalletRequest(password, watchOnlyBundle = bundle.trim()))
        }
    }

    fun exportDescriptors() = run {
        val export = engine.listWalletDescriptors()
        val text = "Fingerprint ${export.rootFingerprint}\n\n" + export.descriptors.joinToString("\n\n")
        _ui.update { it.copy(message = text) }
    }

    /** Whether a watch-only wallet's signer settings are saved here. */
    val hasSigner: Boolean get() = config.remoteSigner != null

    fun unlock(password: String) = run { engine.unlockWallet(password.encodeToByteArray()) }

    fun receive(amountSat: Long) = run {
        val result = engine.receive(ReceiveRequest(amountSat))
        _ui.update { it.copy(invoice = result.invoice) }
    }

    fun prepareSend(destination: String, amountSat: Long?) = run {
        val request = when (val kind = Destination.classify(destination)) {
            is Destination.Invoice -> SendRequest.Invoice(
                destination,
                amountSat = amountSat.takeIf { kind.amount == InvoiceAmount.Amountless },
            )
            Destination.Address -> SendRequest.Onchain(destination, amountSat = amountSat)
            Destination.Empty -> throw WavelengthException.InvalidArgument("enter an invoice or address")
        }
        val quote = engine.prepareSend(request)
        _ui.update { it.copy(quote = quote) }
    }

    fun confirmSend() = run {
        val quote = _ui.value.quote ?: return@run
        _ui.update { it.copy(quote = null) }
        val sent = engine.sendPrepared(quote)
        _ui.update { it.copy(message = "Sent ${sent.actualAmountSat} sat") }
    }

    fun retry() = run { engine.start(config) }

    /**
     * Stops the daemon on the way to the background, because the OS would leave it
     * holding stale connections. A wallet comes back locked; a watch-only one also
     * needs its signer to answer before it opens.
     */
    fun onBackground() {
        viewModelScope.launch {
            lifecycleLock.withLock {
                if (engine.client.isRunning()) {
                    restartOnReturn = true
                    report { engine.stop() }
                }
            }
        }
    }

    fun onForeground() {
        viewModelScope.launch {
            lifecycleLock.withLock {
                if (restartOnReturn) {
                    restartOnReturn = false
                    report { engine.start(config) }
                }
            }
        }
    }

    fun dismiss() = _ui.update { it.copy(message = null, mnemonic = null, invoice = null, quote = null) }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = null) }
            try {
                report(block)
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun report(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: WavelengthException.RemoteSignerFailed) {
            _ui.update {
                it.copy(message = "The signer is unavailable or holds other keys. Your password is not the problem: retry once it is back.\n\n${e.message}")
            }
        } catch (e: WavelengthException) {
            _ui.update { it.copy(message = "${e.code}: ${e.message}") }
        }
    }

    private fun parseBirthday(text: String): Instant? {
        val date = text.trim().ifEmpty { return null }
        return try {
            Instant.parse("${date}T00:00:00Z")
        } catch (_: IllegalArgumentException) {
            throw WavelengthException.InvalidArgument("the birthday must look like 2026-01-31")
        }
    }
}
