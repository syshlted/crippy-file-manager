#!/usr/bin/env bash
#
# Builds Encri Files' release twice from one commit, in two environments that differ on purpose, and
# says whether the two came out the same (F9). encri's script, for encri's reasons (encri D116, D126):
# what follows says "encri" where it means this app as well.
#
#   scripts/release/reproduce.sh [<commit>]       default HEAD
#
# Environment:
#   ENCRI_REPRO_SECOND_JDK   a JDK 21 for the second build, to vary the JDK as well
#   ENCRI_REPRO_KEEP=1       keep the two clones afterwards
#   ENCRI_API_SOURCE         where encri-api is cloned from; default this checkout's sibling when it has
#                             one, and otherwise the repository config/encri-api.pin names
#
# Exit status: 0 both builds are the same, byte for byte; 1 they differ, which is a finding; 2 a
# build failed or nothing could be compared, which is a refusal. kit.sh and sign.sh use the same three.
#
# **What differs between the two builds, and why each.** The second clone sits at another path,
# because a path that leaks into an output is the commonest cause. It runs under another umask,
# timezone and locale — Turkish, whose dotless i is what breaks a case-insensitive comparison that
# forgot its locale. And it has a Gradle home of its own, reading downloaded artifacts from the
# first one's through GRADLE_RO_DEP_CACHE but sharing no transforms and no daemon, so a transform
# whose output differs between runs cannot be hidden by both builds reusing one result. Both run
# with the build cache off, for the same reason.
#
# **What it does not vary is the toolchain.** Gradle, AGP, Kotlin, R8 and every dependency are pinned
# by the repository and checked by gradle/verification-metadata.xml, and that is the claim: the same
# source and the same pinned tools give the same bytes. The JDK is pinned to 21 by feature release
# and not by patch, so provenance.txt records which one each build used.
#
# **encri-api is built from source beside each clone** (F1), at the commit the built commit's own
# config/encri-api.pin names (F9), so a release is reproducible from the two commits together and its
# provenance names both. A commit from before the pin that builds against encri-api is refused, because
# nothing records which encri-api it was built from.
#
# The first build's unsigned APK and bundle are kept in build/reproducible/<commit>/ with their
# SHA-256s and a provenance file. Those are what a maintainer signs (encri D115, D116), after checking the
# hashes match what CI built from the same tag: .github/workflows/reproducible.yml runs this script there.
set -uo pipefail

REPO=$(git rev-parse --show-toplevel 2>/dev/null) || { echo "REFUSED: not inside a git repository" >&2; exit 2; }
REV=${1:-HEAD}
SHA=$(git -C "$REPO" rev-parse --verify --quiet "$REV^{commit}") || { echo "REFUSED: no commit $REV" >&2; exit 2; }
SHORT=${SHA:0:12}
GUH=${GRADLE_USER_HOME:-$HOME/.gradle}
OUT=$REPO/build/reproducible/$SHORT
WORK=$(mktemp -d "${TMPDIR:-/tmp}/encri-files-repro.XXXXXX") || { echo "REFUSED: no temporary directory" >&2; exit 2; }
FIRST=$WORK/first
SECOND=$WORK/second-checkout-at-another-depth/encri-files
SECOND_HOME=$WORK/gradle-home

finish() {
    # The second build's daemon writes into its Gradle home as it exits, so it is stopped first, and the
    # removal is tried a second time if a file appeared under it.
    if [ -x "$SECOND/gradlew" ]; then
        ( cd "$SECOND" && GRADLE_USER_HOME="$SECOND_HOME" ./gradlew --stop > /dev/null 2>&1 )
    fi
    if [ "${ENCRI_REPRO_KEEP:-0}" = 1 ]; then
        echo "kept: $WORK"
    else
        rm -rf -- "$WORK" 2> /dev/null || { sleep 2; rm -rf -- "$WORK"; }
    fi
}
trap finish EXIT


