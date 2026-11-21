#!/usr/bin/env bash
# Build the Debian VM disk image (rt-debian-N.ext4.gz) from the
# linuxcontainers.org Debian trixie arm64 rootfs.
#
#   sudo tools/image/build-image.sh <out.ext4.gz>
#
# Needs root and an arm64 host (or qemu-user binfmt), curl, xz, e2fsprogs.
set -euo pipefail
OUT=$(realpath -m "$1")
HERE=$(cd "$(dirname "$0")" && pwd)
LXC=https://images.linuxcontainers.org
W=$(mktemp -d)
# Never rm -rf while /dev, /proc or /sys are still bound in.
cleanup() {
    for m in dev proc sys; do umount -R "$W/r/$m" 2>/dev/null || umount -lR "$W/r/$m" 2>/dev/null || true; done
    if grep -q " $W/" /proc/self/mounts; then echo "still mounted under $W; not removing it" >&2
    else rm -rf "$W"; fi
}
trap cleanup EXIT

path=$(curl -sSf "$LXC/meta/1.0/index-system" | awk -F';' '$1=="debian" && $2=="trixie" && $3=="arm64" && $4=="default" {print $6}' | tail -1)
[ -n "$path" ] || { echo "no LXC trixie arm64 image" >&2; exit 1; }
echo "rootfs: $path"
mkdir "$W/r"
curl -sSfL "$LXC${path}rootfs.tar.xz" | tar -xJ -C "$W/r" --numeric-owner

R=$W/r
cp -a "$HERE/rootfs/." "$R/"
chown -R 0:0 "$R/etc" "$R/usr/local"
chmod 0440 "$R/etc/sudoers.d/debian"
rc=$(readlink "$R/etc/resolv.conf" || true)
rm -f "$R/etc/resolv.conf"; cp /etc/resolv.conf "$R/etc/resolv.conf"
mount -t proc proc "$R/proc"; mount --rbind --make-rslave /dev "$R/dev"; mount -t sysfs sys "$R/sys"
chroot "$R" /bin/sh -eux <<'EOF'
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q --no-install-recommends openssh-server sudo ca-certificates \
    curl git less procps python3 socat util-linux xz-utils unzip e2fsprogs
apt-get clean
rm -rf /var/lib/apt/lists/*
useradd -m -s /bin/bash -G sudo debian
passwd -d root; passwd -d debian
echo reterm > /etc/hostname
sed -i 's/^127\.0\.1\.1.*/127.0.1.1\treterm/' /etc/hosts
grep -q reterm /etc/hosts || printf '127.0.1.1\treterm\n' >> /etc/hosts
rm -f /etc/ssh/ssh_host_*
systemctl enable reterminal-boot.service ssh.service console-getty.service
rm -f /etc/systemd/system/getty.target.wants/getty@tty1.service
for u in systemd-networkd-wait-online.service fstrim.timer systemd-pstore.service \
    e2scrub_reap.service getty@tty1.service apt-daily.timer apt-daily-upgrade.timer; do
    systemctl mask "$u"
done
EOF
for m in dev proc sys; do umount -R "$R/$m"; done
rm -f "$R/etc/resolv.conf"
[ -z "$rc" ] || ln -s "$rc" "$R/etc/resolv.conf"
rm -rf "$R/tmp/"* "$R/var/log/apt/"* "$R/root/.bash_history"

# 1.2 GB; the app grows the file and reterminal-boot resizes the filesystem.
img=$W/disk.ext4
truncate -s 1200M "$img"
mkfs.ext4 -q -L reterminal -N 76800 -E root_owner=0:0 -d "$R" "$img"
gzip -9n < "$img" > "$OUT"
ls -la "$OUT"
