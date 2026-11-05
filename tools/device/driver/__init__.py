"""Semantic device driver for Free Social acceptance runs."""

from .device import (
    DEFAULT_SERIAL,
    Device,
    DriverError,
    DumpError,
    StepError,
    WallError,
)
from . import policy
from .selectors import (
    Selector,
    by_class,
    by_desc,
    by_id,
    by_text,
    matches,
)
from .xmlnodes import Node, parse_xml

__all__ = [
    "DEFAULT_SERIAL",
    "Device",
    "DriverError",
    "DumpError",
    "Node",
    "Selector",
    "StepError",
    "policy",
    "WallError",
    "by_class",
    "by_desc",
    "by_id",
    "by_text",
    "matches",
    "parse_xml",
]
