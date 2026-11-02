package engineering.lightning.wavelength.embedded

import android.content.Context
import androidx.core.content.edit
import engineering.lightning.wavelength.profiles.WalletProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stores wallet profiles in private SharedPreferences. */
public class SharedPreferencesProfileStore(
    context: Context,
    name: String = "wavelength-wallet-profiles",
) : WalletProfileStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override suspend fun read(): String? = withContext(Dispatchers.IO) { prefs.getString(KEY, null) }

    override suspend fun write(value: String): Unit = withContext(Dispatchers.IO) {
        prefs.edit(commit = true) { putString(KEY, value) }
    }

    private companion object {
        const val KEY = "profiles"
    }
}
