#!/usr/bin/env bash
# Self-test for scripts/check-before-push.sh. Run from anywhere inside the repo:
#   bash scripts/test-check-before-push.sh
# Every case runs in a throwaway clone in a temp dir; the real repo is never modified.
# Planted strings are built by concatenation so that this file does not match the
# patterns it tests.
set -u
repo=$(git rev-parse --show-toplevel) || exit 2
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.invalid
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.invalid
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE

planted_password="store""Password = \"hunter2\""
exempt_shape="store""Password = props.getProperty(\"storePassword\")"
planted_aws="AK""IA""ABCDEFGHIJKLMNOP"
test_marker="zz-test-marker""-qq"
zeros=0000000000000000000000000000000000000000

pass_count=0
fail_count=0
clone="$tmp/clone"
out="$tmp/out.txt"

# fresh: new clone with the working-tree scripts and a test-only markers file.
fresh() {
    rm -rf "$clone"
    git clone -q "$repo" "$clone"
    git -C "$clone" config core.autocrlf false
    mkdir -p "$clone/scripts" "$clone/personal"
    cp "$repo/scripts/check-before-push.sh" "$repo/scripts/install-hooks.sh" "$clone/scripts/"
    printf '%s\n' "$test_marker" > "$clone/personal/markers.txt"
}

# commit_file <path> <content>: write the file in the clone and commit it.
commit_file() {
    mkdir -p "$(dirname "$clone/$1")"
    printf '%s\n' "$2" > "$clone/$1"
    git -C "$clone" add -f -- "$1" # -f: the planted forbidden paths are git-ignored
    git -C "$clone" commit -q -m "add $1"
}

