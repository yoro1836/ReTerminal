#!/system/bin/sh
# A Debian VM tab: wait for the VM (DebianVm.kt writes $VM_DIR/status), then SSH in.
D="$VM_DIR"
DB="$NATIVE_LIB_DIR/libdropbear.so"
export HOME="$D"
ssh() { "$DB" dbclient -y -i "$D/id_dropbear" -p "$(cat "$D/port")" "$@" root@127.0.0.1; }

last=""
n=0
while :; do
  s=$(cat "$D/status" 2>/dev/null)
  case "$s" in
    error*|stopped)
      printf '\r\033[K%s\n' "$s"
      tail -n 12 "$D/console.log" 2>/dev/null
      exit 1 ;;
    booted)
      [ "$last" = booted ] || printf '\r\033[KBooting…'
      if ssh -T true </dev/null >/dev/null 2>&1; then
        printf '\r\033[K'
        break
      fi
      n=$((n + 1))
      if [ $n -gt 180 ]; then
        printf '\r\033[KCould not reach the VM. Console:\n'
        tail -n 12 "$D/console.log" 2>/dev/null
        exit 1
      fi ;;
    *)
      [ "$s" = "$last" ] || printf '\r\033[K%s' "$s" ;;
  esac
  last="$s"
  sleep 1
done
exec "$DB" dbclient -y -i "$D/id_dropbear" -p "$(cat "$D/port")" -t root@127.0.0.1 "$@"
