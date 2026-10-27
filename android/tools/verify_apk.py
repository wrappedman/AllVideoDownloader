#!/usr/bin/env python3
"""Verify an APK's APK Signature Scheme v2 block, with no SDK and no deps.

`apksigner verify` is the reference tool, and `build.sh` uses it when the Android
build-tools are installed. Where they are not (this repository builds without
Gradle or an SDK download), the fallback signer is checked by this script
instead: it re-implements the v2 verification Android itself performs —

  * find the APK Signing Block (magic "APK Sig Block 42") in front of the
    central directory,
  * recompute the SHA-256 content digests over the three sections (file
    contents, central directory, end-of-central-directory with the CD offset
    pointing at the signing block) using the 1 MiB chunking scheme,
  * compare them with the signed digest list, and
  * check the signature over the signed-data bytes with the certificate's own
    RSA key (pure-Python modular exponentiation, no third-party modules).

Usage:  python3 tools/verify_apk.py <apk> [--quiet]
Exit 0 = verified, 1 = not verified (reason printed).
"""
import hashlib
import struct
import sys
import zipfile

MAGIC = b"APK Sig Block 42"
V2_ID = 0x7109871A
CHUNK = 1024 * 1024
# AOSP ids: content digest 0x0101 = CHUNKED_SHA256, signature 0x0101 =
# RSASSA-PKCS1-v1_5 with SHA-256 (0x0103/0x0104 would be RSA-PSS, and 0x0102 a
# SHA-512 digest — accepting those would verify the wrong thing).
DIGEST_CHUNKED_SHA256 = 0x0101
SIG_RSA_PKCS1_SHA256 = 0x0101


class Bad(Exception):
    pass


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def u64(b, o):
    return struct.unpack_from("<Q", b, o)[0]


def lp(buf, off):
    """Read one length-prefixed item: returns (bytes, next_offset)."""
    if off + 4 > len(buf):
        raise Bad("truncated length prefix")
    n = u32(buf, off)
    start = off + 4
    if start + n > len(buf):
        raise Bad("truncated item")
    return buf[start:start + n], start + n


def lp_seq(buf):
    items, off = [], 0
    while off < len(buf):
        item, off = lp(buf, off)
        items.append(item)
    return items


def der_read(buf, off=0):
    """Minimal DER TLV reader: (tag, content, next_offset)."""
    tag = buf[off]
    off += 1
    n = buf[off]
    off += 1
    if n & 0x80:
        size = n & 0x7F
        n = int.from_bytes(buf[off:off + size], "big")
        off += size
    return tag, buf[off:off + n], off + n


def rsa_public_key(spki):
    """SubjectPublicKeyInfo DER → (n, e)."""
    _, spki_body, _ = der_read(spki, 0)                       # SEQUENCE
    _, _, off = der_read(spki_body, 0)                        # AlgorithmIdentifier
    _, bitstr, _ = der_read(spki_body, off)                   # BIT STRING
    key = bitstr[1:]                                          # drop unused-bits byte
    _, rsa_body, _ = der_read(key, 0)                         # RSAPublicKey SEQUENCE
    _, n_bytes, off = der_read(rsa_body, 0)
    _, e_bytes, _ = der_read(rsa_body, off)
    return int.from_bytes(n_bytes, "big"), int.from_bytes(e_bytes, "big")


def rsa_pkcs1_sha256_verify(n, e, signature, message, mod_bits=None):
    k = mod_bits // 8 if mod_bits else (n.bit_length() + 7) // 8
    if len(signature) != k:
        return False
    m = pow(int.from_bytes(signature, "big"), e, n)
    em = m.to_bytes(k, "big")
    # EM = 0x00 || 0x01 || PS(0xff…) || 0x00 || DigestInfo(SHA-256)
    digest_info = bytes.fromhex("3031300d060960864801650304020105000420") + hashlib.sha256(message).digest()
    pad_len = k - len(digest_info) - 3
    if pad_len < 8:
        return False
    expected = b"\x00\x01" + b"\xff" * pad_len + b"\x00" + digest_info
    return em == expected


