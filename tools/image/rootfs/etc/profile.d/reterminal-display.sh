# X11 apps show on the phone through Termux:X11 (drawer > Debian VM > display
# button in ReTerminal): the app relays the phone's 127.0.0.1:6000, which the
# VM reaches at its gateway address.
if [ -z "$DISPLAY" ]; then
	_gw=$(ip route 2>/dev/null | awk '/^default/ {print $3; exit}')
	[ -n "$_gw" ] && export DISPLAY="$_gw:0"
	unset _gw
fi
