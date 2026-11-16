#!/usr/bin/env bash
# BuggyWuggy — open source, self-hosted issue tracker
# Copyright (C) 2026 The BuggyWuggy contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Sets the version of BuggyWuggy (x.y.z) in all pom.xml files and in web/package.json (and its
# lock file), so the SBOMs of the build carry it too:
#   scripts/set-version.sh 0.2.0
# Use it for a new major or minor version (a database change is a reason for a new minor
# version). The patch version (z) goes up by itself with every commit (.githooks/pre-commit);
# a version set with this script is committed as it is.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
POMS=(pom.xml web/pom.xml server/pom.xml)
new=${1:?usage: scripts/set-version.sh x.y.z}
if [[ ! $new =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
    echo "error: '$new' is not a version x.y.z (e.g. 0.2.0)" >&2
    exit 1
fi

# The version of the project is the one right after <artifactId>buggywuggy-parent</artifactId>:
# the project's own in the root pom, the parent's in the modules.
version_of() {
    sed -n '/<artifactId>buggywuggy-parent<\/artifactId>/{n;s#.*<version>\([^<]*\)</version>.*#\1#p;q;}' "$1"
}

for pom in "${POMS[@]}"; do
    sed -i '/<artifactId>buggywuggy-parent<\/artifactId>/{n;s#<version>[^<]*</version>#<version>'"$new"'</version>#;}' "$pom"
    if [[ $(version_of "$pom") != "$new" ]]; then
        echo "error: could not set the version in $pom" >&2
        exit 1
    fi
done

# The web UI's own version: the top-level "version" of package.json, and in package-lock.json
# the top-level one and that of the root package (the first in "packages").
sed -i 's/^  "version": "[^"]*"/  "version": "'"$new"'"/' web/package.json
sed -i -e 's/^  "version": "[^"]*"/  "version": "'"$new"'"/' \
       -e '0,/^      "version": "[^"]*"/s//      "version": "'"$new"'"/' web/package-lock.json
for file in web/package.json web/package-lock.json; do
    if [[ $(grep -c '"version": "'"$new"'"' "$file") -lt 1 ]]; then
        echo "error: could not set the version in $file" >&2
        exit 1
    fi
done
echo "Version set to $new"
