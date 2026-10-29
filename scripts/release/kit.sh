#!/usr/bin/env bash
#
# Packs what reproduce.sh built into a kit that signs it on the machine holding the keys, and rehearses it (F9).
# encri's kit.sh (encri D141), for this app's files; what follows says "encri" where it means this app as well.
#
#   scripts/release/kit.sh <build/reproducible/<commit>>
#
# The kit is build/sign-kit/encri-files-<commit>/, and the same directory as one file,
# build/sign-kit/encri-files-<commit>.tar:
#
#   README.txt              what to do with it on the key machine
#   sign.sh                 this checkout's scripts/release/sign.sh, which refuses to run anywhere but in a kit
#   compare-builds.py       and its compare-builds.py
#   expected-certificates   config/release-certificates: the only keys sign.sh keeps a signature by
#   tools/apksigner.jar     build-tools 36.0.0's, held to the SHA-256 below
#   tools/bundletool/       bundletool and what it depends on, as AGP resolves them and gradle/verification-metadata.xml
#                           pins them, with CLASSPATH, their order
#   unsigned/               the build: the unsigned APK and bundle, SHA256SUMS and provenance.txt
#   KIT-SHA256SUMS          every file above
#
# So the machine that signs needs bash, a JDK 17 or later and python3, and no Android SDK, Gradle or network, and
# the keys never come to the machine that builds. The kit is made in a hidden directory and moved into place only
# after the kit's own `sign.sh --rehearse` has signed it with throwaway keys and left every file as it was, so a kit
# that exists has signed once.
#
# Back from the key machine, the signed files are checked here before anything is uploaded, with this machine's copy
# of the kit rather than the one that travelled:
#
#   bash build/sign-kit/encri-files-<commit>/sign.sh --verify <the signed/ that came back>
#
# Exit status: 0 the kit is made and its rehearsal passed; 1 the rehearsal found something, and no kit is kept;
# 2 a refusal.
set -uo pipefail

REFUSED=2
FOUND=1
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(git -C "$HERE" rev-parse --show-toplevel 2> /dev/null) || { echo "REFUSED: not inside a git repository" >&2; exit $REFUSED; }
refuse() { echo "REFUSED: $*" >&2; exit $REFUSED; }

DIR=${1:?usage: kit.sh <build/reproducible/<commit>>}
DIR=$(cd "$DIR" 2> /dev/null && pwd) || refuse "no directory $1"

# The build-tools release compare-builds.py was measured against (encri D116), and the apksigner.jar it ships. A new
# build-tools changes both lines, after compare-builds.py has been measured against it.
BUILD_TOOLS_VERSION=36.0.0
APKSIGNER_SHA256=3716d9311e55d2b0918a2fd9d54ba9e406c5f6abeea700b287f11259bc163dec

# --- what reproduce.sh left -------------------------------------------------------------------------------------
UNSIGNED_APK=
for f in "$DIR"/encri-files-*-unsigned.apk; do
    [ -f "$f" ] || continue
    [ -z "$UNSIGNED_APK" ] || refuse "$DIR holds more than one encri-files-<commit>-unsigned.apk"
    UNSIGNED_APK=$f
done
[ -n "$UNSIGNED_APK" ] || refuse "$DIR holds no encri-files-<commit>-unsigned.apk from reproduce.sh"
NAME=$(basename "$UNSIGNED_APK" -unsigned.apk)
UNSIGNED_AAB=$DIR/$NAME-unsigned.aab
[ -f "$UNSIGNED_AAB" ] || refuse "no $NAME-unsigned.aab beside the APK"
[ -f "$DIR/SHA256SUMS" ] || refuse "no SHA256SUMS in $DIR"
(cd "$DIR" && sha256sum --quiet --check SHA256SUMS) || refuse "the unsigned files no longer match their SHA256SUMS"
grep -q '^the two builds: apk same, bundle same$' "$DIR/provenance.txt" 2> /dev/null \
    || refuse "provenance.txt does not say both builds came out the same; run reproduce.sh again (F9)"
