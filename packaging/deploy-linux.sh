#!/usr/bin/env bash
# Builds a commit (default: main) and installs it as the desktop app:
#   ~/App/DBAdmin/DBAdmin       launcher (also in the start menu)
#   ~/App/DBAdmin/dbadmin.jar   the app
#   ~/App/DBAdmin/jre/          bundled Java runtime (made once with jlink)
#   ~/App/DBAdmin/Script/       scripts saved by the app (kept)
# Builds from a clean export of the commit, so uncommitted changes are never deployed.
# Tests run as part of the build; if they fail, the installed app is left as it was.
# Run by .githooks/reference-transaction whenever main moves; also fine to run by hand.
set -euo pipefail
cd "$(dirname "$0")/.."

COMMIT=$(git rev-parse --verify "${1:-main}^{commit}")
DEST="${DBADMIN_HOME:-$HOME/App/DBAdmin}"
BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT

echo "$(date '+%F %T') deploying $(git log -1 --format='%h %s' "$COMMIT")"
git archive "$COMMIT" | tar -x -C "$BUILD"
(cd "$BUILD" && mvn -B -q package -Dbuild.commit="$(git rev-parse --short "$COMMIT")" -Dbuild.date="$(date '+%F %H:%M')")

mkdir -p "$DEST"
if [ ! -x "$DEST/jre/bin/java" ]; then
    echo "Creating the Java runtime..."
    jlink --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.localedata \
        --strip-debug --no-header-files --no-man-pages --compress=2 --output "$DEST/jre"
fi

# copy then rename: an app that is still running keeps reading the old jar
cp "$BUILD/target/dbadmin.jar" "$DEST/dbadmin.jar.new"
mv -f "$DEST/dbadmin.jar.new" "$DEST/dbadmin.jar"
cp "$BUILD/src/main/resources/com/dbadmin/app-icon/icon-256.png" "$DEST/dbadmin.png"

cat > "$DEST/DBAdmin" <<'EOF'
#!/usr/bin/env bash
# Starts DBAdmin with its bundled Java runtime. Scripts are saved in ./Script next to dbadmin.jar.
DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
exec "$DIR/jre/bin/java" -jar "$DIR/dbadmin.jar" "$@"
EOF
chmod +x "$DEST/DBAdmin"

APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$APPS"
cat > "$APPS/dbadmin.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=DBAdmin
GenericName=Database Admin
Comment=Database browser and SQL editor (PostgreSQL, MySQL, SQLite)
Exec=$DEST/DBAdmin %F
Icon=$DEST/dbadmin.png
Terminal=false
Categories=Development;Database;
MimeType=application/sql;text/x-sql;
StartupWMClass=com.dbadmin.Launcher
EOF
update-desktop-database "$APPS" 2>/dev/null || true

git rev-parse --short "$COMMIT" > "$DEST/VERSION"
echo "$(date '+%F %T') installed $(cat "$DEST/VERSION") in $DEST"
