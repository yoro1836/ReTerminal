#!/system/bin/sh
# A Debian VM tab: wait for the VM (DebianVm.kt writes $VM_DIR/status), then SSH in.
# While it boots, the kernel console is shown live.
D="$VM_DIR"
DB="$NATIVE_LIB_DIR/libdropbear.so"
export HOME="$D"
root() { "$DB" dbclient -y -i "$D/id_dropbear" -p "$(cat "$D/port")" -T root@127.0.0.1 "$@"; }
# A probe must not hang: passt may accept before the guest's sshd answers.
probe() { timeout 8 "$DB" dbclient -y -i "$D/id_dropbear" -p "$(cat "$D/port")" -T root@127.0.0.1 true; }

# Once per disk: the login user debian (sudo, no password) and hostname reterminal.
SETUP='set -e
id debian >/dev/null 2>&1 || useradd -m -s /bin/bash -G sudo debian
echo "debian ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/debian
chmod 440 /etc/sudoers.d/debian
install -d -m 700 -o debian -g debian /home/debian/.ssh
install -m 600 -o debian -g debian /root/.ssh/authorized_keys /home/debian/.ssh/authorized_keys
echo reterminal > /etc/hostname
hostname reterminal
sed -i "s/claude-phone/reterminal/g" /etc/hosts
grep -q reterminal /etc/hosts || echo "127.0.1.1 reterminal" >> /etc/hosts'
setup() {
  [ -f "$D/.setup-done" ] && return 0
  printf '%s\n' "$SETUP" | root sh && touch "$D/.setup-done"
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
        setup || { printf 'First-boot setup failed\n'; exit 1; }
        printf '\033[2J\033[H'
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
