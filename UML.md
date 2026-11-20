# Debian VM (UML)

The "Debian VM" session runs Debian trixie on a real Linux kernel (User-Mode
Linux, arm64) as plain app processes: no root, no proot. Code:
`core/main/src/main/java/com/rk/terminal/vm/DebianVm.kt` and
`core/main/src/main/assets/vm-login.sh`.

The first tab downloads the disk image (`rt-debian-N.ext4.gz`, ~170 MB) into
`files/vm/debian.ext4` (sparse) and boots the VM once. Every tab is an SSH
session (dropbear `dbclient`, key in `files/vm/id_dropbear`) to the guest's
sshd as `debian`, which passt forwards to a free `127.0.0.1` port picked at boot (`files/vm/port`). The VM powers off when the
last session closes. Cores, memory, swap and disk are in Settings.

Prebuilt binaries in `core/main/src/main/jniLibs/arm64-v8a/` (an app may only
exec from its native lib dir, hence the `lib*.so` names):

| file | what | from |
|---|---|---|
| `libuml.so`, `libumstub.so` | Linux 7.3.1 UML kernel + its process stub, bionic static | [Raymer8639/linux-um-arm64](https://github.com/Raymer8639/linux-um-arm64) `um-arm64-v0.4.2` + `tools/uml/uml-arm64-fastpath.patch` (fastpath, memfd, stub zygote, SMP) + `tools/uml/uml-arm64-modules.patch` (exports bionic string functions, modpost accepts `kernel_` renames), built by its `tools/um-arm64/harness/build-bionic.sh` (NDK r29) with `EXTRA_CONFIG="tools/um-arm64/config/docker.config <this repo>/tools/uml/reterminal.config"` |
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

The image (`rt-debian-1`) is LXC Debian trixie arm64 (systemd, networkd,
resolved) + openssh-server, sudo, e2fsprogs; hostname `reterminal`, user
`debian` (passwordless sudo), root autologin on the console.
`reterminal-boot.service` reads the app's settings from the kernel command
line: `rtkey=` (base64 SSH public key, for root and debian) and `rtswap=` (swap
file MB), and grows the filesystem into the sparse disk. It was derived from
claude-phone's `cp-debian-2` by renaming its `claude` user and boot service.

## Kernel modules

`jniLibs/arm64-v8a/libumlmodules.so` (a plain tar; an asset copy did not work on the phone) is `make modules_install INSTALL_MOD_STRIP=1` of the same
build (`lib/modules/<release>`, `build` link removed); `assets/uml-modules.stamp` is the
first 12 hex of the kernel's md5. `vm-login.sh` unpacks it into the guest's `/lib/modules` as root when the
guest's `/lib/modules/.rt-stamp` differs. `modprobe` needs the `kmod` package.

## USB passthrough

Drawer > Debian VM row > USB icon lists the phone's USB devices. Attach: the app asks
Android for permission, opens the device, starts `libumusb.so --usbsock <abstract name>
--listen 127.0.0.1:<port>` and sends it the usbfs fd over SCM_RIGHTS. Then, as root in the
guest, bash opens `/dev/tcp/<gateway>/<port>` (passt maps the gateway to the phone's
loopback) and writes `<vhci port> 3 <devid> <speed>` to
`/sys/devices/platform/vhci_hcd.0/attach`. Ports 0-7 are the USB 2 hub, 8-15 USB 3.
USB core and vhci_hcd are built in; device drivers are modules (`kmod` + udev load them).

## Rebuilding the kernel

`tools/uml/build-kernel.sh` does the whole thing: clones Raymer's `um-arm64-v0.4.2`, applies
`uml-arm64-fastpath.patch` (git am) and `uml-arm64-modules.patch`, builds the bionic kernel
with `reterminal.config`, packs the modules tar, builds umusb, and copies the results into
`jniLibs/arm64-v8a` (plus `assets/uml-modules.stamp`; `--no-install` skips that). Needs NDK
r29 (`NDK=`) and clang, flex, bison, bc, libelf-dev, kmod. Work dir: `build/uml`.
The `UML kernel` workflow runs it on GitHub (manually, or on pushes touching `tools/uml/`)
and uploads the four libs and the stamp as the `uml-kernel` artifact.
