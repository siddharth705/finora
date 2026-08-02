#!/bin/sh
set -e

# Railway (and most PaaS providers) have no way to upload an actual file into the container --
# GOOGLE_APPLICATION_CREDENTIALS (see FirebaseConfig) needs a real, readable file path, not just a
# string value, because that's what the Firebase Admin SDK / Google's own credential-loading
# library expects. GOOGLE_APPLICATION_CREDENTIALS_BASE64 is the workaround: paste the whole
# Firebase service-account JSON, base64-encoded, as a normal Railway variable (a plain string,
# which Railway's Variables tab *does* support) -- this decodes it to a real file at container
# startup and points GOOGLE_APPLICATION_CREDENTIALS at it before the JVM ever starts.
#
# If GOOGLE_APPLICATION_CREDENTIALS is already set directly (e.g. a platform that DOES support
# real file mounts/volumes), this block is simply skipped and that value is used as-is -- this
# script never overwrites an explicitly-set GOOGLE_APPLICATION_CREDENTIALS.
if [ -n "$GOOGLE_APPLICATION_CREDENTIALS_BASE64" ] && [ -z "$GOOGLE_APPLICATION_CREDENTIALS" ]; then
  echo "$GOOGLE_APPLICATION_CREDENTIALS_BASE64" | base64 -d > /app/firebase-service-account.json
  export GOOGLE_APPLICATION_CREDENTIALS=/app/firebase-service-account.json
fi

exec java -XX:MaxRAMPercentage=75.0 -jar app.jar
