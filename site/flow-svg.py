"""Make the inline SVG for the "desktop" section of site/index.html.

Run from the repository root:

    python3 site/flow-svg.py > /tmp/flow.svg

Then paste the output over the <svg id="flow"> element in site/index.html.

Graphviz makes the layout. This script removes the colors and fonts that
Graphviz writes, because site/site.css sets them from the page theme. It also
gives each node a `data-node` name and each edge a `data-from` and `data-to`
name, which site/site.js uses to show the neighbours of a node.
"""

import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SVG = "http://www.w3.org/2000/svg"
ET.register_namespace("", SVG)


def q(tag: str) -> str:
    return f"{{{SVG}}}{tag}"


source = Path(__file__).with_name("flow.dot")
svg_text = subprocess.run(
    ["dot", "-Tsvg", str(source)], check=True, capture_output=True, text=True
).stdout
root = ET.fromstring(svg_text)

# Graphviz writes a size in points. The page sets the size with CSS.
for attr in ("width", "height"):
    root.attrib.pop(attr, None)
root.set("id", "flow")
root.set("role", "group")
root.set("aria-label", "How the desktop app, gx, and your files connect")

graph = root.find(q("g"))
graph.remove(graph.find(q("title")))
background = graph.find(q("polygon"))  # Absent when bgcolor is transparent.
if background is not None:
    graph.remove(background)

for group in list(graph):
    kind = group.get("class")
    title = group.find(q("title"))
    name = title.text.replace("&#45;", "-") if title is not None else ""
    if title is not None:
        group.remove(title)
    group.attrib.pop("id", None)
    if kind == "node":
        label = " ".join(t.text for t in group.iter(q("text")))
        group.set("data-node", name)
        group.set("tabindex", "0")
        group.set("role", "button")
        group.set("aria-label", label)
    elif kind == "edge":
        source_name, target_name = name.split("->")
        group.set("data-from", source_name)
        group.set("data-to", target_name)
        group.set("aria-hidden", "true")

for element in root.iter():
    for attr in ("fill", "stroke", "font-family", "font-size", "stroke-width"):
        element.attrib.pop(attr, None)

# An arrowhead is the only polygon in an edge. It needs its own class, so
# that CSS can fill it.
for edge in graph.iter(q("g")):
    if edge.get("class") == "edge":
        for polygon in edge.iter(q("polygon")):
            polygon.set("class", "head")

out = ET.tostring(root, encoding="unicode")
out = re.sub(r"\s*xml:space=\"preserve\"", "", out)
out = out.replace("ns0:", "").replace(":ns0", "")
sys.stdout.write(out + "\n")
