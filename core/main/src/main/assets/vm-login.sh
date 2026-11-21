#!/system/bin/sh
# A Debian VM tab: wait for the VM (DebianVm.kt writes $VM_DIR/status), then SSH in.
# While it boots, the kernel console is shown live.
D="$VM_DIR"
DB="$NATIVE_LIB_DIR/libdropbear.so"
export HOME="$D"
# A probe must not hang: passt may accept before the guest's sshd answers.
probe() { timeout 8 "$DB" dbclient -y -i "$D/id_dropbear" -p "$(cat "$D/port")" -T debian@127.0.0.1 true; }

# Phone storage at /mnt/media (hostfs), once per boot; debian joins its group.
share() {
  [ -n "$VM_SHARE" ] || return 0
  printf '%s\n' 'set -e
mountpoint -q /mnt/media && exit 0
mkdir -p /mnt/media
# libmount uses fsconfig, where the path is the "hostfs" key; old mount(2) takes it bare.
mount -t hostfs none /mnt/media -o hostfs="$1" 2>/dev/null ||
  LIBMOUNT_FORCE_MOUNT2=always mount -t hostfs none /mnt/media -o "$1"
g=$(stat -c %g /mnt/media)
n=$(getent group "$g" | cut -d: -f1)
[ -n "$n" ] || { n=media; groupadd -g "$g" "$n"; }
usermod -aG "$n" debian' | "$DB" dbclient -y -i "$D/id_dropbear" -p "$(cat "$D/port")" -T root@127.0.0.1 sh -s "$VM_SHARE"
}

# Hostname "reterm" (older disks were made as "reterminal"), before the login
# shell reads it for its prompt.
host() {
  printf '%s\n' 'h=reterm
[ "$(cat /etc/hostname 2>/dev/null)" = "$h" ] && [ "$(hostname)" = "$h" ] && exit 0
echo "$h" > /etc/hostname
sed -i "s/\breterminal\b/$h/g" /etc/hosts
grep -qw "$h" /etc/hosts || echo "127.0.1.1 $h" >> /etc/hosts
hostname "$h"' | "$DB" dbclient -y -i "$D/id_dropbear" -p "$(cat "$D/port")" -T root@127.0.0.1 sh -s
}

# fstrim once per boot (the port changes every boot), in the background.
trim() {
  [ -n "$VM_TRIM" ] || return 0
  p=$(cat "$D/port")
  [ "$(cat "$D/trimmed" 2>/dev/null)" = "$p" ] && return 0
  echo "$p" > "$D/trimmed"
  ( "$DB" dbclient -y -i "$D/id_dropbear" -p "$p" -T root@127.0.0.1 "fstrim -a" </dev/null >/dev/null 2>&1 & )
}

shown=0
console() {
  c=$(wc -l < "$D/console.log" 2>/dev/null)
  [ -n "$c" ] || return
  [ "$c" -lt "$shown" ] && shown=0
  if [ "$c" -gt "$shown" ]; then
    printf '\r\033[K'
    sed -n "$((shown + 1)),${c}p" "$D/console.log"
    shown=$c
  fi
}

last=""
n=0
while :; do
  s=$(cat "$D/status" 2>/dev/null)
  case "$s" in
    error*|stopped)
      console
      printf '\r\033[K%s\n' "$s"
      exit 1 ;;
    booted)
      console
      if probe </dev/null >/dev/null 2>"$D/probe.err"; then
        err=$(share 2>&1) || err="Could not mount phone storage at /mnt/media: $err"
        host >/dev/null 2>&1
        trim
        printf '\033[2J\033[H'
        [ -z "$err" ] || printf '%s\n' "$err"
        break
      fi
      n=$((n + 1))
      if [ $((n % 20)) -eq 0 ]; then
        printf '\r\033[K[ssh not ready, try %s: %s]\n' "$n" "$(tail -n 1 "$D/probe.err")"
      fi ;;
    *)
      [ "$s" = "$last" ] || printf '\r\033[K%s' "$s" ;;
  esac
  last="$s"
  sleep 1
done
exec "$DB" dbclient -y -i "$D/id_dropbear" -p "$(cat "$D/port")" -t debian@127.0.0.1 "$@"
