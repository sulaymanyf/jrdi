#!/usr/bin/env bash
# Apply the Apache 2.0 SPDX header to all .java source files in the
# jrdi reactor. Idempotent: re-running on an already-headed file is
# a no-op.
#
# Header:
#   /*
#    * Copyright 2026 sulaymanyf
#    *
#    * Licensed under the Apache License, Version 2.0 (the "License");
#    * ...
#    */
#
# Usage:  scripts/add-spdx.sh
# Dry run: scripts/add-spdx.sh --check   (CI mode; exit 1 if any file
#                                       is missing the header)

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

# The header as it should appear at the top of every .java file.
# Trailing blank line after `*/` so the package declaration is
# separated by a blank line (Java style).
read -r -d '' HEADER <<'EOF' || true
/*
 * Copyright 2026 sulaymanyf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

EOF

missing=0
total=0
# Iterate every .java file under the reactor, excluding target/.
while IFS= read -r f; do
    total=$((total + 1))
    # Already has the header? Detect via the unique "Copyright 2026 sulaymanyf"
    # marker line — cheaper than the full SPDX-License-Identifier regex.
    if head -10 "$f" | grep -qF "Copyright 2026 sulaymanyf"; then
        continue
    fi
    if [ "$CHECK_ONLY" = 1 ]; then
        echo "  MISSING: $f"
        missing=$((missing + 1))
    else
        # Prepend header to the file in-place. Use a tmp file to avoid
        # race conditions with concurrently-read tools.
        tmp="$(mktemp)"
        printf '%s' "$HEADER" > "$tmp"
        cat "$f" >> "$tmp"
        mv "$tmp" "$f"
    fi
done < <(find "$ROOT" -name "*.java" -not -path "*/target/*" -not -path "*/.git/*")

if [ "$CHECK_ONLY" = 1 ]; then
    if [ "$missing" -gt 0 ]; then
        echo
        echo "FAIL: $missing / $total .java files are missing the SPDX header."
        echo "  Run scripts/add-spdx.sh (without --check) to add them."
        exit 1
    fi
    echo "OK: all $total .java files have the SPDX header."
    exit 0
fi

echo "Added SPDX header to $((total - $(grep -lF "Copyright 2026 sulaymanyf" $(find "$ROOT" -name "*.java" -not -path "*/target/*") | wc -l))) / $total .java files."
