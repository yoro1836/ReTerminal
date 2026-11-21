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

# fstrim once per boot (the port changes every boot), in the background.
trim() {
  [ -n "$VM_TRIM" ] || return 0
  p=$(cat "$D/port")
  [ "$(cat "$D/trimmed" 2>/dev/null)" = "$p" ] && return 0
  echo "$p" > "$D/trimmed"
  ( "$DB" dbclient -y -i "$D/id_dropbear" -p "$p" -T root@127.0.0.1 "fstrim -a" </dev/null >/dev/null 2>&1 & )
}

E=$(printf '\033'); BEL=$(printf '\007')
shown=0
console() {
  c=$(wc -l < "$D/console.log" 2>/dev/null)
  [ -n "$c" ] || return
  [ "$c" -lt "$shown" ] && shown=0
  if [ "$c" -gt "$shown" ]; then
    printf '\r\033[K'
    # Drop the guest's terminal queries (DSR, DA, OSC ...;?): the terminal would
    # answer them into this tab's input, and the login shell would get them.
    sed -n "$((shown + 1)),${c}p" "$D/console.log" |
      sed -E -e "s/${E}\[[0-9;>?=]*[cnt]//g" -e "s/${E}\][0-9]+;\?[^${E}${BEL}]*(${BEL}|${E}\\\\)//g"
    shown=$c
  fi
}

# Wait for the VM (the app shows its state over the tab), log in, and when the
# VM goes away (stop, restart, crash) wait for it again. Exiting the login
# shell while the VM keeps running ends the tab.
wait_ready() {
  last=""
  n=0
  while :; do
    s=$(cat "$D/status" 2>/dev/null)
    case "$s" in
      ready)
        console
        if probe </dev/null >/dev/null 2>"$D/probe.err"; then
          err=$(share 2>&1) || err="Could not mount phone storage at /mnt/media: $err"
          trim
          printf '\033[2J\033[H'
          [ -z "$err" ] || printf '%s\n' "$err"
          return
        fi
        n=$((n + 1))
        if [ $((n % 20)) -eq 0 ]; then
          printf '\r\033[K[ssh not ready, try %s: %s]\n' "$n" "$(tail -n 1 "$D/probe.err")"
        fi ;;
      booting|booted)
        console ;;
      *)
        [ "$s" = "$last" ] || printf '\r\033[K%s\n' "$s" ;;
    esac
    last="$s"
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
  shown=0
  printf '\r\n'
done
