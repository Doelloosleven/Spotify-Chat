#!/usr/bin/env python3
"""Signs Spotify Chat release jars for the auto-updater.

For every jar given, writes <jar>.sig next to it: the raw 64-byte Ed25519 signature over the jar's
bytes. Upload each .sig to the GitHub release together with its jar; the updater refuses jars without one.

The private key is a PEM (PKCS#8) Ed25519 key. Its path comes from the SPOTIFY_CHAT_SIGNING_KEY
environment variable, so it never has to be inside the repo (*.pem is in .gitignore anyway).

    python tools/sign_release.py build/libs/spotify-chat-1.3.0+26.2.jar ...
    python tools/sign_release.py --public-key

--public-key prints the base64 X.509 public key that goes into UpdateChecker.RELEASE_PUBLIC_KEY.

A key can be made once with:  openssl genpkey -algorithm ed25519 -out spotify-chat-signing.pem
Keep it offline or in a CI secret; anyone who has it can publish updates that players install.

Needs the "cryptography" package (pip install cryptography).
"""
import base64
import os
import sys

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

KEY_ENV = "SPOTIFY_CHAT_SIGNING_KEY"


def load_key() -> Ed25519PrivateKey:
    path = os.environ.get(KEY_ENV)
    if not path:
        sys.exit(f"Set {KEY_ENV} to the path of the Ed25519 private key (PEM).")
    with open(path, "rb") as f:
        key = serialization.load_pem_private_key(f.read(), password=None)
    if not isinstance(key, Ed25519PrivateKey):
        sys.exit("That key isn't an Ed25519 key.")
    return key


def main(args: list[str]) -> None:
    if not args:
        sys.exit(__doc__)
    key = load_key()
    if args == ["--public-key"]:
        der = key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        print(base64.b64encode(der).decode("ascii"))
        return
    for jar in args:
        if not jar.endswith(".jar"):
            sys.exit(f"Not a jar: {jar}")
        with open(jar, "rb") as f:
            signature = key.sign(f.read())
        with open(jar + ".sig", "wb") as f:
            f.write(signature)
        print(f"Signed {os.path.basename(jar)} -> {os.path.basename(jar)}.sig")


if __name__ == "__main__":
    main(sys.argv[1:])
