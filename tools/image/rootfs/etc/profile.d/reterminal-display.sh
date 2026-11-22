# X11 apps show on the phone through Termux:X11 (display button left of + in
# ReTerminal's top bar). With GPU acceleration on, xbridge serves the phone's X
# server at :0 (zero-copy DRI3); otherwise the app relays the phone's
# 127.0.0.1:6000, which the VM reaches at its gateway address.
if [ -z "$DISPLAY" ]; then
	if [ -e /dev/hostx11 ]; then
		export DISPLAY=:0
	else
		_gw=$(ip route 2>/dev/null | awk '/^default/ {print $3; exit}')
		[ -n "$_gw" ] && export DISPLAY="$_gw:0"
		unset _gw
	fi
fi
# Fences in host memory so the phone's X server can see them (DRI3).
[ -e /dev/hostshm ] && export LD_LIBRARY_PATH=/usr/lib/xclipse/shim${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
