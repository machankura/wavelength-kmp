package engineering.lightning.wavelength.testing

import engineering.lightning.wavelength.FacadeWavelengthClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * A real [FacadeWavelengthClient] over a simulated wallet, for tests and UI
 * previews. Keep [bindings] to drive the simulation (settle receives, inject
 * failures, inspect requests).
 */
public fun fakeWavelengthClient(
    bindings: FakeFacadeBindings = FakeFacadeBindings(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): FacadeWavelengthClient = FacadeWavelengthClient(bindings, dispatcher)