# **The bundle depends on how the checkout's path sorts against the Gradle home's.** AGP numbers the
# directories it merges resources from in the order their absolute paths sort
# (RelativeResourceUtils.getIdentifiedSourceSetMap), and writes those numbers into the bundle's
# resources.pb as the source of each value. The libraries' directories are under the Gradle home and
# encri's own are under the checkout, so a checkout that sorts before the Gradle home numbers them
# the other way round and builds a different bundle. Measured 2026-10-22: 88 bytes of
# resources.pb, and nothing else. The APK carries no sources and is unaffected. So every build this
# compares, and every build a release is signed from, has its Gradle home sorting first, which
# ~/.gradle does against a checkout anywhere conventional, CI's included.
sorts_first() {
    LC_ALL=C python3 -c 'import sys; a, b = sys.argv[1] + "/", sys.argv[2] + "/"; sys.exit(0 if a < b and not b.startswith(a) else 1)' "$1" "$2"
}
refuse_unless_home_sorts_first() {
    if ! sorts_first "$1" "$2"; then
        echo "REFUSED: the Gradle home $1 does not sort before the checkout $2, so the bundle would not" >&2
        echo "         match a build made the conventional way; set GRADLE_USER_HOME or TMPDIR" >&2
        exit 2
    fi
}
refuse_unless_home_sorts_first "$GUH" "$FIRST"
refuse_unless_home_sorts_first "$SECOND_HOME" "$SECOND"
refuse_unless_home_sorts_first "$GUH" "$WORK/encri-api"
refuse_unless_home_sorts_first "$SECOND_HOME" "$(dirname "$SECOND")/encri-api"

if [ ! -f "$REPO/local.properties" ] && [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]; then
    echo "REFUSED: no Android SDK: neither $REPO/local.properties nor ANDROID_HOME" >&2
    exit 2
fi

# **A release shows the notices its libraries require (F10).** :app:checkThirdPartyNotices holds them to what
# the build carries; a commit from before them has none to show, and is refused.
if ! git -C "$REPO" cat-file -e "$SHA:app/src/main/assets/third-party-notices.txt" 2> /dev/null; then
    echo "REFUSED: $SHORT shows no third-party notices: it predates them (F10)" >&2
    exit 2
fi

TASKS=(--no-build-cache --console=plain :app:assembleRelease :app:bundleRelease)

checkout() {
    mkdir -p "$(dirname "$1")"
    git clone --quiet --no-hardlinks "$REPO" "$1" && git -C "$1" checkout --quiet --detach "$SHA" || return 1
    # The SDK's location is the one input that may differ by machine and must not reach the build.
    if [ -f "$REPO/local.properties" ]; then cp "$REPO/local.properties" "$1/"; fi
}

checkout "$FIRST" && checkout "$SECOND" || { echo "REFUSED: could not clone $SHA" >&2; exit 2; }

# The two builds share one Gradle distribution (below), so an altered one would run inside both and both
# would agree. So the commit must pin it by checksum and carry Gradle's own wrapper jar, each as gradle.org
# publishes them for its version (F9); a commit from before the pin is refused, as one from before
# config/encri-api.pin is.
"$(dirname "$0")/../ci/gradle-wrapper-check" "$FIRST" \
    || { echo "REFUSED: $SHORT's Gradle wrapper or distribution is not pinned to what gradle.org publishes" >&2; exit 2; }

API_SOURCE=${ENCRI_API_SOURCE:-}
if [ -z "$API_SOURCE" ] && [ -d "$(dirname "$REPO")/encri-api/.git" ]; then
    API_SOURCE=$(dirname "$REPO")/encri-api
fi
API_COMMIT=none
API_FROM=
with_encri_api() {
    if [ ! -f "$1/config/encri-api.pin" ]; then
        if grep -q 'includeBuild' "$1/settings.gradle.kts"; then
            echo "REFUSED: $SHORT builds against an encri-api it does not name: it predates config/encri-api.pin (F9)" >&2
            exit 2
        fi
        return 0
    fi
    ENCRI_API_SOURCE=$API_SOURCE "$1/scripts/ci/encri-api" fetch "$(dirname "$1")/encri-api" > /dev/null || exit 2
    API_COMMIT=$("$1/scripts/ci/encri-api" commit)
    API_FROM=${API_SOURCE:-$(sed -n 's/^repository=//p' "$1/config/encri-api.pin")}
}
with_encri_api "$FIRST"
with_encri_api "$SECOND"

