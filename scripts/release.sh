#!/bin/sh
# Usage: scripts/release.sh 1.2
# Bumps versionCode (+1) and versionName, runs the tests, builds the signed APK, commits, tags v<name>-<code>,
# pushes and publishes a GitHub release with the APK. Phones running Nook pick it up within a day.
set -eu
cd "$(dirname "$0")/.."
NAME="${1:?usage: scripts/release.sh <versionName, e.g. 1.2>}"
[ -f "$HOME/.nook/keystore.properties" ] || { echo "No signing key (~/.nook). Run scripts/make-signing-key.sh first."; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Working tree not clean."; exit 1; }
. scripts/env.sh
G=app/build.gradle.kts
CODE=$(( $(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$G") + 1 ))
sed -i '' "s/versionCode = [0-9]*/versionCode = $CODE/; s/versionName = \"[^\"]*\"/versionName = \"$NAME\"/" "$G"
./gradlew -q testDebugUnitTest assembleRelease
APK="app/build/outputs/apk/release/nook-$NAME.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"
TAG="v$NAME-$CODE"
git commit -qam "Release $NAME ($CODE)"
git tag "$TAG"
git push -q origin HEAD "$TAG"
gh release create "$TAG" "$APK" --title "Nook $NAME" --notes "Nook $NAME"
echo "Released $TAG"