CERTIFICATES=$REPO/config/release-certificates
for key in app-signing upload transparency; do
    [[ $(sed -n "s/^$key=//p" "$CERTIFICATES" 2> /dev/null | tail -n 1) =~ ^[0-9a-f]{64}$ ]] \
        || refuse "$CERTIFICATES names no $key certificate"
done

KITS=$REPO/build/sign-kit
KIT=$KITS/$NAME
{ [ -e "$KIT" ] || [ -e "$KIT.tar" ]; } && refuse "$KIT or its .tar exists; move both aside to make the kit again"
mkdir -p "$KITS" || refuse "cannot make $KITS"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/encri-files-kit.XXXXXX") || refuse "no temporary directory"
STAGE=$(mktemp -d "$KITS/.$NAME.XXXXXX") || refuse "cannot make a directory under $KITS"
trap 'rm -rf -- "$WORK" ${STAGE:+"$STAGE"}' EXIT
mkdir "$STAGE/unsigned" "$STAGE/tools" "$STAGE/tools/bundletool"

# --- the build, the scripts, and what the signatures must be --------------------------------------------------------
cp "$UNSIGNED_APK" "$UNSIGNED_AAB" "$DIR/SHA256SUMS" "$DIR/provenance.txt" "$STAGE/unsigned/" || refuse "could not copy the build"
cp "$HERE/sign.sh" "$HERE/compare-builds.py" "$STAGE/" || refuse "could not copy the scripts"
cp "$CERTIFICATES" "$STAGE/expected-certificates" || refuse "could not copy $CERTIFICATES"

# --- apksigner, from the SDK, held to the jar compare-builds.py was measured with ----------------------------------
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$SDK" ] && [ -f "$REPO/local.properties" ]; then
    SDK=$(sed -n 's/^sdk\.dir=//p' "$REPO/local.properties")
fi
APKSIGNER_JAR=$SDK/build-tools/$BUILD_TOOLS_VERSION/lib/apksigner.jar
[ -f "$APKSIGNER_JAR" ] || refuse "no apksigner.jar $BUILD_TOOLS_VERSION under ${SDK:-<no SDK>}"
[ "$(sha256sum "$APKSIGNER_JAR" | cut -d' ' -f1)" = "$APKSIGNER_SHA256" ] \
    || refuse "$APKSIGNER_JAR is not the apksigner.jar build-tools $BUILD_TOOLS_VERSION shipped"
cp "$APKSIGNER_JAR" "$STAGE/tools/apksigner.jar" || refuse "could not copy apksigner.jar"

# --- bundletool, as AGP resolves it, every jar one gradle/verification-metadata.xml pins --------------------------
# Not --offline: Gradle's cache cleanup drops what has gone unused for 30 days, about the time between releases, and
# an offline run then cannot even configure the build. What it fetches again is held to the metadata like the rest.
(cd "$REPO" && ./gradlew --quiet --init-script "$HERE/bundletool.init.gradle.kts" printBundletoolJars) \
    > "$WORK/jars" 2> "$WORK/gradle.log" || { cat "$WORK/gradle.log" >&2; refuse "Gradle could not list bundletool's jars"; }
