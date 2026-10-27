#!/usr/bin/env python3
"""Sign an APK with **APK Signature Scheme v2** using openssl + stdlib Python.

Why: `build.sh` prefers Google's `apksigner.jar`, then the Python `cryptography`
module (`tools/sign_apk.py`). Neither is available in SDK-less environments, so
this script does the same job with only the `openssl` binary (for the RSA
operation) and the Python standard library. The resulting APK installs on every
platform this app targets (minSdk 24 — all of them support APK Signature Scheme
v2, i.e. Android 7+).

The block layout is byte-for-byte compatible with `tools/sign_apk.py` and is
verified by `tools/verify_apk.py`:

    zipalign-style 4-byte padding of uncompressed entries  ->
    APK Signing Block (id 0x7109871A, chunked SHA-256 content digest,
    RSASSA-PKCS#1-v1_5 + SHA-256 signature) in front of the central directory  ->
    EOCD central-directory offset patched to point at the block

Usage:
  python3 tools/sign_apk_openssl.py <in.apk> <out.apk> --key <key.pem>

The key file holds an RSA private key followed by its certificate
(e.g. android/tools/debug-signing-key.DEBUG-ONLY.pem).
"""
import base64
import hashlib
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib

MAGIC = b"APK Sig Block 42"
V2_ID = 0x7109871A
SIG_ALG = 0x0103  # RSASSA-PKCS#1 v1.5 with SHA-256 -> chunked SHA-256 digest
CHUNK = 1024 * 1024
ALIGN = 4
PAGE = 4096


def lp(data):
    return struct.pack("<I", len(data)) + data


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def u64(b, o):
    return struct.unpack_from("<Q", b, o)[0]


def pem_blocks(path):
    """Return {kind: [der bytes, ...]} for every PEM block in *path*."""
    text = open(path, "rb").read()
    blocks = {}
    for kind in ("PRIVATE KEY", "RSA PRIVATE KEY", "CERTIFICATE", "PUBLIC KEY"):
        pattern = (b"-----BEGIN %s-----\\r?\\n(.*?)\\r?\\n-----END %s-----" % (kind.encode(), kind.encode()))
        for m in re.finditer(pattern, text, re.S):
            blocks.setdefault(kind, []).append(base64.b64decode(b"".join(m.group(1).split())))
    return blocks


def deflate(data):
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    return c.compress(data) + c.flush()