{
    echo "# The second build's JVM: another locale and timezone. A later key in this file wins."
    echo "org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8 -Duser.language=tr -Duser.country=TR -Duser.timezone=Asia/Kolkata"
    if [ -n "${ENCRI_REPRO_SECOND_JDK:-}" ]; then
        echo "org.gradle.java.installations.auto-detect=false"
        echo "org.gradle.java.installations.auto-download=false"
        echo "org.gradle.java.installations.paths=$ENCRI_REPRO_SECOND_JDK"
    fi
} >> "$SECOND/gradle.properties"

# A Gradle home of its own for the second build. The wrapper's distribution is shared, because it is
# Gradle itself and pinned by checksum, which the check above holds; downloaded artifacts are read
# through the read-only cache.
mkdir -p "$SECOND_HOME"
if [ -d "$GUH/wrapper" ]; then ln -s "$GUH/wrapper" "$SECOND_HOME/wrapper"; fi

echo "== first build: $FIRST"
( cd "$FIRST" && ./gradlew "${TASKS[@]}" > "$WORK/first.log" 2>&1 )
first=$?
echo "== second build: $SECOND"
( cd "$SECOND" && umask 077 && export TZ=Asia/Kolkata LANG=tr_TR.UTF-8 \
    GRADLE_USER_HOME="$SECOND_HOME" GRADLE_RO_DEP_CACHE="$GUH/caches" \
    ${ENCRI_REPRO_SECOND_JDK:+JAVA_HOME="$ENCRI_REPRO_SECOND_JDK"} && \
    ./gradlew "${TASKS[@]}" > "$WORK/second.log" 2>&1; status=$?; ./gradlew --stop > /dev/null 2>&1; exit $status )
second=$?

if [ $first -ne 0 ] || [ $second -ne 0 ]; then
    mkdir -p "$OUT"
    cp "$WORK/first.log" "$WORK/second.log" "$OUT/"
    echo "REFUSED: a build failed (first $first, second $second); logs in $OUT" >&2
    exit 2
fi

APK=app/build/outputs/apk/release/app-release-unsigned.apk
AAB=app/build/outputs/bundle/release/app-release.aab
COMPARE=$REPO/scripts/release/compare-builds.py

python3 "$COMPARE" same "$FIRST/$APK" "$SECOND/$APK"
apk=$?
python3 "$COMPARE" same "$FIRST/$AAB" "$SECOND/$AAB"
aab=$?

mkdir -p "$OUT"
cp "$FIRST/$APK" "$OUT/encri-files-$SHORT-unsigned.apk"
cp "$FIRST/$AAB" "$OUT/encri-files-$SHORT-unsigned.aab"
( cd "$OUT" && sha256sum "encri-files-$SHORT-unsigned.apk" "encri-files-$SHORT-unsigned.aab" > SHA256SUMS )
{
    echo "commit: $SHA"
    echo "encri-api: $API_COMMIT${API_FROM:+, cloned from $API_FROM}"
    echo "built: $(date -u +%Y-%m-%dT%H:%M:%SZ) on $(uname -srm)"
    echo
    echo "first build:"
    ( cd "$FIRST" && ./gradlew --version 2>/dev/null | sed 's/^/  /' )
    echo "second build:"
    ( cd "$SECOND" && env GRADLE_USER_HOME="$SECOND_HOME" \
        ${ENCRI_REPRO_SECOND_JDK:+JAVA_HOME="$ENCRI_REPRO_SECOND_JDK"} ./gradlew --version 2>/dev/null | sed 's/^/  /' )
    echo
    echo "the two builds: apk $([ $apk -eq 0 ] && echo same || echo DIFFERENT), bundle $([ $aab -eq 0 ] && echo same || echo DIFFERENT)"
} > "$OUT/provenance.txt"
( cd "$FIRST" && ./gradlew --stop > /dev/null 2>&1 )

echo "== $OUT"
cat "$OUT/SHA256SUMS"
if [ $apk -eq 0 ] && [ $aab -eq 0 ]; then
    echo "REPRODUCIBLE: $SHORT built the same twice"
    exit 0
fi
[ $apk -eq 2 ] || [ $aab -eq 2 ] && exit 2
echo "NOT REPRODUCIBLE: $SHORT built differently; set ENCRI_REPRO_KEEP=1 to look at both" >&2
exit 1
