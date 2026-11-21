#!/usr/bin/env bash
# Build the UML kernel, its modules and umusb for the app, then copy them into
# core/main/src/main/jniLibs/arm64-v8a (libuml, libumstub, libumlinitrd, libumusb).
#
#   tools/uml/build-kernel.sh [--no-install]
#
# Env: NDK (Android NDK r29; default $ANDROID_HOME/ndk/29.0.13846066),
#      SRC (Raymer linux-um-arm64 checkout to reuse; cloned to build/uml/linux if unset),
#      JOBS. Host needs: clang, make, flex, bison, bc, libelf-dev, kmod (depmod), git,
#      curl, gcc-aarch64-linux-gnu + libc6-dev-arm64-cross (busybox).
set -euo pipefail

TAG=um-arm64-v0.4.2
BUSYBOX_VER=1.37.0
BUSYBOX_SHA256=3311dff32e746499f4df0d5df04d7eb396382d7e108bb9250e7b519b837043a4
REPO=https://github.com/Raymer8639/linux-um-arm64
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
WORK=${WORK:-$ROOT/build/uml}
NDK=${NDK:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/29.0.13846066}
JOBS=${JOBS:-$(nproc)}
INSTALL=1
[ "${1:-}" = "--no-install" ] && INSTALL=0

[ -x "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ] || { echo "no NDK at $NDK (set NDK=)" >&2; exit 2; }
TOOL=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
mkdir -p "$WORK"

# 1. Source: Raymer's tag + our patches (fastpath/SMP/zygote, module exports).
if [ -z "${SRC:-}" ]; then
    SRC=$WORK/linux
    if [ ! -d "$SRC/.git" ]; then
        git clone --depth 1 --branch "$TAG" "$REPO" "$SRC"
        git -C "$SRC" -c user.email=build@reterminal -c user.name=build \
            am -q "$HERE/uml-arm64-fastpath.patch"
        git -C "$SRC" apply "$HERE/uml-arm64-modules.patch"
    fi
fi

# ccache, when installed: build-bionic.sh calls $NDK/.../bin/clang by absolute
# path, so hand it an NDK whose clang is a ccache wrapper (everything else is a
# symlink to the real NDK). Content-hashed compiler check, so a freshly
# installed NDK on CI still hits. CCACHE=0 turns it off.
KNDK=$NDK
if [ "${CCACHE:-1}" != 0 ] && command -v ccache >/dev/null; then
    KNDK=$WORK/ndk-ccache
    P=toolchains/llvm/prebuilt/linux-x86_64
    rm -rf "$KNDK"
    mkdir -p "$KNDK/$P/bin"
    for e in "$NDK"/*; do [ "$(basename "$e")" = toolchains ] || ln -s "$e" "$KNDK/"; done
    for e in "$NDK/$P"/*; do [ "$(basename "$e")" = bin ] || ln -s "$e" "$KNDK/$P/"; done
    for e in "$NDK/$P/bin"/*; do ln -s "$e" "$KNDK/$P/bin/"; done
    for c in clang clang++; do
        rm "$KNDK/$P/bin/$c"
        printf '#!/bin/sh\nexec ccache %s "$@"\n' "$NDK/$P/bin/$c" > "$KNDK/$P/bin/$c"
        chmod +x "$KNDK/$P/bin/$c"
    done
    export CCACHE_COMPILERCHECK=content CCACHE_BASEDIR=$WORK CCACHE_NOHASHDIR=1
    export CCACHE_SLOPPINESS=time_macros,include_file_mtime,include_file_ctime
    ccache -z >/dev/null
fi

# 2. Kernel (bionic static) with our config fragments.
O=$WORK/out
ART=$WORK/artifacts
mkdir -p "$O" "$ART"
(cd "$SRC" && NDK=$KNDK O=$O ART=$ART JOBS=$JOBS \
    EXTRA_CONFIG="tools/um-arm64/config/docker.config $HERE/reterminal.config" \
    bash tools/um-arm64/harness/build-bionic.sh)
LINUX=$ART/linux-bionic
STUB=$ART/stub_exe_bionic

# 3. initramfs: busybox + initramfs-init.sh as /init + lib/modules/<release>
#    (depmod'ed on the host). /init mounts the Debian disk, copies the modules
#    into a tmpfs over its /usr/lib/modules/<release> and switch_roots, so the
#    kernel and its modules always boot together.
MS=$WORK/modstage
rm -rf "$MS"
PATH="$TOOL:$PATH" make -C "$SRC" O="$O" ARCH=um SUBARCH=arm64 LLVM=1 \
    HOSTCC=clang HOSTCXX=clang++ INSTALL_MOD_PATH="$MS" INSTALL_MOD_STRIP=1 \
    -j"$JOBS" modules_install >/dev/null
REL=$(ls "$MS/lib/modules")
rm -f "$MS/lib/modules/$REL/build" "$MS/lib/modules/$REL/source"

# busybox, static arm64 (glibc cross gcc: gcc-aarch64-linux-gnu).
BB=busybox-$BUSYBOX_VER
if [ ! -x "$WORK/$BB/busybox" ] || [ ! -f "$WORK/$BB/busybox.links" ]; then
    [ -f "$WORK/$BB.tar.bz2" ] || curl -sSfL -o "$WORK/$BB.tar.bz2" "https://busybox.net/downloads/$BB.tar.bz2"
    echo "$BUSYBOX_SHA256  $WORK/$BB.tar.bz2" | sha256sum -c - >/dev/null
    rm -rf "$WORK/$BB" && tar xjf "$WORK/$BB.tar.bz2" -C "$WORK"
    (cd "$WORK/$BB" && make defconfig >/dev/null &&
        sed -i -e 's/^# CONFIG_STATIC is not set/CONFIG_STATIC=y/' \
               -e 's/^CONFIG_TC=y/# CONFIG_TC is not set/' \
               -e 's/^CONFIG_SHA1_HWACCEL=y/# CONFIG_SHA1_HWACCEL is not set/' \
               -e 's/^CONFIG_SHA256_HWACCEL=y/# CONFIG_SHA256_HWACCEL is not set/' .config &&
        make -j"$JOBS" CROSS_COMPILE=aarch64-linux-gnu- busybox busybox.links >/dev/null 2>&1) ||
        { echo "busybox build failed" >&2; exit 1; }
fi
{
    echo "dir /dev 0755 0 0"
    echo "nod /dev/console 0600 0 0 c 5 1"
    echo "dir /root 0755 0 0"
    echo "dir /proc 0755 0 0"
    echo "dir /sys 0755 0 0"
    echo "dir /bin 0755 0 0"
    echo "file /bin/busybox $WORK/$BB/busybox 0755 0 0"
    # Every applet as /bin/<name> -> busybox, made here rather than with
    # `busybox --install` at boot (that needs /proc/self/exe).
    sed 's|.*/||' "$WORK/$BB/busybox.links" | LC_ALL=C sort -u | grep -vx busybox |
        while read -r a; do echo "slink /bin/$a busybox 0777 0 0"; done
    echo "file /init $HERE/initramfs-init.sh 0755 0 0"
    echo "dir /lib 0755 0 0"
    (cd "$MS" && find lib -mindepth 1 | LC_ALL=C sort | while read -r f; do
        if [ -d "$f" ]; then echo "dir /$f 0755 0 0"
        else echo "file /$f $MS/$f 0644 0 0"; fi
    done)
} > "$WORK/initramfs.list"
"$O/usr/gen_init_cpio" -t 0 "$WORK/initramfs.list" | gzip -9n > "$WORK/libumlinitrd.so"

