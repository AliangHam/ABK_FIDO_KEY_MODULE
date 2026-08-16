#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path


NEEDLE = """    // restored from https://github.com/tiann/KernelSU/pull/3031\n    ksu_allow(db, \"init\", \"adb_data_file\", \"file\", ALL);\n    ksu_allow(db, \"init\", \"adb_data_file\", \"dir\", ALL); // #1289\n\n"""
BLOCK = """    // restored from https://github.com/tiann/KernelSU/pull/3031\n    ksu_allow(db, \"init\", \"adb_data_file\", \"file\", ALL);\n    ksu_allow(db, \"init\", \"adb_data_file\", \"dir\", ALL); // #1289\n\n    /* ABK FIDO: allow kernel domain access to the persisted metadata store.\n     * dir write/add_name + file create are required to create the store\n     * file when /metadata is empty (O_CREAT); without them persist fails\n     * with -EACCES until the companion precreates the file. */\n    ksu_allow(db, \"kernel\", \"metadata_file\", \"dir\", \"search\");\n    ksu_allow(db, \"kernel\", \"metadata_file\", \"dir\", \"write\");\n    ksu_allow(db, \"kernel\", \"metadata_file\", \"dir\", \"add_name\");\n    ksu_allow(db, \"kernel\", \"metadata_file\", \"file\", \"create\");\n    ksu_allow(db, \"kernel\", \"metadata_file\", \"file\", \"open\");\n    ksu_allow(db, \"kernel\", \"metadata_file\", \"file\", \"read\");\n    ksu_allow(db, \"kernel\", \"metadata_file\", \"file\", \"write\");\n    ksu_allow(db, \"kernel\", \"metadata_file\", \"file\", \"getattr\");\n\n    /* ABK FIDO: let kernel-domain call_usermodehelper reach system_server\n     * via binder so the companion service can be started from the kernel. */\n    ksu_allow(db, \"kernel\", \"binder_device\", \"chr_file\", \"open\");\n    ksu_allow(db, \"kernel\", \"binder_device\", \"chr_file\", \"read\");\n    ksu_allow(db, \"kernel\", \"binder_device\", \"chr_file\", \"write\");\n    ksu_allow(db, \"kernel\", \"binder_device\", \"chr_file\", \"ioctl\");\n    ksu_allow(db, \"kernel\", \"binder\", \"binder\", \"transfer\");\n    ksu_allow(db, \"kernel\", \"binder\", \"binder\", \"call\");\n    ksu_allow(db, \"kernel\", \"binder\", \"binder\", \"impersonate\");\n\n"""
MARKER = "ABK FIDO: allow kernel domain access to the persisted metadata store."


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: patch_kernelsu_sepolicy_for_abk_fido.py <rules.c>", file=sys.stderr)
        return 1

    path = Path(sys.argv[1])
    text = path.read_text()
    if MARKER in text:
        return 0
    if NEEDLE not in text:
        raise SystemExit(f"injection point not found in {path}")

    path.write_text(text.replace(NEEDLE, BLOCK, 1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
