#!/usr/bin/env bash
#
# fetch-bindings.sh stages the gomobile wavewalletdk bindings that
# wavelength-kmp wraps, for the daemon tag pinned in wavelength.version:
#
#   bindings/android/Wavewalletdk.aar
#   bindings/ios/Wavewalletdk.xcframework
#
# By default it downloads the release assets that wavelength CI attaches to
# every v* tag (the repository is public, so no credentials are needed). Set
# WAVELENGTH_DIR to a local wavelength checkout to build them from source with
# `make mobile-android` / `make mobile-ios` instead (needs Go, gomobile, the
# Android NDK, and macOS + Xcode for iOS).
#
# Downloads are verified against bindings.digests. After an intentional pin
# bump, run with --update-digests to record the new digests, and review them.
#
# Usage:
#   scripts/fetch-bindings.sh [android|ios|all] [--update-digests]

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${WAVELENGTH_VERSION:-$(tr -d '[:space:]' < "${ROOT}/wavelength.version")}"
REPO="${WAVELENGTH_REPO:-lightninglabs/wavelength}"
OUT="${ROOT}/bindings"
DIGESTS="${ROOT}/bindings.digests"

target="all"
update_digests=0
for arg in "$@"; do
	case "${arg}" in
	android | ios | all) target="${arg}" ;;
	--update-digests) update_digests=1 ;;
	*)
		echo "usage: $0 [android|ios|all] [--update-digests]" >&2
		exit 1
		;;
	esac
done

sha256() {
	if command -v sha256sum >/dev/null 2>&1; then
		sha256sum "$1" | cut -d' ' -f1
	else
		shasum -a 256 "$1" | cut -d' ' -f1
	fi
}

# verify checks an asset against the "<tag> <asset> <sha256>" line in
# bindings.digests, or records it when --update-digests is set.
verify() {
	local asset="$1" file="$2" got want
	got="$(sha256 "${file}")"
	if [[ "${update_digests}" == 1 ]]; then
		touch "${DIGESTS}"
		grep -v "^${TAG} ${asset} " "${DIGESTS}" > "${DIGESTS}.tmp" || true
		echo "${TAG} ${asset} ${got}" >> "${DIGESTS}.tmp"
		sort -o "${DIGESTS}" "${DIGESTS}.tmp"
		rm -f "${DIGESTS}.tmp"
		echo "==> recorded ${asset} ${got}"
		return
	fi
	want="$(awk -v t="${TAG}" -v a="${asset}" '$1 == t && $2 == a {print $3}' "${DIGESTS}" 2>/dev/null || true)"
	if [[ -z "${want}" ]]; then
		echo "error: no digest for ${TAG} ${asset} in bindings.digests;" >&2
		echo "       rerun with --update-digests after reviewing the release" >&2
		exit 1
	fi
	if [[ "${got}" != "${want}" ]]; then
		echo "error: ${asset} digest mismatch: got ${got}, want ${want}" >&2
		exit 1
	fi
	echo "==> verified ${asset}"
}

download() {
	local asset="$1" dest="$2"
	local url="https://github.com/${REPO}/releases/download/${TAG}/${asset}"
	echo "==> downloading ${url}"
	curl --fail --location --retry 3 --silent --show-error -o "${dest}" "${url}"
}

build_from_source() {
	local make_target="$1"
	if [[ ! -d "${WAVELENGTH_DIR}/sdk/wavewalletdk/mobile" ]]; then
		echo "error: wavelength checkout not found at ${WAVELENGTH_DIR}" >&2
		exit 1
	fi
	echo "==> building ${make_target} in ${WAVELENGTH_DIR} (unpinned local source)"
	(cd "${WAVELENGTH_DIR}" && make "${make_target}")
}

fetch_android() {
	mkdir -p "${OUT}/android"
	local dest="${OUT}/android/Wavewalletdk.aar"
	if [[ -n "${WAVELENGTH_DIR:-}" ]]; then
		build_from_source mobile-android
		cp "${WAVELENGTH_DIR}/sdk/wavewalletdk/mobile/build/android/Wavewalletdk.aar" "${dest}"
	else
		download Wavewalletdk.aar "${dest}.part"
		verify Wavewalletdk.aar "${dest}.part"
		mv "${dest}.part" "${dest}"
	fi
	echo "==> staged ${dest}"
}

fetch_ios() {
	mkdir -p "${OUT}/ios"
	local dest="${OUT}/ios/Wavewalletdk.xcframework"
	rm -rf "${dest}"
	if [[ -n "${WAVELENGTH_DIR:-}" ]]; then
		if [[ "$(uname)" != "Darwin" ]]; then
			echo "error: building the iOS xcframework requires macOS + Xcode" >&2
			exit 1
		fi
		build_from_source mobile-ios
		cp -R "${WAVELENGTH_DIR}/sdk/wavewalletdk/mobile/build/ios/Wavewalletdk.xcframework" "${dest}"
	else
		local tarball="${OUT}/ios/Wavewalletdk.xcframework.tar.gz"
		download Wavewalletdk.xcframework.tar.gz "${tarball}"
		verify Wavewalletdk.xcframework.tar.gz "${tarball}"
		tar -xzf "${tarball}" -C "${OUT}/ios"
		rm -f "${tarball}"
	fi
	echo "==> staged ${dest}"
}

echo "==> wavelength ${TAG}"
case "${target}" in
android) fetch_android ;;
ios) fetch_ios ;;
all)
	fetch_android
	fetch_ios
	;;
esac
