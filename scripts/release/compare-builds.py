#!/usr/bin/env python3
"""Compare two builds of Encri Files, or a signed release against a rebuild of its source (F9, encri D116).

    compare-builds.py same   A.apk B.apk              the two files, byte for byte
    compare-builds.py signed SIGNED.apk UNSIGNED.apk  the signed APK with its signing block taken out
    compare-builds.py bundle SIGNED.aab UNSIGNED.aab  entry by entry, less what signing adds

Exit status follows reproduce.sh's: 0 the builds are the same, 1 they
differ, which is a finding, and 2 the comparison could not be made, which is a refusal. A
refusal never reads as either answer.

**What `signed` sets aside, and why it may.** minSdk is 27, here as in encri, so an APK is signed with
schemes v2 and up only. Signing inserts the APK Signing Block before the central directory, and
`apksigner` also rewrites every local header's alignment padding in a format of its own: AGP pads
an entry with zero bytes, `apksigner` with an alignment record, so each entry after the first
padded one moves by a few bytes. Measured on 2026-10-22 with build-tools 36.0.0, with and without
`--alignment-preserved`. So the comparison takes the signing block out and then holds every
entry's name, order, header fields and stored bytes, the central directory and the end record to
the unsigned build exactly, setting aside only padding of those two forms and the offsets it moves.
Padding is skipped by every zip reader, the platform's included; nothing in it is code or data.
A v1 signature adds entries, so this refuses an APK that carries one rather than calling it
different.

**Why `bundle` is not.** An app bundle is signed with jarsigner, which writes the archive anew;
what it must leave alone is every entry's content, so that is what is compared. Code
transparency's signed file is left out with the signature files, since only the signed bundle
can carry it.
"""
import hashlib
import struct
import sys
import zipfile

EOCD = b"PK\x05\x06"
ZIP64_LOCATOR = b"PK\x06\x07"
SIG_BLOCK_MAGIC = b"APK Sig Block 42"
EOCD_BYTES = 22
SHOWN = 20

# What signing a bundle adds, and nothing else.
SIGNATURE_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")
SIGNING_ADDS = {
    "META-INF/MANIFEST.MF",
    "BUNDLE-METADATA/com.android.tools.build.bundletool/code_transparency_signed.jwt",
}


class Refusal(Exception):
    """The comparison could not be made, which is not the same as the builds differing."""


def read(path):
    try:
        with open(path, "rb") as f:
            return f.read()
    except OSError as e:
        raise Refusal(f"cannot read {path}: {e.strerror}") from e


def end_record(data, path):
    """The end of central directory record: its offset, and where it says the directory starts."""
    lowest = max(0, len(data) - EOCD_BYTES - 0xFFFF)
    at = data.rfind(EOCD, lowest)
    while at >= 0:
        comment = struct.unpack_from("<H", data, at + 20)[0]
        if at + EOCD_BYTES + comment == len(data):
            break
        at = data.rfind(EOCD, lowest, at)
    if at < 0:
        raise Refusal(f"{path} is not a zip archive: no end of central directory record")
    if at >= 20 and data[at - 20 : at - 16] == ZIP64_LOCATOR:
        raise Refusal(f"{path} is a zip64 archive, which no encri build produces")
    directory = struct.unpack_from("<I", data, at + 16)[0]
    return at, directory


def without_signing_block(data, path):
    """The signed APK as it was before signing: the APK Signing Block out, the pointer moved back."""
    end, directory = end_record(data, path)
    if directory < 24 or data[directory - 16 : directory] != SIG_BLOCK_MAGIC:
        raise Refusal(f"{path} holds no APK Signing Block, so it is not signed with v2 or later")
    size = struct.unpack_from("<Q", data, directory - 24)[0]
    start = directory - size - 8
    if start < 0 or struct.unpack_from("<Q", data, start)[0] != size:
        raise Refusal(f"{path} has a malformed APK Signing Block")
    stripped = bytearray(data[:start] + data[directory:])
    struct.pack_into("<I", stripped, end - (directory - start) + 16, start)
    return bytes(stripped)


