#!/usr/bin/env bash
# Builds the standalone packages (docs/standalone.md): a folder per platform with a Java runtime, the application
# jar and a launcher, so that nothing has to be installed.
#   scripts/package-standalone.sh [--version 0.1.0] [--skip-build]
# Output in dist/: DistriCompute-<version>-windows-x64.zip, DistriCompute-<version>-linux-x64.tar.gz, SHA256SUMS.
# The runtime is Eclipse Temurin, pinned below with the SHA-256 checksums Adoptium publishes; a download that does
# not match is rejected. Downloads are cached in dist/cache.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JAVA_VERSION="21.0.13.1+2"
JAVA_FILE_VERSION="21.0.13.1_2"
JAVA_BASE="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-${JAVA_VERSION/+/%2B}"
WINDOWS_JRE="OpenJDK21U-jre_x64_windows_hotspot_${JAVA_FILE_VERSION}.zip"
WINDOWS_JRE_SHA256="e15ee1e54a5a3b38027a09cedfa87bc3348d7dd3ce50866dd3d9671d7fffc6c1"
LINUX_JRE="OpenJDK21U-jre_x64_linux_hotspot_${JAVA_FILE_VERSION}.tar.gz"
LINUX_JRE_SHA256="18f44fab9065217f3ba61d0eef8a7b5f3c3713aa7293001b0d4ac10ede3850f0"

VERSION=""
BUILD=1
while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --skip-build) BUILD=0; shift ;;
    *) echo "Unknown option $1" >&2; exit 2 ;;
  esac
done
if [ -z "$VERSION" ]; then
  VERSION="$(./mvnw -B -q -N help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1)"
fi
case "$VERSION" in
  ''|*[!A-Za-z0-9._-]*) echo "Invalid version '$VERSION'" >&2; exit 2 ;;
esac

if [ "$BUILD" = 1 ]; then
  scripts/build.sh --skip-tests
fi
JAR="$(ls backend/target/districompute-backend-*.jar 2>/dev/null | grep -v -- '-plain' | head -1 || true)"
[ -n "$JAR" ] || { echo "No application jar in backend/target; run scripts/build.sh first" >&2; exit 1; }
if ! unzip -l "$JAR" | grep -c 'BOOT-INF/classes/static/index.html' > /dev/null; then
  echo "$JAR does not contain the interface; build it with scripts/build.sh" >&2
  exit 1
fi

DIST="$ROOT/dist"
CACHE="$DIST/cache"
mkdir -p "$CACHE"

# Downloads a runtime archive into the cache once and checks its SHA-256 every time.
fetch() {
  local file="$1" sha="$2"
  if [ ! -f "$CACHE/$file" ]; then
    echo "== Downloading $file"
    curl --fail --location --silent --show-error --retry 3 -o "$CACHE/$file.part" "$JAVA_BASE/$file"
    mv "$CACHE/$file.part" "$CACHE/$file"
  fi
  echo "$sha  $CACHE/$file" | sha256sum --check --quiet - \
    || { echo "Checksum mismatch for $file; deleted" >&2; rm -f "$CACHE/$file"; exit 1; }
}

# Fills <dir>/README.txt from a template.
readme() {
  sed -e "s/@VERSION@/$VERSION/g" -e "s/@JAVA_VERSION@/$JAVA_VERSION/g" "$1" > "$2"
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
rm -f "$DIST"/DistriCompute-*.zip "$DIST"/DistriCompute-*.tar.gz "$DIST/SHA256SUMS"

echo "== Windows x64"
fetch "$WINDOWS_JRE" "$WINDOWS_JRE_SHA256"
NAME="DistriCompute-$VERSION-windows-x64"
mkdir -p "$WORK/win/$NAME/app"
unzip -q "$CACHE/$WINDOWS_JRE" -d "$WORK/win/jre"
mv "$WORK/win/jre/"*/ "$WORK/win/$NAME/runtime"
cp "$JAR" "$WORK/win/$NAME/app/districompute.jar"
# Windows files with Windows line endings.
sed 's/\r*$/\r/' packaging/windows/DistriCompute.cmd > "$WORK/win/$NAME/DistriCompute.cmd"
readme packaging/windows/README.txt "$WORK/win/README.lf"
sed 's/\r*$/\r/' "$WORK/win/README.lf" > "$WORK/win/$NAME/README.txt"
(cd "$WORK/win" && zip -q -r -X "$DIST/$NAME.zip" "$NAME")

echo "== Linux x64"
fetch "$LINUX_JRE" "$LINUX_JRE_SHA256"
NAME="DistriCompute-$VERSION-linux-x64"
mkdir -p "$WORK/linux/$NAME/app" "$WORK/linux/jre"
tar -xzf "$CACHE/$LINUX_JRE" -C "$WORK/linux/jre"
mv "$WORK/linux/jre/"*/ "$WORK/linux/$NAME/runtime"
cp "$JAR" "$WORK/linux/$NAME/app/districompute.jar"
install -m 0755 packaging/linux/districompute.sh "$WORK/linux/$NAME/districompute.sh"
readme packaging/linux/README.txt "$WORK/linux/$NAME/README.txt"
tar --owner=0 --group=0 --numeric-owner -czf "$DIST/$NAME.tar.gz" -C "$WORK/linux" "$NAME"

(cd "$DIST" && sha256sum DistriCompute-*.zip DistriCompute-*.tar.gz > SHA256SUMS)
echo "== Packages"
ls -l "$DIST"/DistriCompute-* && cat "$DIST/SHA256SUMS"
