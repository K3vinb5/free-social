"""Semantic selectors. Nodes are matched on id / text / content-desc / class.

No coordinates live here: a selector describes *what* a node is, and the
device layer resolves its bounds from a fresh dump at action time.

String selectors carry **observed** locale variants only (see
`tools/device/README.md`): a variant list is the English resource value plus the
translated value(s) from `app/src/main/res/values-<locale>/strings.xml`. Matching
falls back to a diacritic-folded comparison, so a dump that strips accents still
matches the resource value without a duplicate variant.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

from .xmlnodes import Node

MATCH_MODES = ("exact", "contains", "regex")

# One observed string, or the observed variants of the same label across locales.
Wanted = str | tuple[str, ...]


@dataclass(frozen=True)
class Selector:
    id: str | None = None
    text: Wanted | None = None
    desc: Wanted | None = None
    cls: str | None = None
    match: str = "exact"  # exact | contains | regex
    index: int = 0  # nth match in document order

    def __post_init__(self) -> None:
        if self.match not in MATCH_MODES:
            raise ValueError(f"match must be one of {MATCH_MODES}, got {self.match!r}")
        for field in (self.text, self.desc):
            if isinstance(field, tuple) and not field:
                raise ValueError("an empty variant list can never match")
        if not any((self.id, self.text, self.desc, self.cls)):
            raise ValueError("selector needs at least one of id/text/desc/cls")

    def __str__(self) -> str:
        parts = []
        if self.id:
            parts.append(f"id={self.id!r}")
        if self.text:
            parts.append(f"text={_show(self.text)}")
        if self.desc:
            parts.append(f"desc={_show(self.desc)}")
        if self.cls:
            parts.append(f"cls={self.cls!r}")
        desc = " & ".join(parts)
        if self.match != "exact":
            desc += f" [{self.match}]"
        if self.index:
            desc += f" #{self.index}"
        return f"<{desc}>"


def _show(wanted: Wanted) -> str:
    if isinstance(wanted, str):
        return repr(wanted)
    return "(" + " | ".join(repr(w) for w in wanted) + ")"


def _deaccent(value: str) -> str:
    return "".join(
        c for c in unicodedata.normalize("NFKD", value) if not unicodedata.combining(c)
    )


def _match_field(
    value: str,
    wanted: str,
    mode: str,
    *,
    short_id: bool = False,
    accent_fold: bool = False,
) -> bool:
    if not value:
        return False
    if mode == "exact":
        if value == wanted:
            return True
        if accent_fold and _deaccent(value) == _deaccent(wanted):
            return True
        # Allow "clips_tab" to mean "com.instagram.android:id/clips_tab".
        return short_id and not wanted.endswith("/") and value.rsplit("/", 1)[-1] == wanted
    if mode == "contains":
        if wanted in value:
            return True
        return accent_fold and _deaccent(wanted) in _deaccent(value)
    try:
        return re.search(wanted, value) is not None
    except re.error as exc:  # bad user regex -> loud, not silent
        raise ValueError(f"invalid regex {wanted!r}: {exc}") from exc


def _match_any(value: str, wanted: Wanted, mode: str) -> bool:
    """Match one node label against one observed variant or a list of them."""
    variants = (wanted,) if isinstance(wanted, str) else wanted
    return any(_match_field(value, v, mode, accent_fold=True) for v in variants)


def matches(node: Node, sel: Selector) -> bool:
    if sel.id is not None and not _match_field(node.resource_id, sel.id, sel.match, short_id=True):
        return False
    if sel.text is not None and not _match_any(node.text, sel.text, sel.match):
        return False
    if sel.desc is not None and not _match_any(node.content_desc, sel.desc, sel.match):
        return False
    if sel.cls is not None and not _match_field(node.class_name, sel.cls, sel.match):
        return False
    return True


def by_id(value: str, match: str = "exact", index: int = 0) -> Selector:
    return Selector(id=value, match=match, index=index)


def by_text(value: str, match: str = "exact", index: int = 0) -> Selector:
    return Selector(text=value, match=match, index=index)


def by_texts(*values: str, match: str = "exact", index: int = 0) -> Selector:
    """One label observed in several locales, e.g. by_texts("Mode", "Modo")."""
    return Selector(text=tuple(values), match=match, index=index)


def by_desc(value: str, match: str = "exact", index: int = 0) -> Selector:
    return Selector(desc=value, match=match, index=index)


def by_descs(*values: str, match: str = "exact", index: int = 0) -> Selector:
    """One content-desc observed in several locales."""
    return Selector(desc=tuple(values), match=match, index=index)


def by_class(value: str, match: str = "exact", index: int = 0) -> Selector:
    return Selector(cls=value, match=match, index=index)


def select_target(nodes: list[Node], pick: str = "first") -> Node:
    """Choose one node among already-matched candidates.

    ``pick="first"`` keeps dump order (the screen's own copy of a label). ``pick="last"`` takes
    the candidate **lowest on screen**, which is the dialog's copy when the same label also
    exists on the screen behind the dialog (measured on the "use built-in rules" confirm, whose
    two matches differ only by their bounds). Choosing by bounds keeps the primitive semantic:
    callers name the instance they want, never coordinates.
    """
    if not nodes:
        raise IndexError("no candidate nodes to choose from")
    if pick == "first":
        return nodes[0]
    if pick == "last":
        return max(nodes, key=lambda n: n.bounds[1])
    raise ValueError(f"unknown pick={pick!r} (expected 'first' or 'last')")