def entries(path):
    try:
        with zipfile.ZipFile(path) as archive:
            return archive.infolist(), {i.filename: archive.read(i) for i in archive.infolist()}
    except (zipfile.BadZipFile, OSError, RuntimeError) as e:
        raise Refusal(f"cannot read the entries of {path}: {e}") from e


def v1_signature_files(infos):
    return [
        i.filename
        for i in infos
        if i.filename.startswith("META-INF/") and i.filename.upper().endswith(SIGNATURE_SUFFIXES)
    ]


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def explain(a_path, b_path, skip=frozenset()):
    """Says where two archives differ, entry by entry, so a failure names its cause."""
    a_infos, a_data = entries(a_path)
    b_infos, b_data = entries(b_path)
    a_names = [i.filename for i in a_infos if i.filename not in skip]
    b_names = [i.filename for i in b_infos if i.filename not in skip]
    lines = []
    for name in sorted(set(a_names) - set(b_names))[:SHOWN]:
        lines.append(f"  only in the first:  {name}")
    for name in sorted(set(b_names) - set(a_names))[:SHOWN]:
        lines.append(f"  only in the second: {name}")
    content = [n for n in a_names if n in b_data and a_data[n] != b_data[n]]
    for name in content[:SHOWN]:
        lines.append(
            f"  content differs:    {name} ({len(a_data[name])} and {len(b_data[name])} bytes)"
        )
    if len(content) > SHOWN:
        lines.append(f"  ... and {len(content) - SHOWN} more entries whose content differs")
    if not lines and a_names != b_names:
        lines.append("  the same entries, in a different order")
    if not lines:
        a_by_name = {i.filename: i for i in a_infos}
        for b in b_infos:
            a = a_by_name.get(b.filename)
            if a is None:
                continue
            fields = [
                field
                for field in ("header_offset", "compress_type", "date_time", "extra", "external_attr")
                if getattr(a, field) != getattr(b, field)
            ]
            if fields:
                lines.append(f"  layout differs:     {b.filename} ({', '.join(fields)})")
                if len(lines) >= SHOWN:
                    break
        if not lines:
            lines.append("  every entry is the same; the bytes differ outside them")
    return lines, content


def same(a_path, b_path):
    a, b = read(a_path), read(b_path)
    print(f"{sha256(a)}  {a_path}")
    print(f"{sha256(b)}  {b_path}")
    if a == b:
        print("identical")
        return 0
    lines, _ = explain(a_path, b_path)
    print("DIFFERENT:")
    print("\n".join(lines))
    return 1


LOCAL = 0x04034B50
CENTRAL = 0x02014B50
ALIGNMENT_RECORD = 0xD935


def raw_entries(data, path):
    """Every entry as stored: its central directory record, its local header and its stored bytes.

    Parsed by hand rather than through `zipfile`, which reads the central directory's extra field
    and never the local header's, and the local header's padding is the one thing here that is
    allowed to differ.
    """
    end, directory = end_record(data, path)
    count = struct.unpack_from("<H", data, end + 10)[0]
    entries_ = []
    at = directory
    for _ in range(count):
        if struct.unpack_from("<I", data, at)[0] != CENTRAL:
            raise Refusal(f"{path} has a malformed central directory")
        fixed = data[at + 4 : at + 28]
        csize = struct.unpack_from("<I", data, at + 20)[0]
        nlen, elen, clen = struct.unpack_from("<HHH", data, at + 28)
        tail = data[at + 34 : at + 42]
        local = struct.unpack_from("<I", data, at + 42)[0]
        name = data[at + 46 : at + 46 + nlen]
        rest = data[at + 46 + nlen : at + 46 + nlen + elen + clen]
        at += 46 + nlen + elen + clen
        if struct.unpack_from("<I", data, local)[0] != LOCAL:
            raise Refusal(f"{path}: {name!r} has no local header where the directory says")
        lnlen, lelen = struct.unpack_from("<HH", data, local + 26)
        start = local + 30 + lnlen + lelen
        entries_.append(
            {
                "name": name,
                "central": (fixed, tail, rest),
                "local": (data[local + 4 : local + 26], data[local + 30 : local + 30 + lnlen]),
                "padding": data[local + 30 + lnlen : start],
                "stored": data[start : start + csize],
            }
        )
    trailer = (data[end + 4 : end + 16], data[end + 20 :])
    return entries_, trailer


