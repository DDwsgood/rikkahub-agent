# Embedded Termux bootstrap

Official Termux bootstrap archives are compiled for
`/data/data/com.termux/files/usr` and cannot be relocated into RikkaHub's app sandbox.
The app therefore deliberately refuses to download an official bootstrap.

Build both bootstrap archives from `termux/termux-packages` for the exact Android
application id and prefix used by the target variant:

- release: package `excp.rikkahub`, prefix `/data/data/excp.rikkahub/files/termux/usr`
- debug: package `excp.rikkahub.debug`, prefix `/data/data/excp.rikkahub.debug/files/termux/usr`

## Building the archives

Use Linux with Docker. As of July 2026, Termux recommends the `infra-improvs` branch while
the bootstrap builder changes are being upstreamed:

```bash
git clone --depth 1 --branch infra-improvs \
  https://github.com/agnostic-apollo/termux-packages.git
cd termux-packages
```

For a release bootstrap, edit `scripts/properties.sh`:

```bash
TERMUX_APP__PACKAGE_NAME="excp.rikkahub"
TERMUX__ROOTFS_SUBDIR="files/termux"
```

The derived paths must print as:

```text
TERMUX__ROOTFS=/data/data/excp.rikkahub/files/termux
TERMUX__HOME=/data/data/excp.rikkahub/files/termux/home
TERMUX__PREFIX=/data/data/excp.rikkahub/files/termux/usr
```

Then build only the ABIs supported by this app:

```bash
./scripts/run-docker.sh ./clean.sh
./scripts/run-docker.sh ./scripts/build-bootstraps.sh \
  --architectures aarch64,x86_64 --no-build-unneeded-subpackages \
  2>&1 | tee build-bootstrap.log
sha256sum bootstrap-aarch64.zip bootstrap-x86_64.zip
```

The `infra-improvs` builder cleans by default and no longer accepts the older `-f` flag.
Use `--no-clean` only to resume after correcting a transient download or package failure.

Repeat from a clean build tree with package `excp.rikkahub.debug` for debug APKs. A release
archive cannot be reused by a debug APK because the absolute app-data path is compiled into
Termux packages.

The locally built archive provides the packages included by `build-bootstraps.sh`. To make
`apt`/`pkg` install or upgrade arbitrary additional packages, those packages must also be
built for the same package/prefix and published in a custom APT repository. Official Termux
mirrors contain packages compiled for `com.termux` and must not be configured as a fallback.

The package repository used by `apt` must contain packages built for the same package and
prefix. Configure the app build through Gradle properties:

```properties
termuxBootstrapUrlTemplate=https://example.invalid/bootstrap/{package}/bootstrap-{arch}.zip
termuxBootstrapAarch64Sha256=<64 lowercase hex characters>
termuxBootstrapX8664Sha256=<64 lowercase hex characters>
```

`{package}` and `{arch}` are replaced at runtime. Supported architecture values are
`aarch64` and `x86_64`. Leaving these properties unset disables bootstrap installation
with an explicit error instead of installing an incompatible official archive.

CI builds may instead place variant-specific archives at
`app/src/<variant>/assets/termux/bootstrap-{arch}.zip`. The installer prefers a bundled
archive and falls back to the configured URL only when the asset is absent. SHA-256 remains
mandatory for bundled archives so the build cannot accidentally package the wrong variant.

On Android 10 and newer, apps targeting API 29+ cannot directly `execve()` writable files
under their app-data directory. RikkaHub therefore starts the bootstrap shell through the
first executable 64-bit Android linker at `/system/bin/linker64`,
`/apex/com.android.runtime/bin/linker64`, or `/system/bin/bootstrap/linker64`, and preloads
the bootstrap's
`libtermux-exec-linker-ld-preload.so`; termux-exec applies the same system-linker routing to
child commands. Android 8 and 9 use the direct termux-exec variant.

Installation is serialized between app startup and tool calls. The installer creates HOME
before publishing PREFIX, runs the mandatory bootstrap second stage, then checks `pkg`,
`apt`, termux-exec child launching, and `dpkg --audit` before writing its readiness marker.
An intact but incomplete installation is repaired in place: HOME, TMPDIR, and the app-managed
environment file are restored without replacing PREFIX or HOME. Consequently packages,
configuration, scripts, and projects survive app restarts and APK upgrades. A saved working
directory that belongs to another build variant or no longer exists falls back to the current
variant's persistent Termux HOME instead of being passed to `ProcessBuilder` as an invalid cwd.
