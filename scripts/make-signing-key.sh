#!/bin/sh
# Creates Nook's release signing key in ~/.nook (outside the repo). Run once; back up ~/.nook afterwards.
set -eu
D="$HOME/.nook"
if [ -e "$D/release.jks" ]; then echo "Refusing: $D/release.jks already exists (losing/overwriting it breaks updates)."; exit 1; fi
. "$(dirname "$0")/env.sh"
mkdir -p "$D"; chmod 700 "$D"
PW=$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32)
"$JAVA_HOME/bin/keytool" -genkeypair -keystore "$D/release.jks" -storetype PKCS12 -alias nook \
  -keyalg RSA -keysize 4096 -validity 10000 -storepass "$PW" -keypass "$PW" -dname "CN=Nook" >/dev/null
umask 077
printf 'storePassword=%s\nkeyPassword=%s\nkeyAlias=nook\n' "$PW" "$PW" > "$D/keystore.properties"
chmod 600 "$D/release.jks" "$D/keystore.properties"
echo "Created $D/release.jks and $D/keystore.properties. Back up BOTH (e.g. in your password manager)."
