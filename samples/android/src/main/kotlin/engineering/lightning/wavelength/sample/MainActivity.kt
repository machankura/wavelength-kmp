package engineering.lightning.wavelength.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import engineering.lightning.wavelength.Destination
import engineering.lightning.wavelength.InvoiceAmount
import engineering.lightning.wavelength.engine.RuntimePhase
import engineering.lightning.wavelength.engine.WalletSnapshot

class MainActivity : ComponentActivity() {
    private val vm: WalletViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.onBackground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val snapshot by vm.engine.snapshot.collectAsStateWithLifecycle()
                val ui by vm.ui.collectAsStateWithLifecycle()
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Wavelength (signet)", style = MaterialTheme.typography.headlineSmall)
                        Text("Phase: ${snapshot.phase}", style = MaterialTheme.typography.labelLarge)
                        if (ui.busy) CircularProgressIndicator()
                        PhaseContent(snapshot, ui, vm)
                    }
                }
                ui.mnemonic?.let { words ->
                    InfoDialog("Back up these words", words.joinToString(" "), vm::dismiss)
                }
                ui.message?.let { InfoDialog("Wavelength", it, vm::dismiss) }
                ui.quote?.let { quote ->
                    AlertDialog(
                        onDismissRequest = vm::dismiss,
                        title = { Text("Confirm payment") },
                        text = {
                            Text(
                                "${quote.amountSat} sat via ${quote.rail?.wire ?: "unknown rail"}\n" +
                                    "fee ${if (quote.feeKnown) "${quote.expectedFeeSat} sat" else "unknown"}" +
                                    quote.warning.takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty(),
                            )
                        },
                        confirmButton = { TextButton(onClick = vm::confirmSend) { Text("Send") } },
                        dismissButton = { TextButton(onClick = vm::dismiss) { Text("Cancel") } },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PhaseContent(snapshot: WalletSnapshot, ui: UiState, vm: WalletViewModel) {
    when (snapshot.phase) {
        RuntimePhase.NEEDS_WALLET -> Onboarding(vm)
        RuntimePhase.LOCKED -> Locked(vm)
        RuntimePhase.SYNCING -> Text("Syncing… block ${snapshot.info?.blockHeight ?: 0}")
        RuntimePhase.RESTORING -> Text("Restoring your wallet…")
        RuntimePhase.READY -> Wallet(snapshot, ui, vm)
        RuntimePhase.ERROR, RuntimePhase.STOPPED -> {
            Text(snapshot.error?.message ?: "The runtime is stopped.")
            Button(onClick = vm::retry) { Text("Start") }
        }
        else -> Text("Starting the embedded wallet…")
    }
}

@Composable
private fun ColumnScope.Onboarding(vm: WalletViewModel) {
    var form by remember { mutableStateOf(OnboardingForm.MNEMONIC) }
    when (form) {
        OnboardingForm.MNEMONIC -> {
            var words by remember { mutableStateOf("") }
            PasswordAction("Create wallet", vm::create)
            HorizontalDivider()
            OutlinedTextField(words, { words = it }, label = { Text("Mnemonic to restore") }, modifier = Modifier.fillMaxWidth())
            PasswordAction("Restore") { vm.restore(it, words) }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { form = OnboardingForm.DESCRIPTORS }) { Text("Restore from descriptors") }
                TextButton(onClick = { form = OnboardingForm.WATCH_ONLY }) { Text("Watch-only wallet") }
            }
        }
        OnboardingForm.DESCRIPTORS -> DescriptorForm(vm) { form = OnboardingForm.MNEMONIC }
        OnboardingForm.WATCH_ONLY -> WatchOnlyForm(vm, needsBundle = true) { form = OnboardingForm.MNEMONIC }
    }
}

private enum class OnboardingForm { MNEMONIC, DESCRIPTORS, WATCH_ONLY }

