#!/usr/bin/env bash
# Verify that an APK and AAB share a valid signing certificate. Optionally
# require the same certificate as another distribution's APK.
set -euo pipefail

APK=${1:?APK path required}
AAB=${2:?AAB path required}
APKSIGNER=$(find "${ANDROID_HOME:?Android SDK required}/build-tools" -name apksigner -type f | sort -V | tail -1)
if [ -z "$APKSIGNER" ]; then
  echo "::error::Unable to locate apksigner"
  exit 1
fi

apk_certificate() {
  "$APKSIGNER" verify --print-certs-pem "$1" \
    | sed -n '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/p' \
    | openssl x509 -outform DER \
    | openssl dgst -sha256 -r \
    | awk '{print $1}'
}

"$APKSIGNER" verify --verbose --print-certs "$APK"
APK_CERT=$(apk_certificate "$APK")
AAB_SIGNATURE=$(zipinfo -1 "$AAB" | sed -n -E '/^META-INF\/.*\.(RSA|DSA|EC)$/p')
if [ -z "$AAB_SIGNATURE" ] || [[ "$AAB_SIGNATURE" == *$'\n'* ]]; then
  echo "::error::Expected exactly one AAB signing certificate"
  exit 1
fi
AAB_CERT=$(unzip -p "$AAB" "$AAB_SIGNATURE" \
  | openssl pkcs7 -inform DER -print_certs \
  | openssl x509 -outform DER \
  | openssl dgst -sha256 -r \
  | awk '{print $1}')
jarsigner -verify "$AAB"
if [ -z "$APK_CERT" ] || [ "$APK_CERT" != "$AAB_CERT" ]; then
  echo "::error::APK and AAB signing certificates do not match"
  exit 1
fi
if [ "$#" -ge 3 ] && [ "$APK_CERT" != "$(apk_certificate "$3")" ]; then
  echo "::error::Play and GitHub signing certificates do not match"
  exit 1
fi
echo "Verified APK and AAB certificate SHA-256: $APK_CERT"
