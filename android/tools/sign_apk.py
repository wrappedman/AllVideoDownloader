#!/usr/bin/env python3
"""Build an **installable** APK out of aapt2's output: zipalign + v1 + v2.

`build.sh` drives Google's `apksigner.jar` when the Android build-tools are
installed. They are not installable in this environment (no Gradle, no SDK
download), so this script does the same three jobs itself:

1. **zipalign** — every *uncompressed* entry is padded (through its local
   header's extra field) so its data starts on a 4-byte boundary, `.so` files on
   a 4096-byte one. Android 11+ *refuses to install* an app targeting API 30+
   whose `resources.arsc` is not uncompressed and 4-byte aligned; an unaligned
   APK is otherwise slow to mmap. This is what made the previous build fail with
   "App not installed".

2. **v1 (JAR)** — `META-INF/MANIFEST.MF`, `META-INF/AVD.SF`, `META-INF/AVD.RSA`
   with SHA-256 digests and a PKCS#7 detached signature, so Android 6 can
   install the APK. Digests cover entry *contents*, so aligning afterwards keeps
   them valid.

3. **v2 (APK Signature Scheme v2)** — the APK Signing Block with the AOSP ids
   (`0x0101` chunked-SHA-256 content digest, `0x0101` RSASSA-PKCS1-v1_5 +
   SHA-256), written **last**, after alignment, because its digest covers the
   final byte layout. Android 11+ requires v2 for `targetSdk ≥ 30`.

Usage:
  python3 tools/sign_apk.py <in.apk> <out.apk> [--key tools/debug-signing-key.DEBUG-ONLY.pem]
                            [--v1] [--no-v2]

The key file holds a PEM private key followed by its self-signed certificate. It
is a **debug** identity, committed on purpose: Android only accepts an update
signed with the same certificate as the installed copy.
"""
import hashlib
import struct
import sys
import zipfile
import zlib

MAGIC = b"APK Sig Block 42"
V2_ID = 0x7109871A
CHUNK = 1024 * 1024
# APK Signature Scheme v2 stores the *signature algorithm* id in both the digest
# record and the signature record, and derives the content-digest algorithm from
# it: 0x0103 = RSASSA-PKCS1-v1_5 with SHA-256 → chunked SHA-256 content digest.
# (0x0101/0x0102 are RSA-PSS; 0x0104 is PKCS#1 v1.5 with SHA-512.)
SIG_RSA_PKCS1_SHA256 = 0x0103
PAGE = 4096
ALIGN = 4


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
    import re

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


# ── zip read / write (alignment is the point of this section) ────────────────
class Entry:
    __slots__ = ("name", "data", "date_time", "stored", "external_attr")

    def __init__(self, name, data, date_time, stored, external_attr=0o644 << 16):
        self.name = name
        self.data = data
        self.date_time = date_time
        self.stored = stored
        self.external_attr = external_attr


def read_entries(path):
    out = []
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            out.append(
                Entry(
                    info.filename,
                    z.read(info.filename),
                    info.date_time,
                    info.compress_type == zipfile.ZIP_STORED,
                    info.external_attr,
                )
            )
    return out


