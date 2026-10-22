#!/usr/bin/env bash
# Builds a Linux x64 version of DBArbiter to share (installs nothing; for this machine see deploy-linux.sh):
#   target/linux/DBArbiter/DBArbiter + dbarbiter.jar + jre/   (extract and run ./DBArbiter; no Java install needed)
#   target/linux/DBArbiter-linux-x64.tar.gz
# The Java runtime is made with jlink from the JDK running this script. Extra arguments go to Maven,
# e.g. -Drevision=0.1.12 -Dbuild.commit=abc1234 -DskipTests. The license generator is not included.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=target/linux/DBArbiter

# no "clean": target/Script holds scripts saved by the jar version of the app
rm -rf target/linux
mvn -B -q package "$@"

mkdir -p "$OUT"
cp target/dbarbiter.jar "$OUT/dbarbiter.jar"
cp src/main/resources/com/dbadmin/app-icon/icon-256.png "$OUT/dbarbiter.png"
jlink --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.localedata \
    --strip-debug --no-header-files --no-man-pages --compress=2 --output "$OUT/jre"

cat > "$OUT/DBArbiter" <<'EOF'
#!/usr/bin/env bash
# Starts DBArbiter with its bundled Java runtime. Scripts are saved in ./Script next to dbarbiter.jar.
DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
exec "$DIR/jre/bin/java" -jar "$DIR/dbarbiter.jar" "$@"
EOF
chmod +x "$OUT/DBArbiter"

tar -C target/linux -czf target/linux/DBArbiter-linux-x64.tar.gz DBArbiter
echo "Built $(pwd)/target/linux/DBArbiter-linux-x64.tar.gz ($(du -h target/linux/DBArbiter-linux-x64.tar.gz | cut -f1))"
