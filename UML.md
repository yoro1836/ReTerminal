# Debian VM (UML)

The "Debian VM" session runs Debian trixie on a real Linux kernel (User-Mode
Linux, arm64) as plain app processes: no root, no proot. Code:
`core/main/src/main/java/com/rk/terminal/vm/DebianVm.kt` and
`core/main/src/main/assets/vm-login.sh`.

The first tab downloads the disk image (`cp-debian-N.ext4.gz`, ~190 MB) into
`files/vm/debian.ext4` (sparse) and boots the VM once. Every tab is an SSH
session (dropbear `dbclient`, key in `files/vm/id_dropbear`) to the guest's
sshd as `debian` (sudo without a password; the tab script creates it and sets the hostname `reterminal` on the first connect), which passt forwards to a free `127.0.0.1` port picked at boot (`files/vm/port`). The VM powers off when the
last session closes. Cores, memory, swap and disk are in Settings.

Prebuilt binaries in `core/main/src/main/jniLibs/arm64-v8a/` (an app may only
exec from its native lib dir, hence the `lib*.so` names):

| file | what | from |
|---|---|---|
| `libuml.so`, `libumstub.so` | Linux 7.3.1 UML kernel + its process stub, bionic static | [Raymer8639/linux-um-arm64](https://github.com/Raymer8639/linux-um-arm64) `um-arm64-v0.4.2` + `tools/uml/uml-arm64-fastpath.patch` (fastpath, memfd, stub zygote, SMP), built by its `tools/um-arm64/harness/build-bionic.sh` (NDK r29) with `EXTRA_CONFIG="tools/um-arm64/config/docker.config <this repo>/tools/uml/reterminal.config"` |
| `libumnet.so` | fd transport <-> passt framing | `tools/uml/umnet.c` in yoro1836/claude-phone |
| `libpasst.so` | passt, the guest's userspace network | [Raymer8639/passt-android](https://github.com/Raymer8639/passt-android) v0.4.1 |
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

The image is the claude-phone one: LXC Debian trixie arm64 + openssh-server,
sudo, user `claude`; its boot service installs the `cpkey=` key for root and
claude, makes a `cpswap=` MB swap file and grows the filesystem to the disk.
