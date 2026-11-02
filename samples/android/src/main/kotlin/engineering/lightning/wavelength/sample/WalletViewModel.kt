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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    private val config = RuntimeConfig.preset(Network.SIGNET, dataDir = Wavelength.defaultDataDir(app))

    /** All wallet state the UI renders; the engine keeps it current. */
    val engine = WalletEngine(Wavelength.client, viewModelScope, config, autoStart = true)

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

    fun retry() = run { engine.start() }

    fun dismiss() = _ui.update { it.copy(message = null, mnemonic = null, invoice = null, quote = null) }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = null) }
            try {
                block()
            } catch (e: WavelengthException) {
                _ui.update { it.copy(message = "${e.code}: ${e.message}") }
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }
}