@Composable
private fun ColumnScope.Locked(vm: WalletViewModel) {
    var fixingSigner by remember { mutableStateOf(false) }
    if (fixingSigner) {
        WatchOnlyForm(vm, needsBundle = false) { fixingSigner = false }
    } else {
        PasswordAction("Unlock", vm::unlock)
        if (vm.hasSigner) TextButton(onClick = { fixingSigner = true }) { Text("Signer settings") }
    }
}

/** Fields that scroll above a submit row that stays in reach, however long a paste is. */
@Composable
private fun ColumnScope.Form(
    submit: String,
    enabled: Boolean,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    fields: @Composable () -> Unit,
) {
    Column(
        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { fields() }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSubmit, enabled = enabled) { Text(submit) }
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun ColumnScope.DescriptorForm(vm: WalletViewModel, onBack: () -> Unit) {
    var descriptors by remember { mutableStateOf("") }
    var birthday by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Form("Restore", descriptors.isNotBlank() && password.length >= 8, { vm.restoreFromDescriptors(password, descriptors, birthday) }, onBack) {
        SecretField(descriptors, { descriptors = it }, "Private descriptors, one per line")
        OutlinedTextField(birthday, { birthday = it }, label = { Text("Earliest funds, 2026-01-31 (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        PasswordField(password) { password = it }
    }
}

@Composable
private fun ColumnScope.WatchOnlyForm(vm: WalletViewModel, needsBundle: Boolean, onBack: () -> Unit) {
    var bundle by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var macaroon by remember { mutableStateOf("") }
    var cert by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val complete = (!needsBundle || bundle.isNotBlank()) && host.isNotBlank() && macaroon.isNotBlank() && cert.isNotBlank() && password.length >= 8
    Form("Open", complete, { vm.openWatchOnly(password, bundle, host, macaroon, cert) }, onBack) {
        if (needsBundle) SecretField(bundle, { bundle = it }, "Signer bundle (wavesigner export-watchonly)")
        OutlinedTextField(host, { host = it }, label = { Text("Signer host:port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SecretField(macaroon, { macaroon = it }, "Signer macaroon, as hex")
        SecretField(cert, { cert = it }, "Signer TLS certificate")
        PasswordField(password) { password = it }
    }
}

/** A multi-line paste target that never echoes its keys. */
@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        maxLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text("Password (8+ characters)") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordAction(label: String, onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    PasswordField(password) { password = it }
    Button(onClick = { onSubmit(password) }, enabled = password.length >= 8) { Text(label) }
}

@Composable
private fun Wallet(snapshot: WalletSnapshot, ui: UiState, vm: WalletViewModel) {
    val balance = snapshot.balance
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("${balance?.confirmedSat ?: 0} sat", style = MaterialTheme.typography.headlineMedium)
            Text("pending in ${balance?.pendingInSat ?: 0} · out ${balance?.pendingOutSat ?: 0}")
            SelectionContainer { Text("Identity ${snapshot.info?.identityPubKey.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
        }
    }

    var amount by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    val kind = Destination.classify(destination)
    val needsAmount = kind == Destination.Address ||
        (kind is Destination.Invoice && kind.amount == InvoiceAmount.Amountless)

    OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Amount (sat)") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.receive(amount.toLongOrNull() ?: 0) }, enabled = amount.isNotEmpty()) { Text("Receive") }
        TextButton(onClick = vm::exportDescriptors) { Text("Export descriptors") }
    }
    ui.invoice?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }

    OutlinedTextField(destination, { destination = it }, label = { Text("Invoice or address") }, modifier = Modifier.fillMaxWidth())
    Button(
        onClick = { vm.prepareSend(destination, amount.toLongOrNull()) },
        enabled = kind != Destination.Empty && (!needsAmount || amount.isNotEmpty()),
    ) { Text("Review payment") }

    Spacer(Modifier.padding(4.dp))
    Text("Activity", style = MaterialTheme.typography.titleMedium)
    LazyColumn {
        items(snapshot.activity, key = { it.id }) { entry ->
            Text("${entry.kind?.wire} ${entry.amountSat} sat · ${entry.status?.wire}")
        }
    }
}

@Composable
private fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { SelectionContainer { Text(body) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
