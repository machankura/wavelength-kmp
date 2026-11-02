# Syncing to a new wavelength release

This runbook mirrors the TypeScript SDK's `syncing-daemon-release` skill.

1. **Bump the pin.** Put the new tag in `wavelength.version`, for example
   `v0.2.2`.
2. **Regenerate the models.** Run `scripts/generate-models.sh`, then
   review the diff in `wavelength-core/.../model/Models.kt` and
   `wire/Requests.kt`.
   - **New fields** need nothing else.
   - **New verbs** in `sdk/wavewalletdk/mobile` need a `FacadeVerb`, a
     mapping in both `AndroidFacadeBindings` and `IosFacadeBindings`, a
     typed method on `WavelengthClient`, and a row in
     [parity.md](parity.md).
   - **A changed `mobileConfig`** fails `RuntimeConfigTest`; update
     `wire/Mobile.kt` and `RuntimeConfig` to match.
3. **Stage the bindings.** Run
   `scripts/fetch-bindings.sh all --update-digests`. Check that the new
   `bindings.digests` lines match the assets on the official release.
4. **Review the error strings.** Compare `sdk/wavewalletdk/errors.go` and
   `mobile/*.go` with `ErrorClassifier`, and update the patterns and
   `ErrorClassifierTest`.
5. **Review the presets.** Compare
   `wavelength-sdk/packages/core/src/config.ts` (`NETWORK_ENDPOINTS`) with
   `NetworkPresets`.
6. **Test on Android:**
   - `./gradlew check`
   - `:wavelength-embedded:connectedAndroidDeviceTest` on an emulator
   - check that a wallet created with the previous release still unlocks
     on the new one
7. **Test on macOS:** run `./gradlew iosSimulatorArm64Test`.
8. **Release.** Follow [RELEASE.md](../RELEASE.md).