def find_signing_block(data, cd_offset):
    """(block_start, pairs_bytes) for the signing block ending at cd_offset."""
    if cd_offset < 32:
        raise Bad("no room for a signing block")
    if data[cd_offset - 16:cd_offset] != MAGIC:
        raise Bad("no APK Signing Block (not v2-signed)")
    size_footer = u64(data, cd_offset - 24)
    start = cd_offset - size_footer - 8
    if start < 0 or u64(data, start) != size_footer:
        raise Bad("signing block sizes disagree")
    return start, data[start + 8:cd_offset - 24]


def chunked_digest(sections):
    """Chunk digests are H(0xa5 || len || chunk); the top hash is H(0x5a || count || …)."""
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


def verify(path):
    with open(path, "rb") as fh:
        data = fh.read()

    # EOCD
    idx = data.rfind(b"PK\x05\x06")
    if idx < 0:
        raise Bad("no end-of-central-directory record")
    eocd = bytearray(data[idx:])
    cd_offset = u32(data, idx + 16)
    cd_size = u32(data, idx + 12)

    block_start, pairs = find_signing_block(data, cd_offset)

    # find the v2 pair
    value, off = None, 0
    while off < len(pairs):
        plen = u64(pairs, off)
        pid = u32(pairs, off + 8)
        payload = pairs[off + 12:off + 8 + plen]
        if pid == V2_ID:
            value = payload
        off += 8 + plen
    if value is None:
        raise Bad("signing block has no v2 signature")

    # the v2 value is a list of length-prefixed signers; take the first
    signer, _ = lp(value, 0)
    signed_data, off = lp(signer, 0)
    signatures_blob, off = lp(signer, off)
    public_key, _ = lp(signer, off)

    # the digests the signature covers
    digests_blob, off2 = lp(signed_data, 0)
    claimed = {}
    for item in lp_seq(digests_blob):
        alg = u32(item, 0)
        digest, _ = lp(item, 4)
        claimed[alg] = digest
    if DIGEST_CHUNKED_SHA256 not in claimed:
        raise Bad("no chunked-SHA-256 content digest in the signature")

    # certificate list (kept for the identity check below)
    certs_blob, _ = lp(signed_data, off2)
    cert, _ = lp(certs_blob, 0)

    actual = chunked_digest([
        data[:block_start],
        data[cd_offset:cd_offset + cd_size],
        bytes(eocd[:16]) + struct.pack("<I", block_start) + bytes(eocd[20:]),
    ])
    if actual != claimed[DIGEST_CHUNKED_SHA256]:
        raise Bad("content digest does not match the signed one")

    # the signer must present the same key its certificate carries
    if public_key not in cert:
        raise Bad("the signer's public key is not the certificate's key")
    n, e = rsa_public_key(public_key)

    sig_ok = False
    for item in lp_seq(signatures_blob):
        alg = u32(item, 0)
        signature, _ = lp(item, 4)
        if alg != SIG_RSA_PKCS1_SHA256:
            continue
        sig_ok = rsa_pkcs1_sha256_verify(n, e, signature, signed_data)
        break
    if not sig_ok:
        raise Bad("signature over the signed data does not verify")

    with zipfile.ZipFile(path) as z:
        bad = z.testzip()
    if bad is not None:
        raise Bad(f"corrupt zip entry: {bad}")

    return True


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    path = argv[1]
    quiet = "--quiet" in argv
    try:
        verify(path)
    except Bad as exc:
        print(f"verify: FAILED — {exc}")
        return 1
    except Exception as exc:  # noqa: BLE001 - any parse error means "not verified"
        print(f"verify: FAILED — {type(exc).__name__}: {exc}")
        return 1
    if not quiet:
        print(f"verify: OK — v2 signature covers the whole file ({path})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
