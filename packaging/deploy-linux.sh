#!/usr/bin/env bash
# Builds a commit (default: main) and installs it as the desktop app:
#   ~/App/DBArbiter/DBArbiter       launcher (also in the start menu)
#   ~/App/DBArbiter/dbarbiter.jar   the app
#   ~/App/DBArbiter/jre/            bundled Java runtime (installed once)
# The app itself is built by build-linux.sh, the same build as the Linux download; this script installs it.
#   ~/App/DBArbiter/Script/         scripts saved by the app (kept)
#   ~/App/DBArbiter-License/        the license generator (start menu: "DBArbiter License Generator")
# The app was called DBAdmin: its scripts in ~/App/DBAdmin/Script move here once; ~/App/DBAdmin is then left to delete.
# Builds from a clean export of the commit, so uncommitted changes are never deployed.
# Tests run as part of the build; if they fail, the installed app is left as it was.
# Run by .githooks/reference-transaction whenever main moves; also fine to run by hand.
# Version: major.minor from <revision> in pom.xml, patch +1 per deploy of a new commit, recorded as a
# git tag (v0.1.1, v0.1.2, ...). A commit that already has a tag keeps its version. A new <revision>
# minor (e.g. 0.2.0) starts at that version.
set -euo pipefail
cd "$(dirname "$0")/.."

COMMIT=$(git rev-parse --verify "${1:-main}^{commit}")
DEST="${DBARBITER_HOME:-$HOME/App/DBArbiter}"
BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT

echo "$(date '+%F %T') deploying $(git log -1 --format='%h %s' "$COMMIT")"
git archive "$COMMIT" | tar -x -C "$BUILD"
SHORT=$(git rev-parse --short "$COMMIT") # here: the build folder is not a git repository

BASE=$(git show "$COMMIT:pom.xml" | sed -n 's:.*<revision>\(.*\)</revision>.*:\1:p' | head -1)
MINOR=${BASE%.*}
VERSION=$(git tag --points-at "$COMMIT" -l "v$MINOR.*" | sort -V | tail -1 | sed 's/^v//')
NEW_TAG=
if [ -z "$VERSION" ]; then
    LAST=$(git tag -l "v$MINOR.*" | sort -V | tail -1)
    VERSION=${LAST:+$MINOR.$(( ${LAST##*.} + 1 ))}
    VERSION=${VERSION:-$BASE}
    NEW_TAG=1
fi
echo "version $VERSION"
# the same build as the Linux download (jar, launcher, icon, Java runtime); the tests run as part of it
(cd "$BUILD" && packaging/build-linux.sh -Drevision="$VERSION" -Dbuild.commit="$SHORT" -Dbuild.date="$(date '+%F %H:%M')")
OUT="$BUILD/target/linux/DBArbiter"

mkdir -p "$DEST"
# once, from the old name: the saved scripts (the app finds their connections again by their place in Script/)
OLD="$HOME/App/DBAdmin"
if [ -d "$OLD/Script" ] && [ ! -e "$DEST/Script" ]; then
    mv "$OLD/Script" "$DEST/Script"
    echo "moved the scripts from $OLD/Script to $DEST/Script"
fi
# the Java runtime is installed once (a running app keeps using it)
if [ ! -x "$DEST/jre/bin/java" ]; then
    cp -r "$OUT/jre" "$DEST/jre"
fi

# copy then rename: an app that is still running keeps reading the old jar
cp "$OUT/dbarbiter.jar" "$DEST/dbarbiter.jar.new"
mv -f "$DEST/dbarbiter.jar.new" "$DEST/dbarbiter.jar"
cp "$OUT/dbarbiter.png" "$DEST/dbarbiter.png"
cp "$OUT/DBArbiter" "$DEST/DBArbiter"

APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$APPS"
rm -f "$APPS/dbadmin.desktop" "$APPS/dbadmin-license.desktop" # the start-menu entries of the old name
cat > "$APPS/dbarbiter.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=DBArbiter
GenericName=Database Release Tool
Comment=Compare databases and decide what goes to production (PostgreSQL, MySQL, SQLite)
Exec=$DEST/DBArbiter %F
Icon=$DEST/dbarbiter.png
Terminal=false
Categories=Development;Database;
MimeType=application/sql;text/x-sql;
StartupWMClass=com.dbadmin.Launcher
EOF
# the license generator: a separate folder, so ~/App/DBArbiter never contains it
TOOL="$DEST-License"
mkdir -p "$TOOL"
cp "$BUILD/target/dbarbiter-license-tool.jar" "$TOOL/license-tool.jar.new"
mv -f "$TOOL/license-tool.jar.new" "$TOOL/license-tool.jar"
cat > "$TOOL/LicenseGenerator" <<EOF
#!/usr/bin/env bash
# Issues DBArbiter license keys with the signing key in ~/.dbadmin-signing. "--init" creates that key (once).
exec "$DEST/jre/bin/java" -jar "$TOOL/license-tool.jar" "\$@"
EOF
chmod +x "$TOOL/LicenseGenerator"
cat > "$APPS/dbarbiter-license.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=DBArbiter License Generator
Comment=Issue DBArbiter license keys
Exec=$TOOL/LicenseGenerator
Icon=$DEST/dbarbiter.png
Terminal=false
Categories=Development;
StartupWMClass=com.dbadmin.licensetool.LicenseGenerator
EOF
update-desktop-database "$APPS" 2>/dev/null || true

# tagged only once installed: a failed build does not use up a version number
if [ -n "$NEW_TAG" ]; then git tag "v$VERSION" "$COMMIT"; fi
echo "$VERSION $SHORT" > "$DEST/VERSION"
echo "$(date '+%F %T') installed $VERSION ($SHORT) in $DEST"
