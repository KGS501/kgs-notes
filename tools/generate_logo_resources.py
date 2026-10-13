#!/usr/bin/env python3
"""Convert the supplied SVG into Android vectors with proportional safe padding.

Run after changing logo/logo.svg. The SVG remains the source of the artwork;
launcher and splash variants only change the canvas and size of the mark.
"""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)


def android(name: str) -> str:
    return f"{{{ANDROID}}}{name}"


def generate() -> None:
    svg = ET.parse(ROOT / "logo/logo.svg").getroot()
    x, y, width, height = map(float, svg.attrib["viewBox"].split())
    if x != 0 or y != 0 or width != height:
        raise ValueError("The logo needs a square viewBox starting at zero.")
    paths = list(svg)
    if not paths or any(node.tag.split("}")[-1] != "path" for node in paths):
        raise ValueError("Only path artwork is supported; preserve other SVG features before converting.")
    colors = {re.fullmatch(r"fill:(#[0-9a-fA-F]{6})", node.attrib.get("style", "")).group(1).upper() for node in paths}
    if len(colors) != 1:
        raise ValueError("The logo must have one solid mark color for the themed icon layer.")
    resources = ROOT / "app/src/main/res"
    colors_xml = ET.Element("resources")
    # Reuse the supplied paths for the white-on-yellow brand variant.
    ET.SubElement(colors_xml, "color", {"name": "logo_mark"}).text = "#FFFFFF"
    ET.SubElement(colors_xml, "color", {"name": "logo_background"}).text = colors.pop()
    write(resources / "values/logo_colors.xml", colors_xml)
    # Use viewport coordinates rather than dp insets: the artwork then keeps
    # identical proportions in small Settings icons and larger launcher icons.
    # The rounded square mark fits inside both the 66dp adaptive-icon safe
    # circle and the 192dp splash safe circle, including its outermost corners.
    for name, canvas, mark in [("ic_launcher_foreground", 108, 44), ("kgs_splash_logo", 288, 144), ("kgs_logo", 64, 64)]:
        vector = ET.Element("vector", {
            android("width"): f"{canvas}dp", android("height"): f"{canvas}dp",
            android("viewportWidth"): str(canvas), android("viewportHeight"): str(canvas),
        })
        scale = mark / width
        # The missing lower-right corner puts the visual center above and left
        # of the bounds. Launcher marks use a subtler 2% correction; the splash
        # keeps its 4% correction, and the uncontained sidebar mark has none.
        optical_fraction = {"ic_launcher_foreground": .02, "kgs_splash_logo": .04, "kgs_logo": 0}[name]
        optical_offset = mark * optical_fraction
        inset = (canvas - mark) / 2 + optical_offset
        outer = ET.SubElement(vector, "group", {
            android("scaleX"): f"{scale:.10f}", android("scaleY"): f"{scale:.10f}",
            android("translateX"): str(inset), android("translateY"): str(inset),
        })
        for node in paths:
            match = re.fullmatch(r"translate\((-?[\d.]+)[ ,]+(-?[\d.]+)\)", node.attrib.get("transform", ""))
            if not match:
                raise ValueError("Unsupported SVG transform; preserve it before converting.")
            group = ET.SubElement(outer, "group", {
                android("translateX"): match.group(1), android("translateY"): match.group(2),
            })
            ET.SubElement(group, "path", {
                android("fillColor"): "@color/logo_mark", android("pathData"): node.attrib["d"],
            })
        write(resources / f"drawable/{name}.xml", vector)


def write(path: Path, root: ET.Element) -> None:
    ET.indent(root, space="    ")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('<?xml version="1.0" encoding="utf-8"?>\n'
                    '<!-- Generated from logo/logo.svg by tools/generate_logo_resources.py. -->\n'
                    + ET.tostring(root, encoding="unicode") + '\n')


if __name__ == "__main__":
    generate()
