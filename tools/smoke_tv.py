#!/usr/bin/env python3
"""Small playback smoke test, explicitly restricted to a disposable emulator.

Requires an already installed/running Lume debug APK; reads only its own log tag.
"""
import argparse
import datetime
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", default="emulator-5560")
    parser.add_argument("--channels", default="1,2,3,4,5,6,7,8,9,21,24")
    parser.add_argument("--report", default="app/build/reports/emulator-playback.json")
    parser.add_argument("--variant", default="debug")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Use a disposable emulator, never a physical TV.")

    def adb(*command):
        return subprocess.run([args.adb, "-s", args.serial, *command], check=True, capture_output=True, timeout=20).stdout.decode(errors="replace")

    report = {"checked_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "device": adb("shell", "getprop", "ro.product.model").strip(),
              "android_api": adb("shell", "getprop", "ro.build.version.sdk").strip(),
              "scope": "First rendered frame in Android TV emulator, not a Bravia benchmark or prolonged playback test", "variant": args.variant, "channels": []}
    for number in args.channels.split(","):
        adb("logcat", "-c")
        start = time.monotonic()
        for digit in number:
            adb("shell", "input", "keyevent", str(7 + int(digit)))
        outcome = {"number": int(number), "first_frame": False}
        while time.monotonic() - start < 35:
            log = adb("logcat", "-d", "-s", "LumePlayer:I")
            if "first_frame" in log:
                outcome["first_frame"] = True
                outcome["observed_seconds_including_numeric_timeout"] = round(time.monotonic() - start, 2)
                break
            error = re.search(r"error=([A-Z0-9_]+)", log)
            if error:
                outcome["error"] = error.group(1)
                break
            time.sleep(1)
        # A few seconds expose immediate playback errors after the first frame.
        time.sleep(2)
        error = re.search(r"error=([A-Z0-9_]+)", adb("logcat", "-d", "-s", "LumePlayer:I"))
        if error:
            outcome["post_first_frame_error"] = error.group(1)
        report["channels"].append(outcome)
        print(json.dumps(outcome), flush=True)
    memory = adb("shell", "dumpsys", "meminfo", "it.lumetv.app")
    total = re.search(r"TOTAL PSS:\s*(\d+)", memory)
    if total:
        report["final_total_pss_kb"] = int(total.group(1))
    output = ROOT / args.report
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
