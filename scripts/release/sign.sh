#!/usr/bin/env bash
#
# Signs the Encri Files release in a kit and holds the signed files to the build: encri D116's steps 3 and 4 (F9).
# encri's sign.sh (encri D140, D141), for this app's files and upload key; what follows says "encri" where it means
# this app as well.
# It runs from inside a kit scripts/release/kit.sh made, on the machine that holds the keys; run from the checkout,
# it refuses.
#
#   bash sign.sh <keys>              sign unsigned/ with the keystores in <keys>; writes signed/ beside this script
#   bash sign.sh --rehearse          every step with throwaway keys, made and destroyed here; keeps nothing
#   bash sign.sh --verify [<dir>]    check signed files, by default signed/, with no key: run where the kit was made
#
# <keys> is the directory holding the keystores under the names they were made with: encri-app-signing.p12 (alias
# app-signing), encri-files-upload.p12 (alias encri-files-upload) and encri-transparency.p12 (alias transparency). Any one can
# be named instead: ENCRI_APP_KEYSTORE and ENCRI_APP_KEY_ALIAS, ENCRI_UPLOAD_KEYSTORE and ENCRI_UPLOAD_KEY_ALIAS,
# ENCRI_TRANSPARENCY_KEYSTORE and ENCRI_TRANSPARENCY_KEY_ALIAS.
#
# Needs bash, a JDK 17 or later and python3 3.6 or later; no Android SDK, no Gradle, no network. apksigner and
# bundletool are in tools/, as jars. JAVA_HOME picks a JDK other than the one on PATH.
#
# Exit status: 0 signed or verified, and every check passed; 1 a check failed, which is a finding, and what this
# run signed is removed; 2 nothing was signed or checked, which is a refusal. The probes and reproduce.sh use the
# same three.
#
# **No password is read from the environment, a file or an argument**: apksigner, bundletool and jarsigner each ask
# on the terminal, so a real signing refuses a stdin or stdout that is not one. bundletool asks through
# System.console(), which a JVM gives only when both are. A rehearsal is the one exception, because its keys are
# made a moment before and deleted a moment after, and its passwords are a file in the same temporary directory.
#
# **What is signed and how.** The APK, which goes to the GitHub release (encri D142), gets schemes v2 and
# v3 from the app signing key and no v1, which minSdk 27 does not need and adds entries (encri D116). The bundle, for Play,
# gets code transparency and then the upload key: adding the transparency file invalidates a signature, so the
# order is fixed, and Play re-signs what it serves with the app signing key it was given.
#
# **The keys are the release's keys.** Each certificate is read back from the signed file, not from the keystore,
# so the record says what was actually signed with, and no extra password is asked for. Each must be the one
# expected-certificates names, so a keystore picked up by mistake fails the run here, before anything reaches
# Play. And no two may coincide: Google's guide says the transparency key is used for nothing else and never the
# app signing key, and encri D115 gives each app an upload key of its own.
#
# **Then the signed files are held to the unsigned ones**: compare-builds.py takes the signing out of each and
# requires every other byte of the APK, and every entry of the bundle, to be what reproduce.sh built.
#
# **What the kit's own check is for.** KIT-SHA256SUMS catches a copy damaged on its way to the key machine. It
# cannot catch one changed on purpose, since whoever changed a file could change the list; --verify, run on the
# signed files back where the kit was made, with the copy of the kit that never travelled, is the check after it.
#
# **A rehearsal leaves nothing behind.** It signs a copy, in a temporary directory, with keys nobody will hold
# again, and removes the signed files with the keys: a signed artifact lying beside the real build could be
# uploaded by mistake, and Play would then hold a first release no key here can update.
#
# Written for bash 3.2, the one macOS has, as well as Linux's; measured on Linux only.
set -uo pipefail

REFUSED=2
FOUND=1
HERE=$(cd "$(dirname "$0")" && pwd)
refuse() { echo "REFUSED: $*" >&2; exit $REFUSED; }
fail() { echo "FOUND: $*" >&2; exit $FOUND; }
USAGE="usage: bash sign.sh <keys> | --rehearse | --verify [<signed dir>]"

