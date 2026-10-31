#!/data/data/com.termux/files/usr/bin/bash
# Jarvis build: aapt2 + javac + d8 + zipalign + apksigner. No Gradle, no desktop binaries.
#   bash build.sh [debug|release]   build + sign APK into build/outputs/
#   bash build.sh test              compile & run JVM unit tests (pure-Java core)
#   bash build.sh clean             remove build/ (keeps .cache, sources, keys)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CACHE="$ROOT/.cache"; BUILD="$ROOT/build"; APP="$ROOT/app"
# shellcheck disable=SC1091
. "$ROOT/version.properties"
ANDROID_JAR="$CACHE/android-$COMPILE_SDK/android.jar"
LAMBDA_STUBS="$CACHE/stubs/core-lambda-stubs.jar"
KEYSTORE="${JARVIS_KEYSTORE:-$HOME/.jarvis-keys/jarvis-release.p12}"
KEYPASS="${JARVIS_KEYPASS_FILE:-$HOME/.jarvis-keys/keystore.pass}"
CMD="${1:-debug}"; PERSONAL=0
log() { printf '\033[1;35m[build]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[build] %s\033[0m\n' "$*" >&2; exit 1; }

need() { [ -f "$1" ] || die "missing $1 (run: bash tools/setup.sh)"; }

