#!/bin/sh
# Points git at .githooks/ for this clone. Run once per clone:  sh scripts/install-hooks.sh
#
# core.hooksPath rather than copying files into .git/hooks, so a hook added later is picked up without
# anyone re-running this, and so `git config core.hooksPath` shows plainly whether a clone is guarded.
set -e
root=$(git rev-parse --show-toplevel)
git -C "$root" config core.hooksPath .githooks
chmod +x "$root"/.githooks/* 2>/dev/null || true
echo "core.hooksPath = $(git -C "$root" config core.hooksPath)"
echo "Hooks active: $(ls "$root/.githooks" | tr '\n' ' ')"
