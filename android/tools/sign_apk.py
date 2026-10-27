#!/usr/bin/env python3
"""Sign an APK with v1 (JAR) + v2 (APK Signature Scheme v2), without any SDK.

`build.sh` prefers Google's `apksigner.jar` and falls back to this script when
the Android build-tools are not installed (no Gradle, no SDK download — the
situation this repository is built in). The fallback has to produce something
Android actually installs:

  * **v2** — required for `targetSdk ≥ 30` on Android 11+, and the scheme Android
    7+ verifies. This script writes the block itself, with the *correct*
    algorithm ids (`0x0101` = chunked-SHA-256 content digest, RSASSA-PKCS1-v1_5
    with SHA-256 signature), the 1 MiB chunking scheme, and the EOCD central
    directory offset pointing at the signing block.
  * **v1** — needed by Android 6 and older, produced by the JDK's own
    `jarsigner` from a PKCS#12 keystore derived from the same PEM key.

Usage:
  python3 tools/sign_apk.py <in.apk> <out.apk> [--key tools/debug-signing-key.DEBUG-ONLY.pem]
                            [--no-v1] [--no-v2]

The key file holds a PEM private key followed by its self-signed certificate.
It is a **debug** identity: what matters is that every build of this app uses the
same one, otherwise Android treats the new APK as a different app.
"""
import hashlib
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

MAGIC = b"APK Sig Block 42"
V2_ID = 0x7109871A
CHUNK = 1024 * 1024
# APK Signature Scheme v2 ids (AOSP ApkSignatureSchemeV2Verifier):
#   digest: 0x0101 = CHUNKED_SHA256 | signature: 0x0101 = RSASSA-PKCS1-v1_5 SHA-256
DIGEST_CHUNKED_SHA256 = 0x0101
SIG_RSA_PKCS1_SHA256 = 0x0101


def lp(data: bytes) -> bytes:
    return struct.pack("<I", len(data)) + data


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def u64(b, o):
    return struct.unpack_from("<Q", b, o)[0]


# ── key material ─────────────────────────────────────────────────────────────
def load_key(path):
    from cryptography import x509
    from cryptography.hazmat.primitives import serialization

    pem = open(path, "rb").read()
    key_block = re.search(
        rb"-----BEGIN (?:RSA )?PRIVATE KEY-----[\s\S]+?-----END (?:RSA )?PRIVATE KEY-----", pem
    )
    cert_block = re.search(rb"-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----", pem)
    if not key_block or not cert_block:
        sys.exit(f"sign_apk: {path} must contain a private key and a certificate")
    key = serialization.load_pem_private_key(key_block.group(0), password=None)
    cert = x509.load_pem_x509_certificate(cert_block.group(0))
    return key, cert


# ── v2 ───────────────────────────────────────────────────────────────────────
def content_digest(sections):
    """Chunked SHA-256 per the spec: H(0x5a || count || per-chunk H(0xa5 || len || chunk))."""
    chunk_digests = []
    for section in sections:
        for i in range(0, len(section), CHUNK):
            chunk = section[i:i + CHUNK]
            h = hashlib.sha256()
            h.update(b"\xa5")
            h.update(struct.pack("<I", len(chunk)))
            h.update(chunk)
            chunk_digests.append(h.digest())
    top = hashlib.sha256()
    top.update(b"\x5a")
    top.update(struct.pack("<I", len(chunk_digests)))
    for d in chunk_digests:
        top.update(d)
    return top.digest()


def build_signer(key, cert, digest):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding

    cert_der = cert.public_bytes(serialization.Encoding.DER)
    pub_der = cert.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    digests = lp(lp(struct.pack("<I", DIGEST_CHUNKED_SHA256) + lp(digest)))
    certs = lp(lp(cert_der))
    attrs = lp(b"")
    signed_data = digests + certs + attrs
    signature = key.sign(signed_data, padding.PKCS1v15(), hashes.SHA256())
    signatures = lp(lp(struct.pack("<I", SIG_RSA_PKCS1_SHA256) + lp(signature)))
    signer = lp(signed_data) + signatures + lp(pub_der)
    return lp(signer)


def v2_block(data, key, cert):
    idx = data.rfind(b"PK\x05\x06")
    if idx < 0:
        sys.exit("sign_apk: not a zip/apk (no EOCD)")
    cd_offset = u32(data, idx + 16)
    cd_size = u32(data, idx + 12)
    eocd = data[idx:]
    if idx + 22 != len(eocd) or u32(eocd, 20) != 0:
        # a zip comment moves the record; APKs made here have none
        eocd = eocd[:22]

    def finish(signer_bytes):
        pair = struct.pack("<Q", len(signer_bytes) + 12) + struct.pack("<I", V2_ID) + signer_bytes
        # the size field covers everything after it: pairs + footer size + magic
        size = len(pair) + 8 + 16
        block = struct.pack("<Q", size) + pair + struct.pack("<Q", size) + MAGIC
        return block, len(block)

    # the block's size does not depend on the digest values, so measure once
    probe, block_len = finish(build_signer(key, cert, b"\x00" * 32))
    block_start = cd_offset
    patched_eocd = bytearray(eocd)
    struct.pack_into("<I", patched_eocd, 16, block_start)
    digest = content_digest([
        data[:cd_offset],
        data[cd_offset:cd_offset + cd_size],
        bytes(patched_eocd),
    ])
    block, block_len = finish(build_signer(key, cert, digest))
    assert len(block) == block_len

    out = bytearray(data[:cd_offset] + block + data[cd_offset:])
    new_eocd_at = idx + block_len
    struct.pack_into("<I", out, new_eocd_at + 16, cd_offset + block_len)
    return bytes(out)