classpath_libs() { # jars bundled into the app (dex-merged)
  { ls "$APP"/libs/*.jar 2>/dev/null || true; ls "$CACHE"/appjars/*.jar 2>/dev/null || true; } | tr '\n' ':'
}

case "$CMD" in
  clean) rm -rf "$BUILD"; log "cleaned build/"; exit 0 ;;
  test) exec bash "$ROOT/tools/run-tests.sh" ;;
  debug|release) FLAVOR="$CMD" ;;
  personal) FLAVOR="release"; PERSONAL=1 ;;
  *) die "usage: build.sh [debug|release|test|clean]" ;;
esac

need "$ANDROID_JAR"; need "$LAMBDA_STUBS"
[ -f "$KEYSTORE" ] || die "signing key missing: $KEYSTORE"
T0=$(date +%s)
# per-invocation work dir: several agents/tools may build at the same time
OUT="$BUILD/work-$FLAVOR-$$"; rm -rf "$OUT"; mkdir -p "$OUT"/{gen,classes,dex,res}
trap '[ -n "${JV_KEEP:-}" ] || rm -rf "$OUT"' EXIT

# 1. BuildConfig + manifest -------------------------------------------------------------------
# JV_APP_ID=app.jarvis.devN builds a side-by-side variant (own data, own port, own launcher entry "Jarvis [devN]"):
# several agents can then test on the same phone without overwriting each other. Code/R package stays app.jarvis.
RT_ID="${JV_APP_ID:-$APPLICATION_ID}"
GIT_REV=$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo nogit)
[ -n "$(git -C "$ROOT" status --porcelain 2>/dev/null)" ] && GIT_REV="$GIT_REV-dirty"
DEBUG_BOOL=$([ "$FLAVOR" = debug ] && echo true || echo false)
mkdir -p "$OUT/gen/${APPLICATION_ID//.//}"
cat > "$OUT/gen/${APPLICATION_ID//.//}/BuildConfig.java" <<JAVA
package $APPLICATION_ID;
public final class BuildConfig {
    private BuildConfig() {}
    public static final boolean DEBUG = $DEBUG_BOOL;
    public static final String APPLICATION_ID = "$RT_ID";
    public static final String VERSION_NAME = "$VERSION_NAME";
    public static final int VERSION_CODE = $VERSION_CODE;
    public static final String GIT_REV = "$GIT_REV";
    public static final long BUILD_TIME = ${T0}000L;
}
JAVA
SNIPPET="$APP/src/$FLAVOR/manifest-components.xml"
python3 - "$APP/AndroidManifest.xml.in" "$OUT/AndroidManifest.xml" "$APPLICATION_ID" "$SNIPPET" "$FLAVOR" "${JV_APP_ID:-}" <<'PY'
import sys, os
src, dst, appid, snip, flavor, rt = sys.argv[1:7]
t = open(src, encoding="utf-8").read().replace("@APPLICATION_ID@", appid)
t = t.replace("@DEBUG_COMPONENTS@", open(snip, encoding="utf-8").read() if os.path.exists(snip) else "")
if flavor == "debug":   # debug builds are debuggable (run-as, profiling); release/personal never
    t = t.replace("<application", "<application\n        android:debuggable=\"true\"", 1)
if rt and rt != appid:  # side-by-side dev variant: distinct label so the launcher entries can be told apart
    suffix = rt.rsplit(".", 1)[-1]
    t = t.replace('android:label="@string/app_name"', 'android:label="Jarvis [%s]"' % suffix, 1)
open(dst, "w", encoding="utf-8").write(t)
PY

# 2. resources --------------------------------------------------------------------------------
log "aapt2 compile/link"
aapt2 compile --dir "$APP/res" -o "$OUT/res/res.zip"
# assets: app/assets (+ the owner's private provisioned.json for the "personal" flavor, which never lives in the repo)
mkdir -p "$OUT/assets"; [ -d "$APP/assets" ] && cp -r "$APP/assets/." "$OUT/assets/"
if [ "$PERSONAL" = 1 ]; then
  PROV="${JARVIS_PROVISIONED:-$HOME/.jarvis-keys/provisioned.json}"
  [ -f "$PROV" ] || die "personal build needs $PROV (run tools/make-provisioned.py)"
  cp "$PROV" "$OUT/assets/provisioned.json"
fi
ASSET_ARGS=(-A "$OUT/assets")
# aapt2 2.20 under PRoot fails to create an ABSOLUTE --java dir ("failed to create directory"); a relative one works
aapt2 link -I "$ANDROID_JAR" --manifest "$OUT/AndroidManifest.xml" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  ${JV_APP_ID:+--rename-manifest-package "$JV_APP_ID"} \
  --java "${OUT#"$ROOT"/}/gen" --auto-add-overlay "${ASSET_ARGS[@]}" \
  -o "$OUT/base.apk" "$OUT/res/res.zip"

# 3. java -> classes --------------------------------------------------------------------------
log "javac"
# JV_EXCLUDE: optional egrep of paths to leave out (lets one worker build while another has a half-written file)
{ find "$APP/src/main/java" "$APP/src/$FLAVOR/java" ${JV_EXTRA_SRC:-} "$OUT/gen" -name '*.java' 2>/dev/null || true; } | { grep -vE "${JV_EXCLUDE:-^$}" || true; } > "$OUT/sources.txt"
NSRC=$(wc -l < "$OUT/sources.txt")
LIBS="$(classpath_libs)"
if ! javac -source 8 -target 8 -Xlint:-options -Xlint:deprecation -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR:$LAMBDA_STUBS" ${LIBS:+-classpath "$LIBS"} \
  -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  cat "$OUT/javac.log"; die "javac failed"
fi
grep -E 'warning:|error:' "$OUT/javac.log" | head -20 || true

# 4. dex --------------------------------------------------------------------------------------
log "d8 ($NSRC sources)"
D8_MODE=$([ "$FLAVOR" = debug ] && echo --debug || echo --release)
JARS_ARR=(); IFS=: read -ra JARS_ARR <<< "$LIBS"
d8 "$D8_MODE" --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class') "${JARS_ARR[@]}" 2>&1 | grep -vE '^Warning: (Missing class|Type `)' || true
[ -f "$OUT/dex/classes.dex" ] || die "d8 produced no classes.dex"

# 5. assemble, align, sign --------------------------------------------------------------------
NATIVE_DIR="${JV_NATIVE_DIR:-$CACHE/native}"; LIB_ARGS=(); [ -d "$NATIVE_DIR" ] && LIB_ARGS=(--lib "$NATIVE_DIR")   # JV_NATIVE_DIR: x86_64 libs for the CI emulator build
python3 "$ROOT/tools/mkapk.py" "$OUT/base.apk" "$OUT/unsigned.apk" --dex "$OUT/dex" "${LIB_ARGS[@]}"
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
mkdir -p "$BUILD/outputs"
SUFFIX=$FLAVOR; [ "$PERSONAL" = 1 ] && SUFFIX=personal
FINAL="$BUILD/outputs/jarvis-$VERSION_NAME-$SUFFIX.apk"
FINAL_TMP="$OUT/final.apk"
JV_KS_PASS="$(cat "$KEYPASS")" apksigner sign --ks "$KEYSTORE" --ks-pass env:JV_KS_PASS --key-pass env:JV_KS_PASS \
  --ks-key-alias jarvis --min-sdk-version "$MIN_SDK" --out "$FINAL_TMP" "$OUT/aligned.apk"
apksigner verify --min-sdk-version "$MIN_SDK" "$FINAL_TMP" >/dev/null || die "signature verification failed"
mv -f "$FINAL_TMP" "$FINAL"
rm -f "$FINAL.idsig"
[ -n "${JV_APK_OUT:-}" ] && { mkdir -p "$(dirname "$JV_APK_OUT")"; cp "$FINAL" "$JV_APK_OUT"; }
SIZE=$(du -h "$FINAL" | cut -f1)
log "OK $FINAL ($SIZE, $(( $(date +%s) - T0 )) s, rev $GIT_REV)"
