#!/usr/bin/env python3
"""Render the store icon from the app's adaptive launcher icon.

Reads the adaptive icon of the app, resolves its background colour and its foreground vector
drawable, and composes them as a launcher does: both layers are 108 dp squares, of which the
centre 72 dp is the visible area. That visible square is written as a 512 x 512 PNG, without
the mask a launcher would cut it with. The path data of the drawable is handed to the renderer
unchanged, so nothing is redrawn here; a drawable that uses anything this script does not
translate is refused instead of being rendered differently from the app.

Usage, from anywhere, with resvg-py installed at the version pinned below:

    python fastlane/render_icon.py            # write the icon
    python fastlane/render_icon.py --check    # fail if the committed icon is not what is rendered

fastlane/README.md says how to set up the renderer.
"""

import hashlib
import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

import resvg_py

# The PNG bytes depend on the renderer, so its version is part of the input.
RENDERER_VERSION = "0.5.0"

REPOSITORY = Path(__file__).resolve().parent.parent
RESOURCES = REPOSITORY / "app" / "src" / "main" / "res"
ADAPTIVE_ICON = RESOURCES / "mipmap-anydpi-v26" / "ic_launcher.xml"
OUTPUT = REPOSITORY / "fastlane" / "metadata" / "android" / "en-US" / "images" / "icon.png"

LAYER_DP = 108  # the size of each layer of an adaptive icon
VISIBLE_DP = 72  # the centre of a layer that a launcher shows
SIZE_PX = 512

ANDROID = "{http://schemas.android.com/apk/res/android}"

# Attributes of a <path> in a vector drawable that have a direct equivalent in SVG.
PATH_ATTRIBUTES = {
    "pathData",
    "fillColor",
    "fillType",
    "strokeColor",
    "strokeWidth",
    "strokeLineCap",
}
PATH_DATA = re.compile(r"[MmLlHhVvCcSsQqTtAaZz0-9eE.,+\-\s]+")
FILL_RULES = {"nonZero": "nonzero", "evenOdd": "evenodd"}
LINE_CAPS = {"butt", "round", "square"}


def android_attributes(element, allowed):
    """Return the android: attributes of an element by local name, refusing any other."""
    attributes = {}
    for name, value in element.attrib.items():
        local = name.removeprefix(ANDROID)
        if not name.startswith(ANDROID) or local not in allowed:
            raise SystemExit(f"unsupported attribute {name} on <{element.tag}>")
        attributes[local] = value
    return attributes


def paint(colour):
    """Translate an Android colour (#RGB, #ARGB, #RRGGBB, #AARRGGBB) into SVG (colour, opacity)."""
    digits = colour.removeprefix("#")
    if not colour.startswith("#") or len(digits) not in (3, 4, 6, 8):
        raise SystemExit(f"unsupported colour {colour}")
    if len(digits) in (3, 4):
        digits = "".join(digit * 2 for digit in digits)
    if len(digits) == 6:
        digits = "FF" + digits
    alpha = int(digits[:2], 16)
    if alpha == 0:
        return "none", None
    return "#" + digits[2:].upper(), None if alpha == 255 else f"{alpha / 255:.6f}"


def colour_resource(name):
    """Find the value of a colour resource in the default values directory."""
    for values_file in sorted((RESOURCES / "values").glob("*.xml")):
        for element in ElementTree.parse(values_file).getroot().iter("color"):
            if element.get("name") == name:
                return element.text.strip()
    raise SystemExit(f"colour resource {name} not found")


def layer_reference(icon, layer, kind):
    """Return the resource name that a layer of the adaptive icon refers to."""
    element = icon.find(layer)
    if element is None or len(element):
        raise SystemExit(f"the adaptive icon needs a <{layer}> that only names a drawable")
    reference = android_attributes(element, {"drawable"}).get("drawable", "")
    if not reference.startswith(f"@{kind}/"):
        raise SystemExit(f"the {layer} layer is {reference}, expected a @{kind}/ resource")
    return reference.removeprefix(f"@{kind}/")