def is_padding(extra):
    """Zero bytes, as AGP pads, or one alignment record, as `apksigner` pads."""
    if not any(extra):
        return True
    if len(extra) < 6:
        return False
    tag, size = struct.unpack_from("<HH", extra, 0)
    return tag == ALIGNMENT_RECORD and size == len(extra) - 4 and not any(extra[6:])


def signed(signed_path, unsigned_path):
    infos, _ = entries(signed_path)
    v1 = v1_signature_files(infos)
    if v1:
        raise Refusal(
            f"{signed_path} carries a v1 signature ({', '.join(v1[:3])}), which adds entries; "
            "encri's releases are signed with --v1-signing-enabled false"
        )
    stripped = without_signing_block(read(signed_path), signed_path)
    unsigned = read(unsigned_path)
    print(f"{sha256(read(signed_path))}  {signed_path}")
    print(f"{sha256(unsigned)}  {unsigned_path}")
    if stripped == unsigned:
        print("identical: the signed APK is this build, signed")
        return 0
    s_entries, s_trailer = raw_entries(stripped, signed_path)
    u_entries, u_trailer = raw_entries(unsigned, unsigned_path)
    lines = []
    if [e["name"] for e in s_entries] != [e["name"] for e in u_entries]:
        lines.append("  the entries are not the same, or not in the same order")
    else:
        for s_entry, u_entry in zip(s_entries, u_entries):
            name = s_entry["name"].decode("utf-8", "replace")
            for part in ("central", "local", "stored"):
                if s_entry[part] != u_entry[part]:
                    lines.append(f"  {part} differs: {name}")
            if s_entry["padding"] != u_entry["padding"] and not (
                is_padding(s_entry["padding"]) and is_padding(u_entry["padding"])
            ):
                lines.append(f"  local extra field is more than padding: {name}")
    if s_trailer != u_trailer:
        lines.append("  the end of central directory record differs")
    if lines:
        print("DIFFERENT: the signed APK is not this build")
        print("\n".join(lines[:SHOWN]))
        return 1
    print(
        f"identical but for alignment padding: all {len(u_entries)} entries, every stored byte, "
        "header and directory record — the signed APK is this build, signed"
    )
    return 0


def bundle(signed_path, unsigned_path):
    s_infos, s_data = entries(signed_path)
    u_infos, u_data = entries(unsigned_path)
    added = SIGNING_ADDS.union(v1_signature_files(s_infos)).intersection(s_data)
    stray = sorted(SIGNING_ADDS.union(v1_signature_files(u_infos)).intersection(u_data))
    if stray:
        raise Refusal(f"{unsigned_path} is already signed: {', '.join(stray[:3])}")
    if not v1_signature_files(s_infos):
        raise Refusal(f"{signed_path} holds no jar signature, so it is not a signed bundle")
    s_names = [n for n in s_data if n not in added]
    if s_names != list(u_data) or any(s_data[n] != u_data[n] for n in s_names):
        lines, _ = explain(signed_path, unsigned_path, skip=frozenset(added))
        print("DIFFERENT: the signed bundle does not hold this build's entries")
        print("\n".join(lines))
        return 1
    print(f"identical: {len(s_names)} entries, less {len(added)} that signing added")
    return 0


COMMANDS = {"same": same, "signed": signed, "bundle": bundle}


def main(argv):
    if len(argv) != 4 or argv[1] not in COMMANDS:
        print(__doc__.strip().split("\n\n")[1], file=sys.stderr)
        return 2
    try:
        return COMMANDS[argv[1]](argv[2], argv[3])
    except Refusal as e:
        print(f"REFUSED: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
