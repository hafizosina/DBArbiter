#!/usr/bin/env bash
# Builds a commit (default: main) and installs it as the desktop app:
#   ~/App/DBArbiter/DBArbiter       launcher (also in the start menu)
#   ~/App/DBArbiter/dbarbiter.jar   the app
#   ~/App/DBArbiter/jre/            bundled Java runtime (made once with jlink)
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
(cd "$BUILD" && mvn -B -q package -Drevision="$VERSION" -Dbuild.commit="$SHORT" -Dbuild.date="$(date '+%F %H:%M')")

mkdir -p "$DEST"
# once, from the old name: the saved scripts, and their connections in ~/.dbadmin/scripts.json (kept by path)
OLD="$HOME/App/DBAdmin"
if [ -d "$OLD/Script" ] && [ ! -e "$DEST/Script" ]; then
    mv "$OLD/Script" "$DEST/Script"
    BINDINGS="$HOME/.dbadmin/scripts.json"
    if [ -f "$BINDINGS" ]; then sed -i "s#\"$OLD/Script/#\"$DEST/Script/#g" "$BINDINGS"; fi
    echo "moved the scripts from $OLD/Script to $DEST/Script"
fi
if [ ! -x "$DEST/jre/bin/java" ]; then
    echo "Creating the Java runtime..."
    jlink --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.localedata \
        --strip-debug --no-header-files --no-man-pages --compress=2 --output "$DEST/jre"
fi

# copy then rename: an app that is still running keeps reading the old jar
cp "$BUILD/target/dbarbiter.jar" "$DEST/dbarbiter.jar.new"
mv -f "$DEST/dbarbiter.jar.new" "$DEST/dbarbiter.jar"
cp "$BUILD/src/main/resources/com/dbadmin/app-icon/icon-256.png" "$DEST/dbarbiter.png"

cat > "$DEST/DBArbiter" <<'EOF'
#!/usr/bin/env bash
# Starts DBArbiter with its bundled Java runtime. Scripts are saved in ./Script next to dbarbiter.jar.
DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
exec "$DIR/jre/bin/java" -jar "$DIR/dbarbiter.jar" "$@"
EOF
chmod +x "$DEST/DBArbiter"

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
