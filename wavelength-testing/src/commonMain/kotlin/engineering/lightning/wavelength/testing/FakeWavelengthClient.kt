package engineering.lightning.wavelength.testing

import engineering.lightning.wavelength.ActivityEvent
import engineering.lightning.wavelength.ActivityOptions
import engineering.lightning.wavelength.ErrorClassifier
import engineering.lightning.wavelength.EmbeddedWavelengthClient
import engineering.lightning.wavelength.FacadeWavelengthClient
import engineering.lightning.wavelength.model.Entry
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * A [FacadeWavelengthClient] over [FakeFacadeBindings] that never blocks a
 * thread: unary calls run inline and the activity stream suspends. Use it
 * with `runTest` and virtual time (e.g. to test code built on WalletEngine).
 */
public class FakeWavelengthClient(
    public val bindings: FakeFacadeBindings = FakeFacadeBindings(),
) : EmbeddedWavelengthClient by FacadeWavelengthClient(bindings, Dispatchers.Unconfined) {

    override fun activity(options: ActivityOptions): Flow<Entry> =
        activityEvents(options).filterIsInstance<ActivityEvent.EntryReceived>().map { it.entry }

    override fun activityEvents(options: ActivityOptions): Flow<ActivityEvent> = flow {
        val subscription = try {
            bindings.openSubscription(options.includeExisting, options.kinds, options.cursor ?: 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ErrorClassifier.classify(e)
        }
        try {
            emit(ActivityEvent.Opened)
            while (true) {
                val entry = try {
                    subscription.receive()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw ErrorClassifier.classify(e)
                } ?: break
                emit(ActivityEvent.EntryReceived(entry))
            }
        } finally {
            subscription.close()
        }
    }
}
