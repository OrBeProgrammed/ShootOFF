#!/bin/bash
# ShootOFF launcher for Linux
# Finds and preloads v4l1compat.so automatically

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Find v4l1compat.so
V4L_PATHS=(
    "/usr/lib/libv4l/v4l1compat.so"
    "/usr/lib/x86_64-linux-gnu/libv4l/v4l1compat.so"
    "/usr/lib/aarch64-linux-gnu/libv4l/v4l1compat.so"
    "/usr/lib/i386-linux-gnu/libv4l/v4l1compat.so"
)

V4L_COMPAT=""
for path in "${V4L_PATHS[@]}"; do
    if [ -f "$path" ]; then
        V4L_COMPAT="$path"
        break
    fi
done

if [ -z "$V4L_COMPAT" ]; then
    # Try to find it dynamically
    V4L_COMPAT=$(find /usr/lib -name "v4l1compat.so" 2>/dev/null | head -1)
fi

if [ -n "$V4L_COMPAT" ]; then
    echo "Using v4l1compat: $V4L_COMPAT"
    export LD_PRELOAD="$V4L_COMPAT"
else
    echo "WARNING: v4l1compat.so not found. Camera may not work."
    echo "Install it with: sudo apt install libv4l-0"
fi

cd "$SCRIPT_DIR"
exec java -jar build/dist/ShootOFF.jar "$@"
