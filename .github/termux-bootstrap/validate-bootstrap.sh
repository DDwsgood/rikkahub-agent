#!/usr/bin/env bash
set -euo pipefail

variant=${1:?usage: validate-bootstrap.sh release|debug aarch64|x86_64 artifact-directory}
arch=${2:?usage: validate-bootstrap.sh release|debug aarch64|x86_64 artifact-directory}
artifact_dir=${3:?usage: validate-bootstrap.sh release|debug aarch64|x86_64 artifact-directory}

case "$variant" in
  release) package=excp.rikkahub ;;
  debug) package=excp.rikkahub.debug ;;
  *) echo "Unknown variant: $variant" >&2; exit 2 ;;
esac

case "$arch" in
  aarch64|x86_64) ;;
  *) echo "Unknown architecture: $arch" >&2; exit 2 ;;
esac

archive="$artifact_dir/bootstrap-$arch.zip"
expected_prefix="/data/data/$package/files/termux/usr"
work_dir=$(mktemp -d)
trap 'rm -r "$work_dir"' EXIT

test -s "$archive"
unzip -t "$archive" >/dev/null
mkdir "$work_dir/root"
unzip -q "$archive" -d "$work_dir/root"
test -f "$work_dir/root/SYMLINKS.txt"
required_files=(
  bin/bash
  bin/pkg
  etc/profile
  etc/profile.d/01-termux-bootstrap-second-stage-fallback.sh
  etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh
  lib/libtermux-exec-direct-ld-preload.so
  lib/libtermux-exec-linker-ld-preload.so
)
for path in "${required_files[@]}"; do
  test -s "$work_dir/root/$path"
done
test -d "$work_dir/root/tmp"
grep -Fq 'dash←./bin/sh' "$work_dir/root/SYMLINKS.txt"

machine=$(readelf -h "$work_dir/root/bin/bash" | awk -F: '/Machine:/ {gsub(/^[[:space:]]+/, "", $2); print $2}')
case "$arch:$machine" in
  aarch64:AArch64|x86_64:Advanced\ Micro\ Devices\ X86-64) ;;
  *) echo "Unexpected ELF machine for $arch: $machine" >&2; exit 1 ;;
esac

interpreter=$(readelf -l "$work_dir/root/bin/bash" |
  sed -n 's/.*Requesting program interpreter: \(.*\)]/\1/p')
[[ "$interpreter" == "/system/bin/linker64" ]]

runtime_paths=(bin etc lib libexec var/lib/dpkg/info)
for path in "${runtime_paths[@]}"; do
  test -e "$work_dir/root/$path"
done

grep -aRl "$expected_prefix" \
  "$work_dir/root/bin" "$work_dir/root/etc" "$work_dir/root/lib" \
  "$work_dir/root/libexec" >/dev/null

mapfile -t official_matches < <(
  grep -aRHn "/data/data/com.termux" \
    "$work_dir/root/bin" "$work_dir/root/etc" "$work_dir/root/lib" \
    "$work_dir/root/libexec" "$work_dir/root/var/lib/dpkg/info" \
    | grep -vE '/bin/termux-exec-ld-preload-lib:[0-9]+:[[:space:]]*#[[:space:]]' \
    || true
)
if ((${#official_matches[@]} > 0)); then
  echo "Official com.termux runtime path found in $archive" >&2
  printf '%s\n' "${official_matches[@]}" >&2
  exit 1
fi

if [[ "$variant" == release ]]; then
  opposite_prefix="/data/data/excp.rikkahub.debug/files/termux/usr"
else
  opposite_prefix="/data/data/excp.rikkahub/files/termux/usr"
fi
if grep -aRl "$opposite_prefix" "$work_dir/root" | grep -q .; then
  echo "Opposite-variant prefix found in $archive" >&2
  exit 1
fi

status_arch=$(awk '/^Package: gpgv$/ {found=1} found && /^Architecture:/ {print $2; exit}' \
  "$work_dir/root/var/lib/dpkg/status")
[[ "$status_arch" == "$arch" ]]

echo "$variant/$arch validated: $machine, $expected_prefix"