# check [stdin-text]: run the check in the clone; output goes to $out.
# Without an argument stdin is empty (like a hand run). Returns the exit status.
check() {
    if [ $# -gt 0 ]; then
        (cd "$clone" && printf '%s\n' "$1" | bash scripts/check-before-push.sh) > "$out" 2>&1
    else
        (cd "$clone" && bash scripts/check-before-push.sh < /dev/null) > "$out" 2>&1
    fi
}

# hook_line <old> <new>: a pre-push stdin line for refs/heads/test.
hook_line() { echo "refs/heads/test $2 refs/heads/test $1"; }

head_sha() { git -C "$clone" rev-parse HEAD; }

result() { # $1 = case name, $2 = 0 for pass
    if [ "$2" -eq 0 ]; then
        echo "PASS $1"
        pass_count=$((pass_count + 1))
    else
        echo "FAIL $1"
        sed 's/^/     | /' "$out"
        fail_count=$((fail_count + 1))
    fi
}

# expect_rc <name> <rc> <wanted>
expect_rc() {
    if [ "$2" -eq "$3" ]; then result "$1" 0; else
        echo "     (exit $2, wanted $3)"
        result "$1" 1
    fi
}

# expect_out <name> <grep -E pattern> / expect_no_out
expect_out() { grep -Eq -- "$2" "$out"; result "$1" $?; }
expect_no_out() { ! grep -Fq -- "$2" "$out"; result "$1" $?; }

# ---- (a) clean tree ----
fresh
check; expect_rc "a: clean tree exits 0" $? 0

# ---- (b) planted string in a tracked file ----
fresh
base=$(head_sha)
commit_file notes.txt "$planted_password"
tip=$(head_sha)
check; expect_rc "b: planted password in tree exits 1" $? 1
expect_no_out "b: tree output does not echo the secret" "hunter2"
check "$(hook_line "$base" "$tip")"; expect_rc "b: planted password in pushed range exits 1" $? 1
expect_out "b: history hit names the commit" "commit [0-9a-f]{7,}"
expect_no_out "b: history output does not echo the secret" "hunter2"

# the signing-config exemption is limited to that file and that line shape
fresh
printf '%s\n' "$exempt_shape" >> "$clone/app/build.gradle.kts"
check; expect_rc "b: signing-config line in app/build.gradle.kts is exempt" $? 0
fresh
commit_file other.kts "$exempt_shape"
check; expect_rc "b: exempt-looking line in another file exits 1" $? 1
fresh
printf '%s\n' "$planted_password" >> "$clone/app/build.gradle.kts"
check; expect_rc "b: literal password in app/build.gradle.kts exits 1" $? 1
fresh
printf '%s\n' "store""Password = props.getProperty(\"a\") + \"b\"" >> "$clone/app/build.gradle.kts"
check; expect_rc "b: non-matching getProperty line in app/build.gradle.kts exits 1" $? 1

# the plan file is excluded from the secret scan only
fresh
printf '%s\n' "$planted_password" >> "$clone/docs/plans/2026-10-22-simple-gym.md"
check; expect_rc "b: secret pattern in the plan file is ignored" $? 0
fresh
printf '%s\n' "$test_marker" >> "$clone/docs/plans/2026-10-22-simple-gym.md"
check; expect_rc "b: personal marker in the plan file exits 1" $? 1

# ---- (c) planted marker ----
fresh
base=$(head_sha)
commit_file notes.txt "see $test_marker here"
tip=$(head_sha)
check; expect_rc "c: planted marker in tree exits 1" $? 1
expect_no_out "c: tree output does not echo the marker" "$test_marker"
check "$(hook_line "$base" "$tip")"; expect_rc "c: planted marker in pushed range exits 1" $? 1
expect_out "c: history hit names the commit" "commit [0-9a-f]{7,}"
expect_no_out "c: history output does not echo the marker" "$test_marker"

# ---- (d) secret added then removed ----
fresh
base=$(head_sha)
commit_file leak.txt "key=$planted_aws"
git -C "$clone" rm -q leak.txt
git -C "$clone" commit -q -m "remove leak.txt"
tip=$(head_sha)
check; expect_rc "d: tree-only mode exits 0 once the secret is removed" $? 0
check "$(hook_line "$base" "$tip")"; expect_rc "d: history mode exits 1" $? 1
expect_out "d: history hit names the commit" "commit [0-9a-f]{7,}"
check "$(hook_line "$zeros" "$tip")"; expect_rc "d: new-branch range exits 1" $? 1
check "$(hook_line "$tip" "$zeros")"; expect_rc "d: delete line is skipped" $? 0
check "$(hook_line "$tip" "$tip")"; expect_rc "d: range already on the remote is clean" $? 0

# ---- (d2) forbidden file added then removed ----
fresh
base=$(head_sha)
commit_file release.jks "not really a keystore"
git -C "$clone" rm -q release.jks
git -C "$clone" commit -q -m "remove release.jks"
tip=$(head_sha)
check; expect_rc "d2: tree-only mode exits 0 once the .jks is removed" $? 0
check "$(hook_line "$base" "$tip")"; expect_rc "d2: a .jks added then removed in the pushed range exits 1" $? 1
expect_out "d2: history hit names the commit" "commit [0-9a-f]{7,} release.jks"
check "$(hook_line "$tip" "$tip")"; expect_rc "d2: range already on the remote is clean" $? 0
for forbidden in keystore.properties app/signing.properties secrets.properties local.properties config/server.pem     certs/id.p12 deploy.key .env .env.production app/google-services.json backups/day.gymbackup backups/day.gymbackup.json     personal/profile.json; do
    fresh
    base=$(head_sha)
    commit_file "$forbidden" "x"
    git -C "$clone" rm -q -- "$forbidden"
    git -C "$clone" commit -q -m "remove $forbidden"
    check "$(hook_line "$base" "$(head_sha)")"; expect_rc "d2: $forbidden added then removed exits 1" $? 1
done
fresh
base=$(head_sha)
commit_file docs/keystore-notes.md "just prose"
commit_file environment.txt "just prose"
check "$(hook_line "$base" "$(head_sha)")"; expect_rc "d2: similar-looking ordinary paths are fine" $? 0

# ---- (e) missing markers file ----
fresh
rm "$clone/personal/markers.txt"
check; expect_rc "e: missing markers file exits 1" $? 1
expect_out "e: missing markers message" "personal/markers.txt missing"
SKIP_MARKER_CHECK=1 check; expect_rc "e: SKIP_MARKER_CHECK=1 exits 0" $? 0
expect_out "e: skip prints a warning" "warning"

# ---- (f) git error ----
fresh
(cd "$clone" && GIT_DIR="$tmp/no-such-git-dir" bash scripts/check-before-push.sh < /dev/null) > "$out" 2>&1
expect_rc "f: invalid GIT_DIR exits 2" $? 2
expect_out "f: error message" "check-before-push: ERROR running"
fresh
check "$(hook_line "$(head_sha)" "1234567890123456789012345678901234567890")"
expect_rc "f: unknown pushed commit exits 2" $? 2

# ---- (g) the installed hook feeds its stdin to the check ----
fresh
git init -q --bare "$tmp/sink.git"
git -C "$clone" remote add sink "$tmp/sink.git"
(cd "$clone" && bash scripts/install-hooks.sh) > /dev/null
good=$(head_sha)
commit_file leak.txt "key=$planted_aws"
git -C "$clone" rm -q leak.txt
git -C "$clone" commit -q -m "remove leak.txt"
git -C "$clone" push sink HEAD:refs/heads/test > "$out" 2>&1
expect_rc "g: hook blocks a push whose history holds a secret" $? 1
git -C "$clone" reset -q --hard "$good"
git -C "$clone" push sink HEAD:refs/heads/test > "$out" 2>&1
expect_rc "g: hook lets a clean push through" $? 0

echo
echo "$pass_count passed, $fail_count failed"
[ "$fail_count" -eq 0 ]
