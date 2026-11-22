# Debian VM (UML)

The "Debian VM" session runs Debian trixie on a real Linux kernel (User-Mode
Linux, arm64) as plain app processes: no root, no proot. Code:
`core/main/src/main/java/com/rk/terminal/vm/DebianVm.kt` and
`core/main/src/main/assets/vm-login.sh`.

The first tab downloads the disk image (`rt-debian-N.ext4.gz`, ~170 MB) into
`files/vm/debian.ext4` (sparse) and boots the VM once. Every tab is an SSH
session (dropbear `dbclient`, key in `files/vm/id_dropbear`) to the guest's
sshd as `debian`, which passt forwards to a free `127.0.0.1` port picked at boot (`files/vm/port`). The VM outlives its tabs
(Stop/Restart are in the drawer). "Start the VM with the app" (default on) boots
it when the first Debian tab opens; while the VM is off or booting, a Debian tab
shows that over the terminal area (`TerminalOverlay.kt`, state from
`DebianVm.state`; "ready" = guest sshd answers), and its login script waits and
reconnects after a restart. The tab prints nothing before the login; "Show log" on the overlay shows `console.log`. Cores, memory, swap and disk are in Settings.

Prebuilt binaries in `core/main/src/main/jniLibs/arm64-v8a/` (an app may only
exec from its native lib dir, hence the `lib*.so` names):