def dos_time(dt):
    y, mo, d, h, mi, s = dt
    return struct.pack("<HH", (h << 11) | (mi << 5) | (s // 2), ((y - 1980) << 9) | (mo << 5) | d)


def alignment_of(name: str) -> int:
    return PAGE if name.endswith(".so") else ALIGN


def write_zip(path, entries):
    """Write entries with 4-byte aligned uncompressed data (zipalign's job)."""
    body = bytearray()
    central = bytearray()
    for e in entries:
        raw_name = e.name.encode("utf-8")
        if e.stored:
            payload = e.data
            method = 0
        else:
            compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
            payload = compressor.compress(e.data) + compressor.flush()
            method = 8
        crc = zlib.crc32(e.data) & 0xFFFFFFFF
        offset = len(body)
        align = alignment_of(e.name)
        # the local header is 30 bytes + name + extra; grow the extra field until
        # the data starts on the boundary. The padding is a real extra field
        # (id 0xd935, as zipalign uses) so every zip reader can skip it.
        base = offset + 30 + len(raw_name)
        pad = (align - base % align) % align
        if pad and pad < 4:
            pad += align
        extra = b""
        if pad:
            extra = struct.pack("<HH", 0xD935, pad - 4) + b"\x00" * (pad - 4)
        t_, d_ = struct.unpack("<HH", dos_time(e.date_time))
        body += struct.pack(
            "<IHHHHHIIIHH",
            0x04034B50, 20, 0, method, t_, d_, crc,
            len(payload), len(e.data), len(raw_name), len(extra),
        )
        body += raw_name + extra + payload

        central += struct.pack(
            "<IHHHHHHIIIHHHHHII",
            0x02014B50, 0x031E, 20, 0, method, t_, d_, crc,
            len(payload), len(e.data), len(raw_name), 0, 0, 0, 0,
            e.external_attr, offset,
        )
        central += raw_name

    cd_offset = len(body)
    body += central
    body += struct.pack(
        "<IHHHHIIH", 0x06054B50, 0, 0, len(entries), len(entries), len(central), cd_offset, 0
    )
    with open(path, "wb") as fh:
        fh.write(bytes(body))


# ── v1 (JAR signature) ───────────────────────────────────────────────────────
def jar_line(name: str, value: str) -> bytes:
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
    head = jar_line("Manifest-Version", "1.0") + jar_line("Created-By", "AllVideoDownloader")
    sections = {}
    for name, data in entries:
        sections[name] = (
            jar_line("Name", name) + jar_line("SHA-256-Digest", b64(hashlib.sha256(data).digest())) + b"\r\n"
        )
    return head + b"\r\n" + b"".join(sections[n] for n, _ in entries), sections


def v1_sign(entries, key, cert):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.serialization import pkcs7

    plain = [(e.name, e.data) for e in entries if not e.name.upper().startswith("META-INF/")]
    manifest, sections = v1_manifest(plain)

    sf = jar_line("Signature-Version", "1.0") + jar_line("Created-By", "AllVideoDownloader")
    sf += jar_line("SHA-256-Digest-Manifest", b64(hashlib.sha256(manifest).digest())) + b"\r\n"
    for name, _ in plain:
        sf += jar_line("Name", name)
        sf += jar_line("SHA-256-Digest", b64(hashlib.sha256(sections[name]).digest())) + b"\r\n"

    der = (
        pkcs7.PKCS7SignatureBuilder()
        .set_data(sf)
        .add_signer(cert, key, hashes.SHA256())
        .sign(serialization.Encoding.DER, [pkcs7.PKCS7Options.DetachedSignature,
                                           pkcs7.PKCS7Options.Binary])
    )

    kept = [
        e for e in entries
        if not (e.name.upper().startswith("META-INF/")
                and e.name.upper().endswith((".SF", ".RSA", ".DSA", ".MF")))
    ]
    stamp = (2024, 1, 1, 0, 0, 0)
    for name, data in (("META-INF/MANIFEST.MF", manifest),
                       ("META-INF/AVD.SF", sf),
                       ("META-INF/AVD.RSA", der)):
        kept.append(Entry(name, data, stamp, stored=True))
    return kept


# ── v2 ───────────────────────────────────────────────────────────────────────
def content_digest(sections):
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


def build_signer(key, cert, digest):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding

    cert_der = cert.public_bytes(serialization.Encoding.DER)
    pub_der = cert.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    digests = lp(lp(struct.pack("<I", SIG_RSA_PKCS1_SHA256) + lp(digest)))
    certs = lp(lp(cert_der))
    signed_data = digests + certs + lp(b"")
    signature = key.sign(signed_data, padding.PKCS1v15(), hashes.SHA256())
    signatures = lp(lp(struct.pack("<I", SIG_RSA_PKCS1_SHA256) + lp(signature)))
    signer = lp(signed_data) + signatures + lp(pub_der)
    # the v2 value is a length-prefixed **sequence** of length-prefixed signers,
    # so a single signer needs both levels — apksigner writes it this way and
    # Android's parser walks it that way
    return lp(lp(signer))


def v2_block(data, key, cert):
    idx = data.rfind(b"PK\x05\x06")
    if idx < 0:
        sys.exit("sign_apk: not a zip/apk (no EOCD)")
    cd_offset = u32(data, idx + 16)
    cd_size = u32(data, idx + 12)
    eocd = data[idx:]

    def finish(signer_bytes):
        pair = struct.pack("<Q", len(signer_bytes) + 12) + struct.pack("<I", V2_ID) + signer_bytes
        size = len(pair) + 8 + 16          # pairs + footer size + magic
        return struct.pack("<Q", size) + pair + struct.pack("<Q", size) + MAGIC

    block_len = len(finish(build_signer(key, cert, b"\x00" * 32)))
    patched = bytearray(eocd)
    struct.pack_into("<I", patched, 16, cd_offset)      # section offsets for the digest
    digest = content_digest([data[:cd_offset], data[cd_offset:cd_offset + cd_size], bytes(patched)])
    block = finish(build_signer(key, cert, digest))
    assert len(block) == block_len

    out = bytearray(data[:cd_offset] + block + data[cd_offset:])
    struct.pack_into("<I", out, idx + block_len + 16, cd_offset + block_len)
    return bytes(out)


# ── main ─────────────────────────────────────────────────────────────────────
def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    inp, outp = argv[1], argv[2]
    key_path = "tools/debug-signing-key.DEBUG-ONLY.pem"
    if "--key" in argv:
        key_path = argv[argv.index("--key") + 1]
    key, cert = load_key(key_path)

    if "--v1" in argv:
        entries = read_entries(inp)

        # Only Android 5-6 insist on the JAR signature. The build ships minSdk 24,
        # where the platform verifies v2 — and a subtly wrong v1 fails the
        # install on *every* device, so it stays opt-in.
        entries = v1_sign(entries, key, cert)
        write_zip(outp, entries)
        print("    v1: MANIFEST.MF + AVD.SF + PKCS#7 (SHA-256), entries realigned")
        with open(outp, "rb") as fh:
            data = fh.read()
    else:
        # no v1: leave the zip exactly as aapt2/zipfile wrote it — there is
        # nothing to align for v2, and rewriting a working zip only adds risk
        with open(inp, "rb") as fh:
            data = fh.read()
        print("    zip: untouched (v2 only)")

    if "--no-v2" not in argv:
        data = v2_block(data, key, cert)
        print("    v2: Signature Scheme v2 (chunked SHA-256, RSA PKCS#1 v1.5)")
        with open(outp, "wb") as fh:
            fh.write(data)

    print(f"    signed {outp}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