# 4. umusb (USB/IP server for USB passthrough).
(cd "$SRC" && NDK=$NDK SRC=$SRC/tools/um-arm64/harness/umusb.c O=$WORK/umusb ART=$ART \
    bash tools/um-arm64/harness/build-usb-bionic.sh) || true
# It copies the binary to $ART/umusb_bionic and deletes its O on success; its
# `file`-based check fails with some `file` versions and then leaves O alone.
UMUSB=$ART/umusb_bionic
[ -f "$UMUSB" ] || UMUSB=$WORK/umusb/umusb
[ -f "$UMUSB" ] || { echo "umusb build failed" >&2; exit 1; }
"$TOOL/llvm-strip" -o "$WORK/libumusb.so" "$UMUSB"

[ "$KNDK" = "$NDK" ] || ccache -s | grep -iE "hits|misses" | head -3
echo "kernel $REL: $(md5sum "$LINUX" | cut -c1-12)"
if [ "$INSTALL" = 1 ]; then
    J=$ROOT/core/main/src/main/jniLibs/arm64-v8a
    cp "$LINUX" "$J/libuml.so"
    cp "$STUB" "$J/libumstub.so"
    cp "$WORK/libumlinitrd.so" "$J/libumlinitrd.so"
    rm -f "$J/libumlmodules.so" "$ROOT/core/main/src/main/assets/uml-modules.stamp"
    cp "$WORK/libumusb.so" "$J/libumusb.so"
    echo "installed into $J"
fi