# ── v1 (JAR signature) ───────────────────────────────────────────────────────
def jar_line(name: str, value: str) -> bytes:
    """One JAR manifest line, wrapped at 72 bytes with a leading space."""
    raw = f"{name}: {value}".encode()
    if len(raw) <= 72:
        return raw + b"\r\n"
    out, first = b"", True
    while raw:
        room = 72 if first else 71
        out += (b"" if first else b" ") + raw[:room] + b"\r\n"
        raw = raw[room:]
        first = False
    return out


def b64(data: bytes) -> str:
    import base64
    return base64.b64encode(data).decode()


def v1_manifest(entries):
    """(MANIFEST.MF bytes, {name: section bytes}) for the given (name, data) pairs."""
    head = jar_line("Manifest-Version", "1.0") + jar_line("Created-By", "AllVideoDownloader")
    manifest = head + b"\r\n"
    sections = {}
    for name, data in entries:
        section = jar_line("Name", name) + jar_line("SHA-256-Digest", b64(hashlib.sha256(data).digest()))
        sections[name] = section + b"\r\n"
    for name, _ in entries:
        manifest += sections[name]
    return manifest, sections


def v1_sign(inp, outp, key, cert):
    """Write MANIFEST.MF / CERT.SF / CERT.RSA into a copy of the APK."""
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.serialization import pkcs7

    with zipfile.ZipFile(inp) as zin:
        items = [(i, zin.read(i.filename)) for i in zin.infolist()]

    # every entry gets a digest, except the signature files themselves
    plain = [(i.filename, data) for i, data in items
             if not i.filename.upper().startswith("META-INF/")]
    manifest, sections = v1_manifest(plain)

    sf = jar_line("Signature-Version", "1.0") + jar_line("Created-By", "AllVideoDownloader")
    sf += jar_line("SHA-256-Digest-Manifest", b64(hashlib.sha256(manifest).digest()))
    sf += b"\r\n"
    for name, _ in plain:
        sf += jar_line("Name", name)
        sf += jar_line("SHA-256-Digest", b64(hashlib.sha256(sections[name]).digest()))
        sf += b"\r\n"

    der = (
        pkcs7.PKCS7SignatureBuilder()
        .set_data(sf)
        .add_signer(cert, key, hashes.SHA256())
        .sign(serialization.Encoding.DER, [pkcs7.PKCS7Options.DetachedSignature,
                                           pkcs7.PKCS7Options.Binary])
    )

    extra = [
        ("META-INF/MANIFEST.MF", manifest),
        ("META-INF/AVD.SF", sf),
        ("META-INF/AVD.RSA", der),
    ]
    with zipfile.ZipFile(outp, "w", zipfile.ZIP_DEFLATED) as zout:
        for info, data in items:
            if info.filename.upper().startswith("META-INF/") and \
                    info.filename.upper().endswith((".SF", ".RSA", ".DSA", ".MF")):
                continue
            zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            zi.external_attr = info.external_attr
            zout.writestr(zi, data, zipfile.ZIP_STORED if info.compress_type == zipfile.ZIP_STORED
                          else zipfile.ZIP_DEFLATED)
        for name, data in extra:
            zout.writestr(zipfile.ZipInfo(name, date_time=(2024, 1, 1, 0, 0, 0)), data,
                          zipfile.ZIP_STORED)


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    inp, outp = argv[1], argv[2]
    key_path = "tools/debug-signing-key.DEBUG-ONLY.pem"
    if "--key" in argv:
        key_path = argv[argv.index("--key") + 1]
    key, cert = load_key(key_path)

    with open(inp, "rb") as fh:
        data = fh.read()

    if "--no-v1" not in argv:
        with tempfile.TemporaryDirectory() as tmp:
            jar = os.path.join(tmp, "v1.apk")
            v1_sign(inp, jar, key, cert)
            data = open(jar, "rb").read()
        print("    v1: MANIFEST.MF + CERT.SF + PKCS#7 (SHA-256)")

    if "--no-v2" not in argv:
        data = v2_block(data, key, cert)
        print("    v2: App Signature Scheme v2 (chunked SHA-256, RSA PKCS#1 v1.5)")

    with open(outp, "wb") as fh:
        fh.write(data)

    with zipfile.ZipFile(outp) as z:
        bad = z.testzip()
    if bad is not None:
        sys.exit(f"sign_apk: output zip is corrupt at {bad}")
    print(f"    signed {outp}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
