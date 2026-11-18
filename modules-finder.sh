#!/bin/sh
# Prints each module from modules.load.vendor_boot that exists in the built modules dir ($1).
while read -r m; do
	[ -n "$(find "$1" -type f -name "$m" -print -quit)" ] && echo "$m"
done < "$(dirname "$0")/modules.load.vendor_boot"
exit 0
