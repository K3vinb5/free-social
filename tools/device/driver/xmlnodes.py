"""Parse `uiautomator dump` XML into flat, reusable node records."""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass

_BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


@dataclass(frozen=True)
class Node:
    index: int
    class_name: str
    package: str
    resource_id: str
    text: str
    content_desc: str
    bounds: tuple[int, int, int, int]  # left, top, right, bottom
    clickable: bool = False
    enabled: bool = True
    selected: bool = False
    scrollable: bool = False
    long_clickable: bool = False

    @property
    def left(self) -> int:
        return self.bounds[0]

    @property
    def top(self) -> int:
        return self.bounds[1]

    @property
    def right(self) -> int:
        return self.bounds[2]

    @property
    def bottom(self) -> int:
        return self.bounds[3]

    @property
    def width(self) -> int:
        return max(0, self.right - self.left)

    @property
    def height(self) -> int:
        return max(0, self.bottom - self.top)

    @property
    def area(self) -> int:
        return self.width * self.height

    @property
    def visible(self) -> bool:
        return self.width > 0 and self.height > 0

    @property
    def center(self) -> tuple[int, int]:
        return (self.left + self.width // 2, self.top + self.height // 2)

    def label(self, limit: int = 80) -> str:
        """Short human label for evidence logs (never used for matching)."""
        bits = []
        if self.resource_id:
            bits.append(self.resource_id.rsplit("/", 1)[-1])
        if self.text:
            bits.append(f"text={self.text!r}")
        if self.content_desc:
            bits.append(f"desc={self.content_desc!r}")
        if not bits:
            bits.append(self.class_name.rsplit(".", 1)[-1] or "node")
        out = " ".join(bits)
        return out if len(out) <= limit else out[: limit - 3] + "..."


def parse_bounds(value: str) -> tuple[int, int, int, int]:
    m = _BOUNDS_RE.search(value or "")
    if not m:
        return (0, 0, 0, 0)
    return tuple(int(g) for g in m.groups())  # type: ignore[return-value]


def _bool(value: str | None) -> bool:
    return (value or "").lower() == "true"


def parse_xml(xml_text: str) -> list[Node]:
    """Return every <node> in document order, deepest children included."""
    root = ET.fromstring(xml_text)
    nodes: list[Node] = []
    for order, el in enumerate(root.iter("node")):
        nodes.append(
            Node(
                index=order,
                class_name=el.get("class", ""),
                package=el.get("package", ""),
                resource_id=el.get("resource-id", ""),
                text=el.get("text", "") or "",
                content_desc=el.get("content-desc", "") or "",
                bounds=parse_bounds(el.get("bounds", "")),
                clickable=_bool(el.get("clickable")),
                enabled=_bool(el.get("enabled", "true")),
                selected=_bool(el.get("selected")),
                scrollable=_bool(el.get("scrollable")),
                long_clickable=_bool(el.get("long-clickable")),
            )
        )
    return nodes
