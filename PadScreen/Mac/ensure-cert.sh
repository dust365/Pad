#!/bin/sh
# 在登录钥匙串里准备一张只用于代码签名的本机证书。已存在且受信任时什么都不做。
set -eu
CERT="PadScreen Local"
KEYCHAIN="$HOME/Library/Keychains/login.keychain-db"

if security find-identity -v -p codesigning | grep -q "\"$CERT\""; then
  exit 0
fi

TMP=$(mktemp -d "${TMPDIR:-/tmp}/padscreen-cert.XXXXXX")
cleanup() {
  rm -rf "$TMP"
}
trap cleanup EXIT

if security find-certificate -c "$CERT" "$KEYCHAIN" >/dev/null 2>&1; then
  security find-certificate -c "$CERT" -p "$KEYCHAIN" > "$TMP/cert.pem"
else
  cat > "$TMP/cert.cnf" << EOF
[ req ]
distinguished_name = req_dn
prompt = no
x509_extensions = codesign_ext

[ req_dn ]
CN = $CERT
O = Pad

[ codesign_ext ]
basicConstraints = critical, CA:TRUE
keyUsage = critical, digitalSignature, keyCertSign
extendedKeyUsage = critical, codeSigning
subjectKeyIdentifier = hash
EOF
  openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
    -keyout "$TMP/key.pem" -out "$TMP/cert.pem" -config "$TMP/cert.cnf"
  openssl pkcs12 -export -inkey "$TMP/key.pem" -in "$TMP/cert.pem" \
    -out "$TMP/cert.p12" -name "$CERT" -passout pass:padscreen
  security import "$TMP/cert.p12" -k "$KEYCHAIN" -P padscreen -f pkcs12 -A \
    -T /usr/bin/codesign -T /usr/bin/security
fi

# 只信任代码签名，不把它当成网页证书。系统会要求确认一次登录密码。
security add-trusted-cert -r trustRoot -p codeSign -k "$KEYCHAIN" "$TMP/cert.pem"

if ! security find-identity -v -p codesigning | grep -q "\"$CERT\""; then
  echo "证书已导入，但系统还没有把它当成代码签名身份。" >&2
  exit 1
fi