def dos_time(dt):
    y, mo, d, h, mi, s = dt
    return struct.pack("<HH", (h << 11) | (mi << 5) | (s // 2), ((y - 1980) << 9) | (mo << 5) | d)


def alignment_of(name):
    return PAGE if name.endswith(".so") else ALIGN


def rewrite_aligned(entries):
    """Re-zip entries so every uncompressed payload starts aligned (zipalign's job)."""
    body = bytearray()
    central = bytearray()
    for e in entries:
        raw_name = e["name"].encode("utf-8")
        if e["stored"]:
            payload = e["data"]
            method = 0
        else:
            payload = deflate(e["data"])
            method = 8
        crc = zlib.crc32(e["data"]) & 0xFFFFFFFF
        offset = len(body)
        align = alignment_of(e["name"])
        base = offset + 30 + len(raw_name)
        pad = (align - base % align) % align
        if pad and pad < 4:
            pad += align
        extra = b""
        if pad:
            extra = struct.pack("<HH", 0xD935, pad - 4) + b"\x00" * (pad - 4)
        t_, d_ = struct.unpack("<HH", dos_time(e["date_time"]))
        body += struct.pack(
            "<IHHHHHIIIHH",
            0x04034B50, 20, 0, method, t_, d_, crc,
            len(payload), len(e["data"]), len(raw_name), len(extra),
        )
        body += raw_name + extra + payload
        central += struct.pack(
            "<IHHHHHHIIIHHHHHII",
            0x02014B50, 0x031E, 20, 0, method, t_, d_, crc,
            len(payload), len(e["data"]), len(raw_name),
            0, 0, 0, 0, e["external_attr"], offset,
        )
        central += raw_name
    cd_offset = len(body)
    body += central
    body += struct.pack(
        "<IHHHHIIH", 0x06054B50, 0, 0, len(entries), len(entries),
        len(central), cd_offset, 0,
    )
    return bytes(body), cd_offset, len(central)


def chunked_digest(sections):
    chunks = []
    for section in sections:
        for i in range(0, len(section), CHUNK):
            chunk = section[i:i + CHUNK]
            h = hashlib.sha256()
            h.update(b"\xa5")
            h.update(struct.pack("<I", len(chunk)))
            h.update(chunk)
            chunks.append(h.digest())
    top = hashlib.sha256()
    top.update(b"\x5a")
    top.update(struct.pack("<I", len(chunks)))
    for d in chunks:
        top.update(d)
    return top.digest()


def openssl_run(args, input_data=None):
    done = subprocess.run(
        ["openssl"] + args, input=input_data, capture_output=True,
    )
    if done.returncode != 0:
        sys.exit(f"sign_apk_openssl: openssl {' '.join(args)} failed: {done.stderr.decode(errors='replace').strip()}")
    return done.stdout


def openssl_sign(message_path, key_pem_path):
    """RSASSA-PKCS#1 v1.5 + SHA-256 signature of the file at *message_path*."""
    blocks = pem_blocks(key_pem_path)
    keys = blocks.get("PRIVATE KEY") or blocks.get("RSA PRIVATE KEY")
    if not keys:
        sys.exit("sign_apk_openssl: key file has no private key")
    fd, key_file = tempfile.mkstemp(prefix="avd-key-", suffix=".pem")
    with os.fdopen(fd, "wb") as kf:
        kf.write(b"-----BEGIN PRIVATE KEY-----\n" + base64.b64encode(keys[0]) + b"\n-----END PRIVATE KEY-----\n")
    sig_file = key_file + ".sig"
    try:
        openssl_run(["dgst", "-sha256", "-sign", key_file, "-out", sig_file, message_path])
        return open(sig_file, "rb").read()
    finally:
        for p in (key_file, sig_file):
            try:
                os.unlink(p)
            except OSError:
                pass


def openssl_pubkey_der(cert_der):
    fd, cert_file = tempfile.mkstemp(prefix="avd-cert-", suffix=".pem")
    with os.fdopen(fd, "wb") as cf:
        cf.write(b"-----BEGIN CERTIFICATE-----\n" + base64.b64encode(cert_der) + b"\n-----END CERTIFICATE-----\n")
    try:
        out = openssl_run(["x509", "-in", cert_file, "-pubkey", "-noout"])
    finally:
        try:
            os.unlink(cert_file)
        except OSError:
            pass
    return pem_blocks_from_pem(out)["PUBLIC KEY"][0]


def pem_blocks_from_pem(pem_bytes):
    blocks = {}
    for kind in ("PUBLIC KEY", "CERTIFICATE", "PRIVATE KEY", "RSA PRIVATE KEY"):
        pattern = (b"-----BEGIN %s-----\\r?\\n(.*?)\\r?\\n-----END %s-----" % (kind.encode(), kind.encode()))
        m = re.search(pattern, pem_bytes, re.S)
        if m:
            blocks[kind] = [base64.b64decode(b"".join(m.group(1).split()))]
    return blocks


def signed_data_bytes(digest, cert_der):
    """signed_data = digests + certs + empty-signatures-placeholder.

    This is exactly what the RSA key signs (tools/sign_apk.py does the same):
    the signature covers everything *except* itself, which is a placeholder
    here and is filled in afterwards.
    """
    digests = lp(lp(struct.pack("<I", SIG_ALG) + lp(digest)))
    certs = lp(lp(cert_der))
    return digests + certs + lp(b"")


def build_signer(signed_data, sig, pub_der):
    """signer = lp(lp(signed_data + signatures + pub_der))."""
    signatures = lp(lp(struct.pack("<I", SIG_ALG) + lp(sig)))
    signer = lp(signed_data) + signatures + lp(pub_der)
    return lp(lp(signer))


def build_v2_block(data, cd_offset, cd_size, key_path, cert_der, pub_der):
    eocd_idx = data.rfind(b"PK\x05\x06")
    eocd = bytearray(data[eocd_idx:])

    def finish(signer_bytes):
        pair = struct.pack("<Q", len(signer_bytes) + 12) + struct.pack("<I", V2_ID) + signer_bytes
        size = len(pair) + 8 + 16  # pair + size fields + magic
        return struct.pack("<Q", size) + pair + struct.pack("<Q", size) + MAGIC

    # pass 1: block length with a zero content digest
    sd_zero = signed_data_bytes(b"\x00" * 32, cert_der)
    fd, msg_file = tempfile.mkstemp(prefix="avd-msg-")
    with os.fdopen(fd, "wb") as mf:
        mf.write(sd_zero)
    sig_zero = openssl_sign(msg_file, key_path)
    os.unlink(msg_file)
    block_len = len(finish(build_signer(sd_zero, sig_zero, pub_der)))

    # pass 2: real digest over the final layout
    patched = bytearray(eocd)
    struct.pack_into("<I", patched, 16, cd_offset)  # central directory right after the block
    digest = chunked_digest([data[:cd_offset], data[cd_offset:cd_offset + cd_size], bytes(patched)])
    sd = signed_data_bytes(digest, cert_der)
    fd, msg_file = tempfile.mkstemp(prefix="avd-msg-")
    with os.fdopen(fd, "wb") as mf:
        mf.write(sd)
    sig = openssl_sign(msg_file, key_path)
    os.unlink(msg_file)
    block = finish(build_signer(sd, sig, pub_der))
    assert len(block) == block_len

    out = bytearray(data[:cd_offset] + block + data[cd_offset:])
    # positions after the insertion point shift by len(block)
    struct.pack_into("<I", out, eocd_idx + len(block) + 16, cd_offset + len(block))
    return bytes(out)


def main(argv):
    if len(argv) < 4 or "--key" not in argv:
        print(__doc__)
        return 2
    inp, outp = argv[1], argv[2]
    key_path = argv[argv.index("--key") + 1]
    if shutil.which("openssl") is None:
        sys.exit("sign_apk_openssl: the 'openssl' binary is required but was not found")

    blocks = pem_blocks(key_path)
    certs = blocks.get("CERTIFICATE")
    if not certs:
        sys.exit("sign_apk_openssl: key file has no certificate")
    cert_der = certs[0]
    pub_der = openssl_pubkey_der(cert_der)

    with open(inp, "rb") as f:
        data = f.read()
    if data.rfind(b"PK\x05\x06") < 0:
        sys.exit("sign_apk_openssl: not a zip/apk (no end-of-central-directory)")

    with zipfile.ZipFile(inp) as z:
        if z.testzip() is not None:
            sys.exit("sign_apk_openssl: corrupt zip entry in input APK")
        entries = [
            dict(
                name=i.filename,
                data=z.read(i.filename),
                date_time=i.date_time,
                stored=i.compress_type == zipfile.ZIP_STORED,
                external_attr=i.external_attr,
            )
            for i in z.infolist()
        ]

    aligned, cd_offset, cd_size = rewrite_aligned(entries)
    signed = build_v2_block(aligned, cd_offset, cd_size, key_path, cert_der, pub_der)
    with open(outp, "wb") as f:
        f.write(signed)
    print(f"signed {outp} ({len(signed)} bytes) — APK Signature Scheme v2")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
