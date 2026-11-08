#!/usr/bin/env bash
# Quick build and install script for CleanYourTV launcher
set -euo pipefail
cd "$(dirname "$0")"
export TV_HOST="${TV_HOST:-10.0.0.104}"
export HOME_KEY_SERVICE=1
export RESTORE_KEY_SERVICES=1
./launcher/build-and-install.sh
./tools/theme.sh themes/glass