MODE=sign
case "${1:-}" in
    --rehearse) MODE=rehearse; shift; [ $# -eq 0 ] || refuse "$USAGE" ;;
    --verify) MODE=verify; shift; [ $# -le 1 ] || refuse "$USAGE" ;;
    -* | "") refuse "$USAGE" ;;
    *) [ $# -eq 1 ] || refuse "$USAGE" ;;
esac

[ -f "$HERE/KIT-SHA256SUMS" ] || refuse "sign.sh runs from inside a kit; make one with scripts/release/kit.sh (F9)"
case "$HERE" in *:*) refuse "the kit's path holds a colon, which a Java class path cannot: $HERE" ;; esac

WORK=$(mktemp -d "${TMPDIR:-/tmp}/encri-files-sign.XXXXXX") || refuse "no temporary directory"
chmod 700 "$WORK"
# OUT is set only once this run has made the directory, so a refusal never removes a signed/ it did not make (encri B138).
OUT=
SIGNED_OK=0
finish() {
    if [ $MODE = sign ] && [ $SIGNED_OK = 0 ] && [ -n "$OUT" ] && [ -d "$OUT" ]; then rm -rf -- "$OUT"; fi
    rm -rf -- "$WORK"
}
trap finish EXIT

if command -v sha256sum > /dev/null 2>&1; then
    SHA256="sha256sum"
elif command -v shasum > /dev/null 2>&1; then
    SHA256="shasum -a 256"
else
    refuse "no sha256sum or shasum"
fi
# Checks a list of SHA-256s from inside the directory its names are relative to, naming any file that fails.
sha256_check() { # dir, list
    (cd "$1" && $SHA256 -c "$2" > "$WORK/sha256.log" 2>&1) || { grep -v ': OK$' "$WORK/sha256.log" >&2; return 1; }
}
norm() { echo "$1" | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]'; }

# --- the kit, whole, and the build in it ---------------------------------------------------------------------------
sha256_check "$HERE" KIT-SHA256SUMS || refuse "the kit does not match its KIT-SHA256SUMS; copy it again from where it was made"
UNSIGNED_APK=
for f in "$HERE"/unsigned/encri-files-*-unsigned.apk; do
    [ -f "$f" ] || continue
    [ -z "$UNSIGNED_APK" ] || refuse "unsigned/ holds more than one encri-files-<commit>-unsigned.apk"
    UNSIGNED_APK=$f
done
[ -n "$UNSIGNED_APK" ] || refuse "unsigned/ holds no encri-files-<commit>-unsigned.apk"
NAME=$(basename "$UNSIGNED_APK" -unsigned.apk)
UNSIGNED_AAB=$HERE/unsigned/$NAME-unsigned.aab
[ -f "$UNSIGNED_AAB" ] || refuse "no $NAME-unsigned.aab beside the APK"
sha256_check "$HERE/unsigned" SHA256SUMS || refuse "the unsigned files no longer match their SHA256SUMS"
grep -q '^the two builds: apk same, bundle same$' "$HERE/unsigned/provenance.txt" 2>/dev/null \
    || refuse "provenance.txt does not say both builds came out the same (F9)"

# --- the tools: a JDK and python3 from the machine, the rest from the kit -----------------------------------------
JAVA_BIN=${JAVA_HOME:+$JAVA_HOME/bin/}
for tool in java jarsigner keytool; do
    command -v "${JAVA_BIN}$tool" > /dev/null 2>&1 \
        || refuse "no $tool${JAVA_HOME:+ in $JAVA_HOME/bin}: sign.sh needs a JDK, not only a Java runtime"
done
# bundletool reads a bundle's resources with aapt2-proto, which is compiled for Java 17.
JAVA_VERSION=$("${JAVA_BIN}java" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.specification\.version = //p')
case "$JAVA_VERSION" in
    1.*) JAVA_MAJOR=${JAVA_VERSION#1.} ;;
    *) JAVA_MAJOR=${JAVA_VERSION%%.*} ;;
esac
case "$JAVA_MAJOR" in "" | *[!0-9]*) refuse "could not tell which Java ${JAVA_BIN}java is" ;; esac
[ "$JAVA_MAJOR" -ge 17 ] || refuse "Java $JAVA_VERSION is too old: bundletool's jars need 17 or later"
command -v python3 > /dev/null 2>&1 || refuse "no python3: compare-builds.py holds the signed files to the build"
python3 -c 'import sys; sys.exit(sys.version_info < (3, 6))' 2> /dev/null || refuse "python3 is older than 3.6"

