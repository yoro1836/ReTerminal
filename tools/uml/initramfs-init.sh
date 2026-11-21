#!/bin/sh
# /init of ReTerminal's initramfs (libumlinitrd.so, passed to UML as initrd=).
# The initramfs holds busybox and lib/modules/<release> of the same kernel
# build. Mount the Debian disk, put the modules on it (in a tmpfs over
# /usr/lib/modules/<release>, so the disk itself is never written), then
# switch_root to systemd. "rtinit=shell" on the command line stops in a shell.
export PATH=/bin
busybox --install -s /bin
mkdir -p /dev /proc /sys /root
mount -t devtmpfs devtmpfs /dev
mount -t proc proc /proc

die() {
	echo "rt-init: $*; cannot boot" >/dev/console
	exec sh </dev/console >/dev/console 2>&1
}

root=/dev/ubda
fstype=ext4
for a in $(cat /proc/cmdline); do
	case "$a" in
	root=*) root=${a#root=} ;;
	rootfstype=*) fstype=${a#rootfstype=} ;;
	rtinit=shell) sh </dev/console >/dev/console 2>&1 ;;
	esac
done

mount -t "$fstype" -o rw "$root" /root || die "mounting $root"

# The one release this initramfs was built with (equals uname -r).
rel=
for d in /lib/modules/*; do [ -d "$d" ] && rel=${d##*/}; done
if [ -n "$rel" ]; then
	m=/root/usr/lib/modules/$rel
	mkdir -p "$m" &&
	mount -t tmpfs -o mode=0755 modules "$m" &&
	cp -a "/lib/modules/$rel/." "$m/" &&
	mount -o remount,ro "$m" ||
	echo "rt-init: could not set up kernel modules" >/dev/console
fi

umount /proc
mount --move /dev /root/dev
exec switch_root /root /sbin/init "$@"
die "switch_root"
