#!/bin/sh
# Generates the TLS material that Qraft's tests use. FOR TESTS ONLY: these keys are public, in the repository.
# Valid for 100 years, so that no test depends on the date. Run from this directory: sh generate.sh
#
#   ca.pem                    the cluster's test CA
#   server.pem, server.key    a server: localhost, 127.0.0.1, server1 to server5; server and client use
#   server-ec.pem, -ec.key    the same names with an EC key
#   client.pem, client.key    a client, signed by the CA; client use
#   wrong-host.pem, .key      signed by the CA for elsewhere.invalid only; server and client use
#   other-ca.pem              another CA, which nothing trusts
#   other-client.pem, .key    a client signed by the other CA
set -eu
# Git Bash on Windows would otherwise rewrite a subject such as /CN=x into a Windows path.
export MSYS_NO_PATHCONV=1
DAYS=36500
cd "$(dirname "$0")"
# A relative scratch directory, which a native Windows OpenSSL can open as well.
work=.work
rm -rf "$work" && mkdir "$work"
trap 'rm -rf "$work"' EXIT

ca() {
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$work/$1.key" 2>/dev/null
    openssl req -x509 -new -key "$work/$1.key" -sha256 -days $DAYS -subj "/CN=$2" \
        -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" \
        -out "$1.pem"
}

leaf() {
    name=$1 issuer=$2 cn=$3 usage=$4 san=$5 algorithm=$6
    if [ "$algorithm" = ec ]; then
        openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$name.key"
    else
        openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$name.key"
    fi
    openssl req -new -key "$name.key" -subj "/CN=$cn" -out "$work/$name.csr"
    {
        echo "basicConstraints=critical,CA:FALSE"
        echo "keyUsage=critical,digitalSignature,keyEncipherment"
        echo "extendedKeyUsage=$usage"
        if [ -n "$san" ]; then echo "subjectAltName=$san"; fi
    } > "$work/$name.ext"
    openssl x509 -req -in "$work/$name.csr" -CA "$issuer.pem" -CAkey "$work/$issuer.key" -CAcreateserial \
        -CAserial "$work/$issuer.srl" -days $DAYS -sha256 -extfile "$work/$name.ext" -out "$name.pem" 2>/dev/null
}

SERVERS="DNS:localhost,IP:127.0.0.1,DNS:server1,DNS:server2,DNS:server3,DNS:server4,DNS:server5"
ca ca "Qraft test CA (tests only)"
ca other-ca "Another test CA (tests only)"
leaf server ca "qraft test server" "serverAuth,clientAuth" "$SERVERS" rsa
leaf server-ec ca "qraft test server (EC)" "serverAuth,clientAuth" "$SERVERS" ec
leaf client ca "qraft test client" "clientAuth" "" rsa
leaf wrong-host ca "elsewhere" "serverAuth,clientAuth" "DNS:elsewhere.invalid" rsa
leaf other-client other-ca "another client" "clientAuth" "" rsa
echo "generated"
