"""Prepare a local runtime from the exact APK used for GT hardware validation."""

import argparse
import hashlib
from pathlib import Path
import subprocess
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SOURCE_SHA256 = "cffcf418f579df0cec335f6e2d66fdb790cb831ca1c5323906ac0e949e9d1ffa"
LIBRARIES = ["libFFalconXRServer.so", "libFFalconXRClient.so", "libFFalconXRConfig.so", "libFFalconXRGraphic.so"]


def run(*arguments):
    subprocess.run([str(a) for a in arguments], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--dexlib-jar", type=Path, required=True)
    parser.add_argument("--jdk", type=Path, default=ROOT / ".gradle/codex-toolchain/jdk")
    args = parser.parse_args()
    if hashlib.sha256(args.apk.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise ValueError("APK differs from the artifact validated with GT. Inspect and validate that version first.")
    work = ROOT / "app/build/rayneo-runtime"
    work.mkdir(parents=True, exist_ok=True)
    output = ROOT / "app/src/main/assets/rayneo"
    output.mkdir(parents=True, exist_ok=True)
    run(args.jdk / "bin/javac.exe", "-encoding", "UTF-8", "-cp", args.dexlib_jar,
        "-d", work, ROOT / "scripts/RayNeoDexSubset.java")
    run(args.jdk / "bin/java.exe", "-cp", str(work) + ";" + str(args.dexlib_jar),
        "RayNeoDexSubset", args.apk, work / "classes.dex")
    with zipfile.ZipFile(output / "runtime.jar", "w", zipfile.ZIP_DEFLATED) as archive:
        archive.write(work / "classes.dex", "classes.dex")
    with zipfile.ZipFile(args.apk) as archive:
        for name in LIBRARIES:
            (output / name).write_bytes(archive.read("lib/arm64-v8a/" + name))
    (output / "build-id.txt").write_text(SOURCE_SHA256 + "\n", encoding="ascii")
    print("Prepared local RayNeo runtime", output)


if __name__ == "__main__":
    main()
