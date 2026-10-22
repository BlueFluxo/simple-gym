#!/usr/bin/env bash
# Installs scripts/check-before-push.sh as the repo's pre-push hook.
set -eu
root=$(git rev-parse --show-toplevel)
hook="$root/.git/hooks/pre-push"
mkdir -p "$root/.git/hooks"
cat > "$hook" <<'HOOK'
#!/usr/bin/env bash
# exec keeps git's stdin (the "<local ref> <local sha> <remote ref> <remote sha>" lines)
# connected to the check, which scans the history being pushed.
exec bash "$(git rev-parse --show-toplevel)/scripts/check-before-push.sh" "$@"
HOOK
chmod +x "$hook"
echo "Installed pre-push hook: $hook"
