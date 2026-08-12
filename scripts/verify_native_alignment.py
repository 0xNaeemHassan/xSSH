#!/usr/bin/env python3
"""Verify that packaged native libraries support Android 16 KiB page sizes."""

from __future__ import annotations

import argparse
import hashlib
import struct
import sys
import zipfile
from pathlib import Path


PAGE_SIZE = 16 * 1024
PT_LOAD = 1
EXPECTED_ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}


def program_alignments(data: bytes) -> list[int]:
    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise ValueError("not an ELF binary")
    elf_class = data[4]
    endian = {1: "<", 2: ">"}.get(data[5])
    if endian is None:
        raise ValueError("unsupported ELF byte order")

    if elf_class == 1:
        ph_offset = struct.unpack_from(f"{endian}I", data, 28)[0]
        ph_entry_size = struct.unpack_from(f"{endian}H", data, 42)[0]
        ph_count = struct.unpack_from(f"{endian}H", data, 44)[0]
        type_offset, align_offset, align_format = 0, 28, "I"
    elif elf_class == 2:
        ph_offset = struct.unpack_from(f"{endian}Q", data, 32)[0]
        ph_entry_size = struct.unpack_from(f"{endian}H", data, 54)[0]
        ph_count = struct.unpack_from(f"{endian}H", data, 56)[0]
        type_offset, align_offset, align_format = 0, 48, "Q"
    else:
        raise ValueError(f"unsupported ELF class {elf_class}")

    alignments: list[int] = []
    for index in range(ph_count):
        entry = ph_offset + index * ph_entry_size
        if entry + ph_entry_size > len(data):
            raise ValueError("truncated ELF program-header table")
        segment_type = struct.unpack_from(f"{endian}I", data, entry + type_offset)[0]
        if segment_type == PT_LOAD:
            alignment = struct.unpack_from(
                f"{endian}{align_format}", data, entry + align_offset
            )[0]
            alignments.append(alignment)
    if not alignments:
        raise ValueError("ELF has no loadable segments")
    return alignments


def zip_data_offset(apk: Path, entry: zipfile.ZipInfo) -> int:
    with apk.open("rb") as source:
        source.seek(entry.header_offset + 26)
        lengths = source.read(4)
    if len(lengths) != 4:
        raise ValueError(f"truncated ZIP local header for {entry.filename}")
    filename_length, extra_length = struct.unpack("<HH", lengths)
    return entry.header_offset + 30 + filename_length + extra_length


def verify(apk: Path) -> None:
    failures: list[str] = []
    seen_termux_abis: set[str] = set()
    with zipfile.ZipFile(apk) as archive:
        entries = sorted(
            (
                entry
                for entry in archive.infolist()
                if entry.filename.startswith("lib/")
                and entry.filename.endswith(".so")
            ),
            key=lambda entry: entry.filename,
        )
        if not entries:
            raise ValueError("APK contains no native-library entries")

        for entry in entries:
            parts = entry.filename.split("/")
            abi = parts[1]
            if entry.filename.endswith("/libtermux.so"):
                seen_termux_abis.add(abi)
            binary = archive.read(entry)
            alignments = program_alignments(binary)
            offset = zip_data_offset(apk, entry)
            digest = hashlib.sha256(binary).hexdigest()
            if min(alignments) < PAGE_SIZE:
                failures.append(
                    f"{entry.filename}: PT_LOAD alignment {min(alignments)} < {PAGE_SIZE}"
                )
            if entry.compress_type != zipfile.ZIP_STORED:
                failures.append(f"{entry.filename}: native library is compressed")
            if offset % PAGE_SIZE != 0:
                failures.append(
                    f"{entry.filename}: ZIP data offset {offset} is not {PAGE_SIZE}-aligned"
                )
            print(
                f"{entry.filename}: ELF={min(alignments)} ZIP={offset % PAGE_SIZE} "
                f"sha256={digest}"
            )

    missing = EXPECTED_ABIS - seen_termux_abis
    if missing:
        failures.append(f"libtermux.so is missing ABIs: {', '.join(sorted(missing))}")
    if failures:
        raise ValueError("\n".join(failures))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apks", type=Path, nargs="+")
    args = parser.parse_args()
    failed = False
    for apk in args.apks:
        try:
            if not apk.is_file():
                raise ValueError(f"APK not found: {apk}")
            print(f"Verifying native alignment for {apk.name}...")
            verify(apk)
            print(f"native alignment verification for {apk.name}: OK")
        except (OSError, ValueError, zipfile.BadZipFile) as error:
            print(f"native alignment verification failed for {apk.name}: {error}", file=sys.stderr)
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