| file | what | from |
|---|---|---|
| `libuml.so`, `libumstub.so` | Linux 7.3.1 UML kernel + its process stub, bionic static | [Raymer8639/linux-um-arm64](https://github.com/Raymer8639/linux-um-arm64) `um-arm64-v0.4.2` + `tools/uml/uml-arm64-fastpath.patch` (fastpath, memfd, stub zygote, SMP, persistent physmem fd, stubs off little cores, deferred IPIs, yielding handoff spin, PROT_NONE TLB anchors; `stub_pin=0` and `tlb_anchor=0` turn those off) + `tools/uml/uml-arm64-modules.patch` (exports bionic string functions, modpost accepts `kernel_` renames), built by its `tools/um-arm64/harness/build-bionic.sh` (NDK r29) with `EXTRA_CONFIG="tools/um-arm64/config/docker.config <this repo>/tools/uml/reterminal.config"` |
| `libumnet.so` | fd transport <-> passt framing | `tools/uml/umnet.c` in yoro1836/claude-phone |
| `libumusb.so` | USB/IP server for one usbfs fd (USB passthrough) | Raymer `tools/um-arm64/harness/umusb.c`, built by its `build-usb-bionic.sh` (NDK r29), stripped |
| `libpasst.so` | passt, the guest's userspace network | [Raymer8639/passt-android](https://github.com/Raymer8639/passt-android) v0.4.1 |
| `libe2fsck.so`, `libresize2fs.so` | e2fsprogs e2fsck + resize2fs, static bionic, used to shrink the disk while the VM is off | [tytso/e2fsprogs](https://github.com/tytso/e2fsprogs) `v1.47.3`, `configure --host=aarch64-linux-android --disable-nls --disable-fuse2fs --disable-uuidd --disable-defrag --disable-imager --disable-debugfs --disable-tdb --disable-e2initrd-helper --without-libarchive --disable-elf-shlibs` with `LDFLAGS=-static`, then `make libs`, `make -C resize`, `make -C e2fsck e2fsck`, stripped |
| `libdropbear.so` | dropbearmulti (`dbclient`, `dropbearkey`), static bionic | [mkj/dropbear](https://github.com/mkj/dropbear) `DROPBEAR_2025.88`, see below |

dropbear build (NDK r29):

```sh
cat > localoptions.h <<'X'
#define DROPBEAR_CLI_PASSWORD_AUTH 0
#define DROPBEAR_CLI_INTERACT_AUTH 0
#define DROPBEAR_X11FWD 0
#define DROPBEAR_CLI_AGENTFWD 0
#define DROPBEAR_SVR_AGENTFWD 0
#define DROPBEAR_SVR_PASSWORD_AUTH 0
X
CC=$T/aarch64-linux-android30-clang AR=$T/llvm-ar RANLIB=$T/llvm-ranlib \
  ../dropbear/configure --host=aarch64-linux-android --disable-zlib --disable-lastlog \
  --disable-utmp --disable-utmpx --disable-wtmp --disable-wtmpx --disable-syslog \
  --disable-pututline --disable-pututxline --enable-static --disable-harden
make PROGRAMS="dbclient dropbearkey" MULTI=1 STATIC=1
llvm-strip -o libdropbear.so dropbearmulti
```

The image (`rt-debian-2`) is LXC Debian trixie arm64 (systemd, networkd,
resolved) + openssh-server, sudo, e2fsprogs; hostname `reterm`, user
`debian` (passwordless sudo), root autologin on the console.
`reterminal-boot.service` reads the app's settings from the kernel command
line: `rtkey=` (base64 SSH public key, for root and debian) and `rtswap=` (swap
file MB), and grows the filesystem into the sparse disk. It is built by
`tools/image/build-image.sh` (files added to the rootfs are in
`tools/image/rootfs/`) and published as the GitHub release `rt-debian-N` by the
"Debian disk image" workflow (run it with the new N, then bump `IMAGE` in
`DebianVm.kt`; installed disks are kept).

## Kernel modules (initramfs)

`jniLibs/arm64-v8a/libumlinitrd.so` is a gzip'd cpio passed as `initrd=`, an ordinary
busybox initramfs: static arm64 busybox 1.37.0 (built from source by build-kernel.sh with
`aarch64-linux-gnu-gcc`), `/init` = `tools/uml/initramfs-init.sh`, and `lib/modules/<release>`
(`modules_install`, stripped, depmod'ed) of the same kernel build. `/init` mounts `/dev/ubda`
(`root=`, `rootfstype=` honoured), copies the modules into a read-only tmpfs over the disk's
`/usr/lib/modules/<release>`, moves /dev and `switch_root`s to `/sbin/init`. Kernel and
modules always match, udev can load drivers from boot, and the disk is never written. Cost:
the modules (~10 MB) stay in guest RAM. `rtinit=shell` on the kernel command line drops to a
shell first. `modprobe` needs the `kmod` package in the guest.

## USB passthrough

Drawer > Debian VM row > USB icon lists the phone's USB devices. Attach: the app asks
Android for permission, opens the device, starts `libumusb.so --usbsock <abstract name>
--listen 127.0.0.1:<port>` and sends it the usbfs fd over SCM_RIGHTS. Then, as root in the
guest, bash opens `/dev/tcp/<gateway>/<port>` (passt maps the gateway to the phone's
loopback) and writes `<vhci port> 3 <devid> <speed>` to
`/sys/devices/platform/vhci_hcd.0/attach`. Ports 0-7 are the USB 2 hub, 8-15 USB 3.
USB core and vhci_hcd are built in; device drivers are modules (`kmod` + udev load them).

## Rebuilding the kernel

`tools/uml/build-kernel.sh` does the whole thing (kernel, initramfs with modules, umusb): clones Raymer's `um-arm64-v0.4.2`, applies
`uml-arm64-fastpath.patch` (git am) and `uml-arm64-modules.patch`, builds the bionic kernel
with `reterminal.config`, packs the modules tar, builds umusb, and copies the results into
`jniLibs/arm64-v8a` as libuml, libumstub, libumlinitrd and libumusb (`--no-install` skips that). Needs NDK
r29 (`NDK=`) and clang, flex, bison, bc, libelf-dev, kmod, curl, gcc-aarch64-linux-gnu,
libc6-dev-arm64-cross. Work dir: `build/uml`.
With `ccache` installed the kernel compiles go through it (`CCACHE=0` disables): a full
rebuild with a warm cache took 38 s instead of 7.5 min in the sandbox, same output md5.
The `UML kernel` workflow runs it on GitHub (manually, or on pushes touching `tools/uml/`)
and uploads the four libs and the stamp as the `uml-kernel` artifact. It caches the NDK and
the ccache directory between runs.

## Port forwarding

Settings > Debian VM > Port forwarding (`vm/PortForwarding.kt`) keeps rules
phone port -> VM port (pref `ports`, e.g. `8080, 3000:80`), added to passt's
`-t` list next to the SSH port at boot (a Restart VM prompt shows when they
changed). It also lists the guest's TCP listeners (`ss -ltn` over root SSH)
for one-tap adding. The other direction needs nothing: the guest reaches the phone's
loopback at its gateway address.

## X11 (Termux:X11)

The display button left of + in the terminal's top bar (`vm/X11.kt`) starts the
Termux:X11 X server in this app (`app_process` with the Termux:X11 APK as
`CLASSPATH`, `com.termux.x11.CmdEntryPoint :0`, `TMPDIR` and
`XKB_CONFIG_ROOT` under `files/x11`), relays its unix socket to
`127.0.0.1:6000`, and opens the Termux:X11 app. In the guest,
`/etc/profile.d/reterminal-display.sh` (image `rt-debian-4`) sets
`DISPLAY=<gateway>:0`. XKB data: `assets/xkb.zip`, made from Debian's xkb-data
by `tools/x11/make-xkb-zip.sh`.

Anland is not supported: it passes GPU buffers (dmabuf fds) between the
Android app and a compositor on the same kernel, which a UML guest is not.

## GPU (Vulkan) and zero-copy X

Settings > Debian VM > "GPU acceleration" (off by default) adds two kernel args
(the kernel's hostdev passthrough, see `tools/uml/uml-arm64-fastpath.patch`):
`hostdev=/dev/dri/renderD128,dri/renderD128,226:128,drm` gives the guest the
phone's render node, and `hostdev=<files/x11/tmp/.X11-unix/X0>,hostx11,240:0,sock`
makes `/dev/hostx11` a connection to the in-app Termux:X11 server per open
(`/dev/hostshm`, 4-byte host memfds, is always there). In the image (rt-debian-5+; user debian in render/video from rt-debian-6):
RADV Xclipse ([JimVulkan/radv-xclipse](https://github.com/JimVulkan/radv-xclipse)
v1.9.0 glibc build, sha256-checked) with libvulkan1/vulkan-tools, and from
`tools/image/gpu/` (compiled by build-image.sh): `xbridge` (systemd unit
reterminal-xbridge, only when /dev/hostx11 exists) serving the guest
`/tmp/.X11-unix/X0` and forwarding DRI3 fds, and a libxshmfence in
`/usr/lib/xclipse/shim` that puts fences in host memory. profile.d then sets
`DISPLAY=:0` and that `LD_LIBRARY_PATH`. Kernel debug: `hostdev_debug=1`,
`hostdev:` lines in dmesg.

## CPU pinning

Settings > Debian VM > CPU pinning (`vm/CpuPinning.kt`, its own screen: a switch, then the cores grouped Little/Mid/Big by max frequency) picks the phone cores the whole VM runs on
(prefs `cpu_pin` + `cpu_mask`). At boot
the command is started under `/system/bin/taskset <mask>`, which umnet, passt,
UML's vCPU threads and the stubs inherit. Android's cpusets still apply, so a
backgrounded app can be held to the little cores regardless.
