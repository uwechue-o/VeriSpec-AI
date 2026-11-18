"""Capture the Bundle Detail charts from a connected device for the manuals.

The manuals' app-screen figures are real screenshots, not redrawn charts. This
script takes them: with the VeriSpec app open on a bundle's Detail screen, it
scrolls the screen, reads the on-screen labels through `uiautomator dump`, and
crops each chart out of an `adb exec-out screencap` image by those labels'
bounds. It then writes

    <prefix>-throughput.png   the "Throughput per iteration" chart
    <prefix>-telemetry.png    the "Telemetry over time" charts, two by two

into the output directory, ready for tools/report/src/main/resources/docs/.

Usage (device connected, app on the bundle's Detail screen, scrolled to top):

    python tools/report/scripts/capture_app_charts.py <out-dir> <prefix>

Needs adb on PATH and Pillow. On Git Bash for Windows, MSYS path conversion is
disabled for the adb calls so /sdcard paths survive.
"""

import io
import os
import re
import subprocess
import sys
import time

from PIL import Image

ENV = dict(os.environ, MSYS_NO_PATHCONV="1")

# Chart title -> the unit caption drawn directly under it. Requiring the unit
# tells a chart title apart from the same words in the Hardware telemetry table.
CHARTS = {
    "throughput": ("Throughput per iteration", None),
    "core": ("Fastest core clock", "MHz"),
    "gpu": ("GPU clock", "MHz"),
    "battery": ("Battery temperature", "°C"),
    "headroom": ("Thermal headroom", "0 = cool, 1 = severe"),
}
TELEMETRY = ["core", "gpu", "battery", "headroom"]
STATUS_BAR, NAV_BAR = 110, 2060  # Pixel 6 at 1080x2400; content lies between these rows.


def adb(*args, binary=False):
    out = subprocess.run(["adb", *args], capture_output=True, env=ENV)
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def visible_text():
    """(text, left, top, right, bottom) for every labelled view on screen."""
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    nodes = []
    for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if m.group(1):
            nodes.append((m.group(1), *map(int, m.groups()[1:])))
    return nodes


def find_chart(nodes, title, unit):
    """Bounds (top, bottom) of one chart, or None unless it is fully on screen."""
    for i, n in enumerate(nodes):
        if n[0] != title:
            continue
        if unit is not None and not any(u[0] == unit and 0 <= u[2] - n[4] < 40 for u in nodes):
            continue  # same words, but not a chart title
        axis = "iteration" if unit is None else "seconds since run start"
        end = next((e for e in nodes[i + 1:] if e[0] == axis), None)
        if end and n[2] > STATUS_BAR and end[4] < NAV_BAR:
            return n[2] - 12, end[4] + 18
    return None


def capture():
    crops = {}
    for _ in range(40):
        nodes = visible_text()
        shot = Image.open(io.BytesIO(adb("exec-out", "screencap", "-p", binary=True))).convert("RGB")
        for key, (title, unit) in CHARTS.items():
            if key not in crops:
                bounds = find_chart(nodes, title, unit)
                if bounds:
                    crops[key] = shot.crop((0, bounds[0], shot.width, bounds[1]))
        if len(crops) == len(CHARTS) or any(n[0] == "Actions" and n[2] < 1500 for n in nodes):
            break
        adb("shell", "input", "swipe", "540", "1500", "540", "900", "400")
        time.sleep(0.8)
    return crops


def grid(images, columns=2):
    width = max(i.width for i in images)
    height = max(i.height for i in images)
    rows = (len(images) + columns - 1) // columns
    sheet = Image.new("RGB", (width * columns, height * rows), images[0].getpixel((5, 5)))
    for n, image in enumerate(images):
        sheet.paste(image, ((n % columns) * width, (n // columns) * height))
    return sheet


def main():
    out, prefix = sys.argv[1], sys.argv[2]
    crops = capture()
    missing = [k for k in CHARTS if k not in crops]
    if "throughput" in missing:
        sys.exit(f"throughput chart not found on screen; missing {missing}")
    crops["throughput"].save(os.path.join(out, f"{prefix}-throughput.png"), optimize=True)
    telemetry = [crops[k] for k in TELEMETRY if k in crops]
    if telemetry:
        grid(telemetry).save(os.path.join(out, f"{prefix}-telemetry.png"), optimize=True)
    print(f"{prefix}: captured {sorted(crops)}; not on this bundle: {missing}")


if __name__ == "__main__":
    main()