def svg_path(element):
    """Translate one <path> of a vector drawable into an SVG <path> with the same geometry."""
    attributes = android_attributes(element, PATH_ATTRIBUTES)
    data = attributes.get("pathData", "")
    if not PATH_DATA.fullmatch(data):
        raise SystemExit(f"unsupported pathData {data!r}")
    fill, fill_opacity = paint(attributes.get("fillColor", "#00000000"))
    svg = [f'd="{data}"', f'fill="{fill}"']
    if fill_opacity:
        svg.append(f'fill-opacity="{fill_opacity}"')
    if fill != "none":
        svg.append(f'fill-rule="{FILL_RULES[attributes.get("fillType", "nonZero")]}"')
    if "strokeColor" in attributes:
        stroke, stroke_opacity = paint(attributes["strokeColor"])
        cap = attributes.get("strokeLineCap", "butt")
        if cap not in LINE_CAPS:
            raise SystemExit(f"unsupported strokeLineCap {cap}")
        width = float(attributes.get("strokeWidth", "0"))  # 0 is Android's default: no stroke
        svg.append(f'stroke="{stroke}" stroke-width="{width:g}"')
        svg.append(f'stroke-linecap="{cap}"')
        if stroke_opacity:
            svg.append(f'stroke-opacity="{stroke_opacity}"')
    return "<path " + " ".join(svg) + "/>"


def compose():
    """Build the SVG of the visible square: the background colour under the foreground paths."""
    icon = ElementTree.parse(ADAPTIVE_ICON).getroot()
    background, opacity = paint(colour_resource(layer_reference(icon, "background", "color")))
    if background == "none" or opacity:
        raise SystemExit("the background colour must be opaque")
    foreground_name = layer_reference(icon, "foreground", "drawable")
    foreground_file = RESOURCES / "drawable" / (foreground_name + ".xml")
    foreground = ElementTree.parse(foreground_file).getroot()
    if foreground.tag != "vector":
        raise SystemExit(f"{foreground_file.name} is not a vector drawable")
    size = android_attributes(foreground, {"width", "height", "viewportWidth", "viewportHeight"})
    paths = []
    for element in foreground:
        if element.tag != "path":
            raise SystemExit(f"unsupported element <{element.tag}> in {foreground_file.name}")
        paths.append(svg_path(element))
    # A layer is stretched to the 108 dp square whatever its own size, so the viewport is too.
    scale_x = LAYER_DP / float(size["viewportWidth"])
    scale_y = LAYER_DP / float(size["viewportHeight"])
    inset = (LAYER_DP - VISIBLE_DP) / 2
    return "\n".join(
        [
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{SIZE_PX}" height="{SIZE_PX}"'
            f' viewBox="{inset:g} {inset:g} {VISIBLE_DP} {VISIBLE_DP}">',
            f'<rect width="{LAYER_DP}" height="{LAYER_DP}" fill="{background}"/>',
            f'<g transform="scale({scale_x:g} {scale_y:g})">',
            *paths,
            "</g>",
            "</svg>",
        ]
    )


def main():
    if sys.argv[1:] not in ([], ["--check"]):
        raise SystemExit(__doc__)
    if resvg_py.__version__ != RENDERER_VERSION:
        installed = resvg_py.__version__
        raise SystemExit(f"resvg-py {installed} is installed, {RENDERER_VERSION} is pinned")
    png = bytes(resvg_py.svg_to_bytes(svg_string=compose(), skip_system_fonts=True))
    digest = hashlib.sha256(png).hexdigest()
    name = OUTPUT.relative_to(REPOSITORY).as_posix()
    if sys.argv[1:] == ["--check"]:
        if not OUTPUT.is_file() or OUTPUT.read_bytes() != png:
            raise SystemExit(f"{name} is not the rendered icon (sha256 {digest})")
        print(f"{name} is the rendered icon, sha256 {digest}")
    else:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_bytes(png)
        print(f"wrote {name}, {len(png)} bytes, sha256 {digest}")


if __name__ == "__main__":
    main()
