#!/usr/bin/env bash
# Helper for app translators. See docs/LOCALISATION.md.
#
#   scripts/translations.sh init <qualifier>    create values-<qualifier>/ from the English files
#   scripts/translations.sh status <qualifier>  list missing, extra, and still-English strings
#
# <qualifier> is the Android resource qualifier, e.g. fr, pt-rBR, b+sr+Latn.
set -euo pipefail

usage() {
    sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

[ $# -eq 2 ] || usage
command=$1
qualifier=$2
[[ "$qualifier" =~ ^[A-Za-z][A-Za-z0-9+-]*$ ]] || { echo "Invalid qualifier: $qualifier" >&2; exit 1; }
[ "$qualifier" != "night" ] || { echo "'night' is not a language qualifier" >&2; exit 1; }

cd "$(dirname "$0")/.."
res=app/src/main/res
default_dir=$res/values
target_dir=$res/values-$qualifier
translatable_files=(strings settings_strings milestone_strings streak_splash_strings top_followers_browser_strings)

# Prints the resource names of every translatable <string> and <plurals>
# element in the given files. Non-translatable strings are excluded.
names() {
    grep -h -E '^\s*<(string|plurals) name="' "$@" \
        | grep -v 'translatable="false"' \
        | sed -E 's/.*name="([^"]+)".*/\1/' \
        | sort
}

# Prints name<TAB>text for every translatable <string> and <plurals> element
# (plural items are joined into one line), used to spot entries that still
# contain the English text.
texts() {
    awk '
        function resource_name(line) {
            match(line, /name="[^"]+"/)
            return substr(line, RSTART + 6, RLENGTH - 7)
        }
        in_plural && /<\/plurals>/ { print name "\t" body; in_plural = 0; next }
        in_plural { gsub(/^[[:space:]]+|[[:space:]]+$/, ""); body = body $0; next }
        /^[[:space:]]*<plurals name="/ { name = resource_name($0); body = ""; in_plural = 1; next }
        /^[[:space:]]*<string name="/ && !/translatable="false"/ {
            text = $0
            sub(/^[^>]*>/, "", text)
            sub(/<\/string>[[:space:]]*$/, "", text)
            print resource_name($0) "\t" text
        }
    ' "$@" | sort
}

default_files=()
for f in "${translatable_files[@]}"; do
    default_files+=("$default_dir/$f.xml")
done

case "$command" in
init)
    if [ -e "$target_dir" ]; then
        echo "$target_dir already exists; use 'status' to see what is left to translate." >&2
        exit 1
    fi
    mkdir -p "$target_dir"
    for f in "${translatable_files[@]}"; do
        grep -v 'translatable="false"' "$default_dir/$f.xml" > "$target_dir/$f.xml"
    done
    count=$(names "${default_files[@]}" | wc -l | tr -d ' ')
    echo "Created $target_dir with $count strings to translate."
    echo "Translate the text between the tags, then run: $0 status $qualifier"
    ;;
status)
    [ -d "$target_dir" ] || { echo "$target_dir does not exist; run: $0 init $qualifier" >&2; exit 1; }
    target_files=("$target_dir"/*.xml)
    missing=$(comm -23 <(names "${default_files[@]}") <(names "${target_files[@]}"))
    extra=$(comm -13 <(names "${default_files[@]}") <(names "${target_files[@]}"))
    identical=$(comm -12 <(texts "${default_files[@]}") <(texts "${target_files[@]}") | cut -f1)
    total=$(names "${default_files[@]}" | wc -l | tr -d ' ')
    translated=$(comm -12 <(names "${default_files[@]}") <(names "${target_files[@]}") | wc -l | tr -d ' ')

    echo "$target_dir: $translated of $total strings present"
    if [ -n "$missing" ]; then
        printf '\nMissing (add these to your translation):\n'
        printf '  %s\n' $missing
    fi
    if [ -n "$extra" ]; then
        printf '\nExtra (not in the English files, or marked translatable="false"; delete these):\n'
        printf '  %s\n' $extra
    fi
    if [ -n "$identical" ]; then
        identical_count=$(printf '%s\n' "$identical" | wc -l | tr -d ' ')
        printf '\n%s identical to English (fine for names and symbols, otherwise translate):\n' "$identical_count"
        printf '%s\n' "$identical" | head -n 40 | sed 's/^/  /'
        [ "$identical_count" -le 40 ] || echo "  ... and $((identical_count - 40)) more"
    fi
    [ -z "$missing" ] && [ -z "$extra" ] && printf '\nNo missing or extra strings.\n'
    ;;
*)
    usage
    ;;
esac
