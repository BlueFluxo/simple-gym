#!/usr/bin/env bash
# Pre-push check: the public repo must contain no secrets and no personal data.
#
# This is a backstop, not a guarantee. It catches the obvious (key material, signing
# passwords, cloud tokens, the personal markers you list) but cannot know what a secret
# looks like in general. Keep secrets and personal data out of the repo in the first place.
#
# Scans (1) the tracked files in the working tree and (2) when run as the pre-push hook
# (ref lines on stdin), the lines added by the commits being pushed, so a secret that was
# added and later removed is still caught. Prints file:line or commit <sha> for every hit,
# never the matching text. Exit status: 0 clean, 1 findings, 2 a git command failed
# (fail closed: an error is never treated as "nothing found").
set -u

git_error() { echo "check-before-push: ERROR running $*" >&2; exit 2; }

root=$(git rev-parse --show-toplevel) || git_error "git rev-parse --show-toplevel"
cd "$root" || git_error "cd $root"

# git_run <max-ok-status> <label> <git args...>: run git, leave stdout in $out.
# A status above <max-ok-status> (1 for git grep = "no match") is a real error: exit 2.
out=""
git_run() {
    local max_ok=$1 label=$2 rc
    shift 2
    out=$(git "$@")
    rc=$?
    if [ "$rc" -gt "$max_ok" ]; then
        git_error "${label:-git $*}"
    fi
}

fail=0
# report <heading> <hits>: print the heading and the (sorted, de-duplicated) hits.
report() {
    local hits
    hits=$(printf '%s\n' "$2" | sed '/^$/d' | sort -u)
    if [ -n "$hits" ]; then
        echo "check-before-push: $1" >&2
        echo "$hits" | sed 's/^/  /' >&2
        fail=1
    fi
}

# Secret patterns (ERE). Written so that this file does not match its own patterns.
secret_patterns=(
    '-----BEGIN[ ].*PRIVATE KEY'
    '(store|key)Password[[:space:]]*='
    'AKIA[0-9A-Z]{16}'
    'gh[pousr]_[A-Za-z0-9]{30,}'
)
# Two deliberate exemptions, for the secret patterns only:
#  - a hit in app/build.gradle.kts on a line that only reads the value from a Properties
#    object (the signing config reading the git-ignored keystore.properties);
#  - the implementation plan, which quotes the patterns.
exempt_file="app/build.gradle.kts"
exempt_line_re='^[[:space:]]*(store|key)Password[[:space:]]*=[[:space:]]*[A-Za-z_][A-Za-z0-9_]*\.getProperty\("[A-Za-z]+"\)[[:space:]]*$'
plan_file="docs/plans/2026-10-22-simple-gym.md"

is_exempt() { # $1 = file, $2 = line text
    [ "$1" = "$exempt_file" ] && [[ $2 =~ $exempt_line_re ]]
}

# Personal markers: git-ignored personal/markers.txt, one per line, fixed string, case-insensitive.
markers_file="personal/markers.txt"
markers=()
marker_lines=()
if [ -f "$markers_file" ]; then
    n=0
    while IFS= read -r marker || [ -n "$marker" ]; do
        n=$((n + 1))
        marker=${marker%$'\r'}
        [ -z "$marker" ] && continue
        markers+=("$marker")
        marker_lines+=("$n")
    done < "$markers_file"
elif [ "${SKIP_MARKER_CHECK:-}" = "1" ]; then
    echo "check-before-push: warning: $markers_file missing, personal-marker check skipped (SKIP_MARKER_CHECK=1)" >&2
else
    echo "check-before-push: personal/markers.txt missing — create it (one personal marker per line) or set SKIP_MARKER_CHECK=1 to skip deliberately" >&2
    exit 1
fi

# ---- 1. Tree scan ----

