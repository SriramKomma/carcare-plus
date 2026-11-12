#!/usr/bin/env bash
# Runs every scene of docs/DEMO_SCRIPT.md through the menu and saves what the
# program printed to docs/demo-transcripts/<scene>.txt (backup for the live demo,
# and a check for the report screenshots).
#
# Usage (from the project root):  bash scripts/capture-demo.sh
#
# Your real data/search-history.txt is backed up first and put back at the end,
# so the scenes start with an empty history and leave yours unchanged.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=docs/demo-transcripts
HISTORY=data/search-history.txt
BACKUP=$(mktemp)
mkdir -p "$OUT"

had_history=false
if [ -f "$HISTORY" ]; then
    cp "$HISTORY" "$BACKUP"
    had_history=true
fi
restore_history() {
    if $had_history; then cp "$BACKUP" "$HISTORY"; else rm -f "$HISTORY"; fi
    rm -f "$BACKUP"
}
trap restore_history EXIT
rm -f "$HISTORY"

mvn -q compile
mvn -q exec:java -Dexec.args="fixtures" > "$OUT/00-fixtures.txt"
mvn -q exec:java -Dexec.args="parse"    > "$OUT/00-parse.txt"

# scene <file name> <input lines...>   (a final "0" exits the menu)
scene() {
    local name=$1
    shift
    printf '%s\n' "$@" 0 | mvn -q exec:java > "$OUT/$name.txt"
    echo "saved $OUT/$name.txt"
}

scene 01-ev-battery-completion-ranking  1 1 2 'bat?' 1 'V2S 1A1'
scene 02-spell-check-corrected-search   1 2 5 'breke pads' '' y
scene 03-ev-charging-windsor            1 1 10 '' Windsor
scene 04-word-completion                2 bra
scene 05-spell-check-edit-distance      3 battrey
scene 06-word-frequency                 4 1 brake 4 1 ''
scene 07-search-history                 5
scene 08-input-validation               1 2 5 '<script>' brake 'N9B 3P' n9b3p4
