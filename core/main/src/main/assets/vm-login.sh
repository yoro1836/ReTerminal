#!/system/bin/sh
# A Debian VM tab: wait for the VM (DebianVm.kt writes $VM_DIR/status), then SSH in.
# While it boots, the app shows the VM state and boot log over the tab.
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

# fstrim once per boot (the port changes every boot), in the background.
trim() {
  [ -n "$VM_TRIM" ] || return 0
  p=$(cat "$D/port")
  [ "$(cat "$D/trimmed" 2>/dev/null)" = "$p" ] && return 0
  echo "$p" > "$D/trimmed"
  ( "$DB" dbclient -y -i "$D/id_dropbear" -p "$p" -T root@127.0.0.1 "fstrim -a" </dev/null >/dev/null 2>&1 & )
}

# Wait for the VM (the app shows its state and boot log over the tab, so
# nothing is printed here before the login), log in, and when the
# VM goes away (stop, restart, crash) wait for it again. Exiting the login
# shell while the VM keeps running ends the tab.
wait_ready() {
  while :; do
    s=$(cat "$D/status" 2>/dev/null)
    case "$s" in
      ready)
        if probe </dev/null >/dev/null 2>"$D/probe.err"; then
          err=$(share 2>&1) || err="Could not mount phone storage at /mnt/media: $err"
          trim
          printf '\033c'  # full reset, in case anything was printed
          [ -z "$err" ] || printf '%s\n' "$err"
          return
        fi
        ;;
    esac
    sleep 1
  done
}

while :; do
  wait_ready
  # Throw away anything typed (or answered by the terminal) while waiting.
  old=$(stty -g 2>/dev/null) && stty -icanon min 0 time 0 2>/dev/null && cat >/dev/null 2>&1
  [ -n "$old" ] && stty "$old" 2>/dev/null
  "$DB" dbclient -y -i "$D/id_dropbear" -p "$(cat "$D/port")" -t debian@127.0.0.1 "$@"
  rc=$?
  [ "$(cat "$D/status" 2>/dev/null)" = ready ] && exit $rc
done