while IFS= read -r jar; do
    case "$jar" in /*.jar) ;; *) continue ;; esac
    [ -f "$jar" ] || refuse "Gradle named $jar, which is not there"
    name=$(basename "$jar")
    [ -e "$STAGE/tools/bundletool/$name" ] && refuse "two of bundletool's jars are both called $name"
    grep -q "<sha256 value=\"$(sha256sum "$jar" | cut -d' ' -f1)\"" "$REPO/gradle/verification-metadata.xml" \
        || refuse "$name is not one gradle/verification-metadata.xml pins"
    cp "$jar" "$STAGE/tools/bundletool/" || refuse "could not copy $name"
    echo "$name" >> "$STAGE/tools/bundletool/CLASSPATH"
done < "$WORK/jars"
grep -q '^bundletool-[0-9.]*\.jar$' "$STAGE/tools/bundletool/CLASSPATH" 2> /dev/null \
    || { cat "$WORK/gradle.log" >&2; refuse "could not find bundletool on AGP's classpath"; }
BUNDLETOOL=$(sed -n 's/^bundletool-\([0-9.]*\)\.jar$/\1/p' "$STAGE/tools/bundletool/CLASSPATH")

COMMIT=$(sed -n 's/^commit: //p' "$DIR/provenance.txt")
cat > "$STAGE/README.txt" << EOF
Encri Files $COMMIT, unsigned, with what signs it (F9)

Made $(date -u +%Y-%m-%d) by scripts/release/kit.sh from build/reproducible/$(basename "$DIR"), with bundletool
$BUNDLETOOL and apksigner from build-tools $BUILD_TOOLS_VERSION. Rehearsed there with throwaway keys before it was
kept.

The machine that signs needs bash, a JDK 17 or later (java, jarsigner, keytool) and python3. It needs no Android
SDK, no Gradle and no network. Run sign.sh with bash, as below: a stick formatted FAT drops the execute bit.

1. Check this copy and this machine, with throwaway keys. Nothing is kept:

     bash sign.sh --rehearse

2. Sign, at a terminal. apksigner, bundletool and jarsigner each ask for their key's password:

     bash sign.sh /path/to/the/keys

   The directory holds encri-app-signing.p12, encri-files-upload.p12 and encri-transparency.p12, under the
   aliases app-signing, encri-files-upload and transparency; the app signing and transparency keys are encri's,
   and the top of sign.sh says how to name a keystore by another name, such as ENCRI_UPLOAD_KEYSTORE=... and
   ENCRI_UPLOAD_KEY_ALIAS=... for the upload key. It refuses a key
   whose certificate is not the one expected-certificates names, and writes signed/ here. bundletool's three
   SLF4J lines and jarsigner's warning that the certificate is self-signed are expected, and so, on Java 24 or
   later, are the JVM's WARNING lines about restricted and sun.misc.Unsafe methods (Conscrypt in apksigner,
   protobuf in bundletool).

3. Bring signed/ back, all three files: $NAME.aab goes to Play, $NAME.apk to the GitHub release once
   the trademark search lets the app go beyond testers (encri D136, D142), and SIGNED.txt records both. Where the kit was made, check them before uploading:

     bash build/sign-kit/$NAME/sign.sh --verify <the signed/ that came back>

KIT-SHA256SUMS lists every file here, and sign.sh refuses a copy that does not match it.
EOF

(cd "$STAGE" && find . -type f | sed 's|^\./||' | LC_ALL=C sort | xargs sha256sum) > "$WORK/KIT-SHA256SUMS" \
    && mv "$WORK/KIT-SHA256SUMS" "$STAGE/KIT-SHA256SUMS" || refuse "could not list the kit's SHA-256s"

# --- a kit that exists has signed once --------------------------------------------------------------------------------
echo "== rehearsing the kit with throwaway keys"
bash "$STAGE/sign.sh" --rehearse
s=$?
if [ $s -ne 0 ]; then
    [ $s -eq 1 ] && { echo "FOUND: the kit's rehearsal failed; no kit is kept" >&2; exit $FOUND; }
    refuse "the kit's rehearsal refused; no kit is kept"
fi
(cd "$STAGE" && sha256sum --quiet --check KIT-SHA256SUMS) || refuse "the rehearsal changed a file in the kit"
[ "$(cd "$STAGE" && find . -type f ! -name KIT-SHA256SUMS | wc -l)" -eq "$(wc -l < "$STAGE/KIT-SHA256SUMS")" ] \
    || refuse "the rehearsal left a file in the kit"

chmod 755 "$STAGE"
mv "$STAGE" "$KIT" || refuse "could not move the kit to $KIT"
STAGE=
tar -C "$KITS" -cf "$KIT.tar" "$NAME" || { rm -f -- "$KIT.tar"; refuse "could not write $KIT.tar"; }

echo
echo "== $KIT.tar, $(du -h "$KIT.tar" | cut -f1): carry it to the key machine, and there:"
echo "     tar -xf $NAME.tar && cd $NAME && bash sign.sh --rehearse && bash sign.sh <the directory holding the keys>"
echo "   then bring signed/ back, and before uploading:"
echo "     bash $KIT/sign.sh --verify <the signed/ that came back>"
exit 0