for pattern in "${secret_patterns[@]}"; do
    git_run 1 "" grep -nIE -e "$pattern" -- . ":(exclude)$plan_file"
    hits=""
    while IFS= read -r hit; do
        [ -z "$hit" ] && continue
        file=${hit%%:*}
        rest=${hit#*:}
        if ! is_exempt "$file" "${rest#*:}"; then
            hits+="$file:${rest%%:*}"$'\n'
        fi
    done <<< "$out"
    report "secret pattern /$pattern/ found at:" "$hits"
done

# Paths that must never be committed: mirrors the secrets, local-config, backup and personal sections of .gitignore.
forbidden_path_re='\.(jks|keystore|pem|p12|key)$|\.gymbackup[^/]*$|(^|/)(keystore|signing|secrets|local)\.properties$|(^|/)\.env(\.[^/]*)?$|(^|/)google-services\.json$|^personal/'

git_run 0 "" ls-files
report "forbidden tracked file:" "$(printf '%s\n' "$out" | grep -E "$forbidden_path_re")"

for i in "${!markers[@]}"; do
    git_run 1 "git grep (personal marker)" grep -nIiF -e "${markers[$i]}"
    report "personal marker (entry on line ${marker_lines[$i]} of $markers_file) found at:" \
        "$(printf '%s\n' "$out" | cut -d: -f1,2)"
done

# ---- 2. History scan (pre-push hook: "<local ref> <local sha> <remote ref> <remote sha>" lines on stdin) ----

history_secret_hits=()
history_marker_hits=()
history_path_hits=""

# scan_range <git log revision args...>: record the commits whose added lines hit, and those that added or
# modified a path matching forbidden_path_re (even when a later commit removed it again).
scan_range() {
    git_run 0 "" log --no-color --no-ext-diff --no-renames --diff-filter=AM --name-only --format='@@commit %h' "$@"
    local path_commit="" path_line
    while IFS= read -r path_line; do
        case $path_line in
            '@@commit '*) path_commit=${path_line#@@commit } ;;
            '') ;;
            *)
                if [[ $path_line =~ $forbidden_path_re ]]; then
                    history_path_hits+="commit $path_commit $path_line"$'\n'
                fi
                ;;
        esac
    done <<< "$out"
    git_run 0 "" log -p --no-color --no-ext-diff --no-renames --format='@@commit %h' "$@"
    local line commit="" file="" in_header=0 text lower i
    while IFS= read -r line; do
        case $line in
            '@@commit '*) commit=${line#@@commit }; file=""; in_header=0; continue ;;
            'diff --git '*) in_header=1; file=""; continue ;;
            '@@ '*) in_header=0; continue ;;
        esac
        if [ "$in_header" -eq 1 ]; then
            # Only inside a file header is "+++ " a file name; in a hunk it is an added line.
            if [[ $line == '+++ '* ]]; then
                file=${line#'+++ '}
                file=${file%%$'\t'*}
                file=${file#b/}
            fi
            continue
        fi
        [[ $line == '+'* ]] || continue
        text=${line:1}
        if [ "$file" != "$plan_file" ]; then
            for i in "${!secret_patterns[@]}"; do
                if [[ $text =~ ${secret_patterns[$i]} ]] && ! is_exempt "$file" "$text"; then
                    history_secret_hits[$i]+="commit $commit $file"$'\n'
                fi
            done
        fi
        if [ "${#markers[@]}" -gt 0 ]; then
            lower=${text,,}
            for i in "${!markers[@]}"; do
                if [[ $lower == *"${markers[$i],,}"* ]]; then
                    history_marker_hits[$i]+="commit $commit $file"$'\n'
                fi
            done
        fi
    done <<< "$out"
}

if [ ! -t 0 ]; then
    stdin_data=$(cat)
    while read -r _ local_sha _ remote_sha; do
        [[ ${local_sha:-} =~ ^0*$ ]] && continue # blank line or a delete
        if [[ ${remote_sha:-} =~ ^0*$ ]]; then
            scan_range "$local_sha" --not --remotes=origin # new branch
        else
            scan_range "$remote_sha..$local_sha"
        fi
    done <<< "$stdin_data"
fi

report "forbidden file added in a pushed commit:" "$history_path_hits"
for i in "${!secret_patterns[@]}"; do
    report "secret pattern /${secret_patterns[$i]}/ found in added lines of:" "${history_secret_hits[$i]:-}"
done
for i in "${!markers[@]}"; do
    report "personal marker (entry on line ${marker_lines[$i]} of $markers_file) found in added lines of:" \
        "${history_marker_hits[$i]:-}"
done

if [ "$fail" -ne 0 ]; then
    echo "check-before-push: FAILED, push blocked" >&2
    exit 1
fi
echo "check-before-push: OK"