BUNDLETOOL_CP=
while IFS= read -r jar; do
    [ -n "$jar" ] && BUNDLETOOL_CP=${BUNDLETOOL_CP:+$BUNDLETOOL_CP:}$HERE/tools/bundletool/$jar
done < "$HERE/tools/bundletool/CLASSPATH"
[ -n "$BUNDLETOOL_CP" ] || refuse "tools/bundletool/CLASSPATH names no jar"
bundletool() { "${JAVA_BIN}java" -cp "$BUNDLETOOL_CP" com.android.tools.build.bundletool.BundleToolMain "$@"; }
# The SDK's own apksigner script gives it a 1 GB heap; so does this.
apksigner() { "${JAVA_BIN}java" -Xmx1024M -jar "$HERE/tools/apksigner.jar" "$@"; }
compare_builds() { python3 -I -B "$HERE/compare-builds.py" "$@"; }

# --- the keys, and the certificates they must have -----------------------------------------------------------------
# A rehearsal passes its password file to each tool; a real signing passes nothing, so each tool asks. Expanded as
# ${ARR[@]+"${ARR[@]}"}, because bash before 4.4 calls an empty array unbound under set -u.
APKSIGNER_PASS=()
BUNDLETOOL_PASS=()
JARSIGNER_PASS=()
expected() { norm "$(sed -n "s/^$1=//p" "$HERE/expected-certificates" | tail -n 1)"; }
case $MODE in
    rehearse)
        KEYS=$WORK/keys
        mkdir -m 700 "$KEYS"
        head -c 24 /dev/urandom | base64 > "$KEYS/password"
        for alias in app upload transparency; do
            "${JAVA_BIN}keytool" -genkeypair -noprompt -keystore "$KEYS/$alias.p12" -storetype PKCS12 -alias "$alias" \
                -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=encri-files rehearsal $alias, O=nobody" \
                -storepass:file "$KEYS/password" > "$WORK/keytool-$alias.log" 2>&1 \
                || { cat "$WORK/keytool-$alias.log" >&2; refuse "could not make a throwaway $alias key"; }
        done
        # What a rehearsal expects is its own throwaway certificates, so the comparison a real signing makes runs here too.
        cert_of() {
            norm "$("${JAVA_BIN}keytool" -list -v -keystore "$KEYS/$1.p12" -alias "$1" -storepass:file "$KEYS/password" \
                2> /dev/null | sed -n 's/^[[:space:]]*SHA256: //p' | head -n 1)"
        }
        WANT_APP=$(cert_of app)
        WANT_UPLOAD=$(cert_of upload)
        WANT_TRANSPARENCY=$(cert_of transparency)
        APP_KS=$KEYS/app.p12 APP_ALIAS=app
        UPLOAD_KS=$KEYS/upload.p12 UPLOAD_ALIAS=upload
        TRANSPARENCY_KS=$KEYS/transparency.p12 TRANSPARENCY_ALIAS=transparency
        APKSIGNER_PASS=(--ks-pass "file:$KEYS/password")
        BUNDLETOOL_PASS=("--ks-pass=file:$KEYS/password")
        JARSIGNER_PASS=(-storepass:file "$KEYS/password")
        mkdir "$WORK/signed"
        OUT=$WORK/signed
        ;;
    sign)
        KEYS=$(cd "$1" 2> /dev/null && pwd) || refuse "no directory $1"
        APP_KS=${ENCRI_APP_KEYSTORE:-$KEYS/encri-app-signing.p12}
        APP_ALIAS=${ENCRI_APP_KEY_ALIAS:-app-signing}
        UPLOAD_KS=${ENCRI_UPLOAD_KEYSTORE:-$KEYS/encri-files-upload.p12}
        UPLOAD_ALIAS=${ENCRI_UPLOAD_KEY_ALIAS:-encri-files-upload}
        TRANSPARENCY_KS=${ENCRI_TRANSPARENCY_KEYSTORE:-$KEYS/encri-transparency.p12}
        TRANSPARENCY_ALIAS=${ENCRI_TRANSPARENCY_KEY_ALIAS:-transparency}
        for ks in "$APP_KS" "$UPLOAD_KS" "$TRANSPARENCY_KS"; do [ -f "$ks" ] || refuse "no keystore $ks"; done
        WANT_APP=$(expected app-signing)
        WANT_UPLOAD=$(expected upload)
        WANT_TRANSPARENCY=$(expected transparency)
        [ -t 0 ] && [ -t 1 ] || refuse "a real signing asks for each password on a terminal; run it in one, unpiped"
        [ -e "$HERE/signed" ] && refuse "$HERE/signed exists; a release is signed once, so move it aside if this is meant"
        mkdir "$HERE/signed" || refuse "cannot make $HERE/signed"
        OUT=$HERE/signed
        ;;
    verify)
        SIGNED=$(cd "${1:-$HERE/signed}" 2> /dev/null && pwd) || refuse "no directory ${1:-$HERE/signed}"
        WANT_APP=$(expected app-signing)
        WANT_UPLOAD=$(expected upload)
        WANT_TRANSPARENCY=$(expected transparency)
        ;;
esac
for want in "$WANT_APP" "$WANT_UPLOAD" "$WANT_TRANSPARENCY"; do
    case "$want" in "" | *[!0-9a-f]*) refuse "no certificate to expect for one of the three keys; see expected-certificates" ;; esac
    [ ${#want} -eq 64 ] || refuse "a certificate to expect is not a SHA-256: $want"
done

# --- the checks, the same after a signing as on its own ------------------------------------------------------------
check_signed() { # dir
    APK=$1/$NAME.apk
    AAB=$1/$NAME.aab
    [ -f "$APK" ] && [ -f "$AAB" ] || refuse "$1 does not hold $NAME.apk and $NAME.aab"

    apksigner verify --verbose --print-certs --min-sdk-version 27 "$APK" > "$WORK/apk-verify.txt" 2>&1 \
        || { cat "$WORK/apk-verify.txt" >&2; fail "the signed APK does not verify"; }
    grep -q '^Verified using v1 scheme (JAR signing): false$' "$WORK/apk-verify.txt" || fail "the APK carries a v1 signature"
    grep -q '^Verified using v2 scheme (APK Signature Scheme v2): true$' "$WORK/apk-verify.txt" || fail "the APK has no v2 signature"
    grep -q '^Number of signers: 1$' "$WORK/apk-verify.txt" || fail "the APK has more than one signer"
    a=$(norm "$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$WORK/apk-verify.txt")")
    [ -n "$a" ] || fail "apksigner named no signer"

    "${JAVA_BIN}jarsigner" -verify "$AAB" > "$WORK/aab-verify.txt" 2>&1 && grep -q '^jar verified\.$' "$WORK/aab-verify.txt" \
        || { cat "$WORK/aab-verify.txt" >&2; fail "the signed bundle does not verify"; }
    u=$(norm "$("${JAVA_BIN}keytool" -printcert -jarfile "$AAB" 2> /dev/null | sed -n 's/^[[:space:]]*SHA256: //p' | head -n 1)")
    [ -n "$u" ] || fail "the signed bundle names no signer"
    bundletool check-transparency --mode=bundle --bundle="$AAB" > "$WORK/transparency.txt" 2>&1 \
        || { cat "$WORK/transparency.txt" >&2; fail "the bundle's code transparency does not verify"; }
    grep -q '^Code transparency verified' "$WORK/transparency.txt" \
        || { cat "$WORK/transparency.txt" >&2; fail "bundletool did not say the code matches the transparency file"; }
    t=$(norm "$(sed -n 's/.*fingerprint of the code transparency key certificate[^:]*: *//p' "$WORK/transparency.txt" | head -n 1)")
    case "$t" in "" | *[!0-9a-f]*) cat "$WORK/transparency.txt" >&2; fail "bundletool named no transparency key" ;; esac

    [ "$t" != "$a" ] || fail "the transparency key is the app signing key; Google requires a key used for nothing else"
    [ "$t" != "$u" ] || fail "the transparency key is the upload key; it must be used for nothing else"
    [ "$u" != "$a" ] || fail "the upload key is the app signing key; encri D115 keeps the app signing key offline and out of uploads"
    [ "$a" = "$WANT_APP" ] || fail "the APK is signed by $a, not the app signing key expected-certificates names, $WANT_APP"
    [ "$u" = "$WANT_UPLOAD" ] || fail "the bundle is signed by $u, not the upload key expected-certificates names, $WANT_UPLOAD"
    [ "$t" = "$WANT_TRANSPARENCY" ] \
        || fail "the transparency file is signed by $t, not the key expected-certificates names, $WANT_TRANSPARENCY"

    echo "== comparing the signed files with what reproduce.sh built"
    compare_builds signed "$APK" "$UNSIGNED_APK"; s=$?
    [ $s -eq 0 ] || { [ $s -eq 2 ] && refuse "compare-builds.py could not compare the APKs"; fail "the signed APK differs from the build"; }
    compare_builds bundle "$AAB" "$UNSIGNED_AAB"; s=$?
    [ $s -eq 0 ] || { [ $s -eq 2 ] && refuse "compare-builds.py could not compare the bundles"; fail "the signed bundle differs from the build"; }
}

if [ $MODE = verify ]; then
    [ -f "$SIGNED/SIGNED.txt" ] || refuse "no SIGNED.txt in $SIGNED; bring back all three files signed/ held"
    grep -E "^[0-9a-f]{64}  $NAME\.(apk|aab)$" "$SIGNED/SIGNED.txt" > "$WORK/signed.sums"
    [ "$(grep -c . "$WORK/signed.sums")" -eq 2 ] || fail "SIGNED.txt does not list $NAME.apk and $NAME.aab"
    sha256_check "$SIGNED" "$WORK/signed.sums" || fail "the signed files are not the ones SIGNED.txt records"
    check_signed "$SIGNED"
    echo
    cat "$SIGNED/SIGNED.txt"
    echo "== verified with $HERE: upload $NAME.aab to Play, and publish the three fingerprints (encri D115)"
    echo "   keep $NAME.apk with SIGNED.txt for the GitHub release, which waits on the trademark search (encri D136, D142)"
    exit 0
fi

# --- step 3: the APK ----------------------------------------------------------------------------------------------
echo "== signing the APK with the app signing key ($APP_ALIAS)"
apksigner sign --ks "$APP_KS" --ks-key-alias "$APP_ALIAS" ${APKSIGNER_PASS[@]+"${APKSIGNER_PASS[@]}"} \
    --min-sdk-version 27 --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --v4-signing-enabled false --out "$OUT/$NAME.apk" "$UNSIGNED_APK" || refuse "apksigner did not sign the APK"

# --- step 3: the bundle -------------------------------------------------------------------------------------------
echo "== adding code transparency with the transparency key ($TRANSPARENCY_ALIAS)"
bundletool add-transparency --bundle="$UNSIGNED_AAB" --output="$WORK/transparent.aab" \
    --ks="$TRANSPARENCY_KS" --ks-key-alias="$TRANSPARENCY_ALIAS" ${BUNDLETOOL_PASS[@]+"${BUNDLETOOL_PASS[@]}"} \
    || refuse "bundletool did not add code transparency"
echo "== signing the bundle with the upload key ($UPLOAD_ALIAS)"
"${JAVA_BIN}jarsigner" -keystore "$UPLOAD_KS" ${JARSIGNER_PASS[@]+"${JARSIGNER_PASS[@]}"} -digestalg SHA-256 \
    -signedjar "$OUT/$NAME.aab" "$WORK/transparent.aab" "$UPLOAD_ALIAS" || refuse "jarsigner did not sign the bundle"

# --- step 4: the keys, and the signed files against the unsigned build ---------------------------------------------
check_signed "$OUT"

{
    echo "signed $(date -u +%Y-%m-%dT%H:%M:%SZ) from $NAME"
    echo "app signing certificate SHA-256:  $a"
    echo "upload certificate SHA-256:       $u"
    echo "code transparency key SHA-256:    $t"
    (cd "$OUT" && $SHA256 "$NAME.apk" "$NAME.aab")
} > "$OUT/SIGNED.txt"
SIGNED_OK=1

echo
cat "$OUT/SIGNED.txt"
if [ $MODE = rehearse ]; then
    echo "== rehearsal: every step passed with throwaway keys; nothing is kept"
else
    echo "== $OUT: bring its three files back to where the kit was made, and run sign.sh --verify there before uploading"
fi
exit 0
