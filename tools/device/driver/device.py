"""Semantic, Playwright-style driver for Android apps over adb.

Everything is resolved from a fresh `uiautomator dump` at action time. No
screen coordinates are stored anywhere; `input tap/swipe` only ever receives
numbers computed from node bounds or the screen size.

Transport is pinned with `adb -s <host:port>` and every command is bounded by
`timeout` seconds (default 15).
"""

from __future__ import annotations

import re
import subprocess
import time
import unicodedata
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

from .selectors import Selector, matches
from .xmlnodes import Node, parse_xml

DEFAULT_SERIAL = "LAN-IP-REDACTED:37161"
REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_EVIDENCE_ROOT = REPO_ROOT / "reverse" / "runtime-reno"
REMOTE_DUMP = "/sdcard/freesocial-driver-dump.xml"
REMOTE_SHOT = "/sdcard/freesocial-driver-shot.png"

# Consent / login / permission walls. Never interact with these: abort WALL.
# Strong markers may appear as labels inside longer UI text; weak markers only
# count as a whole button label, otherwise feed captions/ads ("Sign up...")
# would be mistaken for a wall.
WALL_STRONG = (
    "iniciar sessao",
    "iniciar sesion",
    "criar nova conta",
    "criar conta",
    "crear cuenta",
    "crear una cuenta",
    "continuar como",
    "fortfahren als",
    "continuer comme",
    "aceitar tudo",
    "aceitar todos",
    "aceptar todo",
    "aceptar todo y continuar",
    "tout accepter",
    "alles akzeptieren",
    "alle akzeptieren",
    "concordo com",
    "termos e politicas",
    "termos de servico",
    "terminos y condiciones",
    "condicions do servizo",
    "politica de privacidade",
    "politique de confidentialite",
    "datenschutzrichtlinie",
    "accept all",
    "allow all",
    "se connecter",
    "creer un compte",
    "konto erstellen",
    "usar a localizacao",
    "usar la ubicacion",
    "utiliser la localisation",
    "standort verwenden",
    "permitir acesso",
    "permitir o acceso",
    "permite o acceso",
    "autorizar acesso",
    "autoriser l acces",
    "zugriff erlauben",
)
WALL_WEAK = (
    "sign up",
    "log in",
    "login",
    "permitir",
    "entrar",
    "continuar",
    "acceder",
    "acceso",
    "anmelden",
    "registrieren",
    "connexion",
    "zulassen",
    "erlauben",
    "fortfahren",
)
# Structural login-wall signal: a visible password input, locale-independent via the
# field's resource-id/hint words (see _guard). Never interact with such a screen.
PASSWORD_FIELD_WORDS = (
    "password",
    "senha",
    "palavra-passe",
    "palavra passe",
    "contrasinal",
    "contrasena",
    "passwort",
    "kennwort",
    "mot de passe",
)


class DriverError(RuntimeError):
    """Base class for driver failures."""


class DumpError(DriverError):
    """uiautomator could not produce a usable hierarchy."""


class WallError(DriverError):
    """A consent/login/permission wall is on screen; scenario aborted."""


class StepError(DriverError):
    """A selector/action step could not be completed."""


def _deaccent(value: str) -> str:
    return "".join(
        c for c in unicodedata.normalize("NFKD", value) if not unicodedata.combining(c)
    )


def _safe(value: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_.-]+", "-", value).strip("-")
    return cleaned[:80] or "shot"


class Device:
    """One device session, one evidence directory, one step counter."""

    def __init__(
        self,
        serial: str | None = None,
        evidence_dir: str | Path | None = None,
        timeout: int = 15,
        adb: str = "adb",
        scenario: str = "scenario",
    ) -> None:
        self.serial = serial or DEFAULT_SERIAL
        self.timeout = timeout
        self.adb = adb
        self.scenario = scenario
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        self.evidence_dir = Path(
            evidence_dir or (DEFAULT_EVIDENCE_ROOT / f"driver-{stamp}" / scenario)
        ).resolve()
        self.evidence_dir.mkdir(parents=True, exist_ok=True)
        self.log_path = self.evidence_dir / "steps.log"
        self._step = 0
        self._shot = 0
        self._last_nodes: list[Node] | None = None
        self._last_xml: str = ""
        self._screen: tuple[int, int] | None = None
        self._started = datetime.now()

    # ---------------------------------------------------------------- plumbing
    def _adb(self, *args: str, timeout: int | None = None) -> subprocess.CompletedProcess[str]:
        cmd = [self.adb, "-s", self.serial, *map(str, args)]
        try:
            return subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                errors="replace",
                timeout=timeout or self.timeout,
            )
        except FileNotFoundError as exc:
            raise DriverError("adb binary not found (install platform-tools)") from exc
        except subprocess.TimeoutExpired as exc:
            raise DriverError(
                f"adb timed out after {timeout or self.timeout}s: {' '.join(cmd[-3:])}"
            ) from exc

    def shell(self, *args: str, timeout: int | None = None, check: bool = False) -> str:
        proc = self._adb("shell", *args, timeout=timeout)
        out = (proc.stdout or "") + (proc.stderr or "")
        if check and proc.returncode != 0:
            raise DriverError(f"shell failed ({proc.returncode}): {' '.join(args)}: {out.strip()}")
        return proc.stdout if proc.stdout is not None else ""

    def _record(self, what: str, result: str) -> None:
        self._step += 1
        line = f"STEP {self._step}: {what} -> {result}"
        print(line, flush=True)
        with self.log_path.open("a", encoding="utf-8") as fh:
            fh.write(line + "\n")

    def log(self, message: str) -> None:
        line = f"LOG: {message}"
        print(line, flush=True)
        with self.log_path.open("a", encoding="utf-8") as fh:
            fh.write(line + "\n")

    def evidence(self, path: str | Path) -> Path:
        p = Path(path).resolve()
        line = f"EVIDENCE: {p}"
        print(line, flush=True)
        with self.log_path.open("a", encoding="utf-8") as fh:
            fh.write(line + "\n")
        return p

    # ------------------------------------------------------------------- dump
    def dump(
        self,
        retries: int = 2,
        save: bool = False,
        label: str | None = None,
    ) -> list[Node]:
        """Dump + parse the hierarchy. Retries, then fails with evidence.

        Playing-video screens return "could not get idle state"; we retry twice
        and then raise DumpError after writing a screencap plus the last good
        dump, rather than acting on stale bounds.
        """
        last_err = "unknown error"
        for attempt in range(retries + 1):
            try:
                out = self.shell("uiautomator", "dump", REMOTE_DUMP)
                if "dumped to" not in out.lower():
                    raise DumpError(out.strip() or "uiautomator dump produced no file")
                xml = self.shell("cat", REMOTE_DUMP)
                nodes = parse_xml(xml)
                if not nodes:
                    raise DumpError("empty uiautomator hierarchy")
                self._last_nodes, self._last_xml = nodes, xml
                if save:
                    name = label or f"dump-{datetime.now().strftime('%H%M%S')}"
                    if not name.endswith(".xml"):
                        name += ".xml"
                    self.save_dump(_safe(name), xml)
                return nodes
            except (DriverError, ValueError, ET.ParseError) as exc:  # bad/partial XML
                last_err = str(exc)
                if attempt < retries:
                    time.sleep(1.2)
        # Fallback: screenshot the failure, preserve last known good hierarchy.
        shot = self.screencap("dump-failed")
        if self._last_nodes is not None and self._last_xml:
            self.save_dump("last-good-dump.xml", self._last_xml)
        raise DumpError(
            f"uiautomator dump failed after {retries + 1} attempts ({last_err}); "
            f"screenshot={shot}; last-good-dump={self.evidence_dir / 'last-good-dump.xml'}"
        )

    def save_dump(self, name: str, xml: str | None = None) -> Path:
        path = self.evidence_dir / name
        path.write_text(xml if xml is not None else self._last_xml, encoding="utf-8")
        return path

    # ------------------------------------------------------------------ find
    def find(self, selector: Selector, nodes: list[Node] | None = None) -> list[Node]:
        nodes = nodes if nodes is not None else self.dump()
        return [n for n in nodes if n.visible and matches(n, selector)]

    def find_one(self, selector: Selector, nodes: list[Node] | None = None) -> Node:
        found = self.find(selector, nodes)
        if len(found) <= selector.index:
            self._fail(f"no node matches {selector} (found {len(found)} visible nodes)")
        return found[selector.index]

    def _fail(self, message: str) -> None:
        """Attach evidence to a failure, then raise."""
        shot = ""
        try:
            shot = str(self.screencap("failure"))
        except DriverError:
            pass
        try:
            self.dump(retries=0, save=True, label="failure-dump.xml")
        except DriverError:
            pass
        raise StepError(f"{message}; screenshot={shot}")

    def _guard(self, nodes: list[Node]) -> None:
        strong: list[tuple[str, str]] = []
        weak: dict[str, str] = {}
        for node in nodes:
            if not node.visible:
                continue
            blob = _deaccent(
                " ".join(filter(None, (node.resource_id, node.text, node.content_desc)))
            ).lower()
            if node.resource_id and any(w in _deaccent(node.resource_id).lower() for w in PASSWORD_FIELD_WORDS):
                strong.append(("password-field-id", node.resource_id[:70]))
            elif ("edittext" in (node.class_name or "").lower() or "textfield" in (node.class_name or "").lower()) and any(
                w in blob for w in PASSWORD_FIELD_WORDS
            ):
                strong.append(("password-field", (node.text or node.content_desc or node.resource_id or "")[:70]))
            for field in (node.text, node.content_desc):
                low = _deaccent(field).lower().strip()
                if not low or len(low) > 60 or "\n" in field:
                    continue
                if "patrocinado" in low or "sponsored" in low:
                    continue
                for marker in WALL_STRONG:
                    if marker in low:
                        strong.append((marker, field.strip()[:70]))
                for marker in WALL_WEAK:
                    if low == marker:
                        strong.append((marker, field.strip()[:70]))
                    elif len(low) <= 25 and marker in low:
                        weak.setdefault(marker, field.strip()[:70])
        if not strong and len(weak) >= 2:
            strong = [(m, t) for m, t in weak.items()]
        if strong:
            marker, sample = strong[0]
            shot = self.screencap("wall")
            self.save_dump("wall-dump.xml", self._last_xml)
            raise WallError(
                f"consent/login wall detected (marker={marker!r}, text={sample!r}); "
                f"nothing was tapped; screenshot={shot}"
            )

    # -------------------------------------------------------------- actions
    def tap(self, selector: Selector, nodes: list[Node] | None = None) -> Node:
        nodes = nodes if nodes is not None else self.dump()
        self._guard(nodes)
        node = self.find_one(selector, nodes)
        x, y = node.center
        proc = self._adb("shell", "input", "tap", str(x), str(y))
        if proc.returncode != 0 or "error" in (proc.stdout + proc.stderr).lower():
            self._fail(f"input tap failed at {x},{y} for {selector}")
        self._record(f"tap {selector}", f"ok center={x},{y} node={node.label(50)}")
        return node

    def long_press(self, selector: Selector, duration_ms: int = 1000) -> Node:
        nodes = self.dump()
        self._guard(nodes)
        node = self.find_one(selector, nodes)
        x, y = node.center
        proc = self._adb(
            "shell", "input", "swipe", str(x), str(y), str(x), str(y), str(duration_ms)
        )
        if proc.returncode != 0:
            self._fail(f"long press failed at {x},{y} for {selector}")
        self._record(f"long_press {selector}", f"ok center={x},{y} {duration_ms}ms")
        return node

    def screen_size(self) -> tuple[int, int]:
        if self._screen is None:
            out = self.shell("wm", "size")
            m = re.search(r"(\d+)x(\d+)", out)
            if not m:
                self._fail(f"cannot read wm size from {out!r}")
            self._screen = (int(m.group(1)), int(m.group(2)))
        return self._screen

    def _direction_points(
        self, rect: tuple[int, int, int, int], direction: str, ratio: float
    ) -> tuple[tuple[int, int], tuple[int, int]]:
        left, top, right, bottom = rect
        cx, cy = (left + right) // 2, (top + bottom) // 2
        w, h = max(1, right - left), max(1, bottom - top)
        dx, dy = int(w * ratio / 2), int(h * ratio / 2)
        if direction == "up":
            return (cx, cy + dy), (cx, cy - dy)
        if direction == "down":
            return (cx, cy - dy), (cx, cy + dy)
        if direction == "left":
            return (cx + dx, cy), (cx - dx, cy)
        if direction == "right":
            return (cx - dx, cy), (cx + dx, cy)
        raise ValueError(f"direction must be up/down/left/right, got {direction!r}")

    def swipe(
        self,
        direction: str = "up",
        container: Selector | None = None,
        times: int = 1,
        ratio: float = 0.7,
        duration_ms: int = 400,
        from_selector: Selector | None = None,
        to_selector: Selector | None = None,
    ) -> int:
        """Swipe between runtime-resolved bounds; returns number of swipes."""
        if from_selector is not None and to_selector is not None:
            nodes = self.dump()
            self._guard(nodes)
            start = self.find_one(from_selector, nodes).center
            end = self.find_one(to_selector, nodes).center
            for _ in range(times):
                self._adb(
                    "shell", "input", "swipe",
                    *map(str, (*start, *end, duration_ms)),
                )
        else:
            if container is not None:
                nodes = self.dump()
                self._guard(nodes)
                node = self.find_one(container, nodes)
                rect = node.bounds
                where = f"container={container}"
            else:
                w, h = self.screen_size()
                rect = (0, 0, w, h)
                where = "screen"
            start, end = self._direction_points(rect, direction, ratio)
            for _ in range(times):
                self._adb(
                    "shell", "input", "swipe",
                    *map(str, (*start, *end, duration_ms)),
                )
            self._record(
                f"swipe {direction} x{times} in {where}",
                f"ok {start}->{end} ({duration_ms}ms)",
            )
            return times
        self._record(
            f"swipe {from_selector} -> {to_selector} x{times}",
            f"ok {start}->{end}",
        )
        return times

    def launch(
        self,
        package: str | None = None,
        component: str | None = None,
        settle: float = 1.0,
    ) -> str:
        if component is None:
            if not package:
                raise ValueError("launch() needs package or component")
            out = self.shell("cmd", "package", "resolve-activity", "--brief", package)
            lines = [ln.strip() for ln in out.splitlines() if "/" in ln]
            if not lines:
                self._fail(f"cannot resolve launcher activity for {package}: {out.strip()}")
            component = lines[-1]
        # The device shell expands `$` in activity names (e.g. Shell$HomeActivity),
        # so escape it before adb joins the command line.
        remote_component = component.replace("$", "\\$")
        proc = self._adb("shell", "am", "start", "-W", "-n", remote_component)
        out = (proc.stdout or "") + (proc.stderr or "")
        if proc.returncode != 0 or "Error" in out:
            self._fail(f"am start failed for {component}: {out.strip()[:200]}")
        if settle:
            time.sleep(settle)
        self._record(f"launch {component}", "ok")
        return component

    def back(self) -> None:
        self._adb("shell", "input", "keyevent", "KEYCODE_BACK")
        self._record("back", "ok")

    def home(self) -> None:
        self._adb("shell", "input", "keyevent", "KEYCODE_HOME")
        self._record("home", "ok")

    def wait(self, seconds: float) -> None:
        time.sleep(max(0.0, seconds))

    def current_package(self) -> str:
        out = self.shell(
            "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -4"
        )
        m = re.search(r"\bu0 ([A-Za-z0-9_.]+)/", out)
        if m:
            return m.group(1)
        out2 = self.shell("dumpsys activity activities | grep -m1 topResumedActivity")
        m2 = re.search(r"\bu0 ([A-Za-z0-9_.]+)/", out2)
        return m2.group(1) if m2 else ""

    # ---------------------------------------------------------------- waits
    def wait_for(
        self,
        selector: Selector,
        timeout: float | None = None,
        recover: bool = True,
        poll: float = 0.6,
    ) -> Node:
        """Poll for a selector; on timeout back out once and retry once."""

        def attempt(budget: float) -> Node | None:
            deadline = time.monotonic() + budget
            while time.monotonic() < deadline:
                try:
                    nodes = self.dump(retries=0)
                except DumpError:
                    self.wait(poll)
                    continue
                self._guard(nodes)
                found = self.find(selector, nodes)
                if len(found) > selector.index:
                    return found[selector.index]
                self.wait(poll)
            return None

        budget = self.timeout if timeout is None else timeout
        node = attempt(budget)
        if node is not None:
            self._record(f"wait_for {selector}", f"ok {node.label(50)}")
            return node
        if not recover:
            self._fail(f"{selector} not visible within {budget:.0f}s")
        pkg = self.current_package()
        self.log(f"{selector} not found within {budget:.0f}s (foreground={pkg or '?'}); "
                 "backing out once and retrying")
        self.back()
        self.wait(1.0)
        retry_budget = min(8.0, budget)
        node = attempt(retry_budget)
        if node is None:
            self._fail(
                f"{selector} still not visible after back-out + {retry_budget:.0f}s retry"
            )
        self._record(f"wait_for {selector}", f"ok after back-out {node.label(50)}")
        return node

    def assert_visible(self, selector: Selector, timeout: float = 0) -> Node:
        node = self.wait_for(selector, timeout=timeout) if timeout else self.find_one(selector)
        self._record(f"assert_visible {selector}", f"PASS {node.label(50)}")
        return node

    def assert_gone(
        self, selector: Selector, after: float = 0, timeout: float | None = None
    ) -> None:
        if after:
            self.wait(after)
        budget = self.timeout if timeout is None else timeout
        deadline = time.monotonic() + budget
        while True:
            found = self.find(selector)
            if not found:
                self._record(f"assert_gone {selector}", "PASS")
                return
            if time.monotonic() >= deadline:
                self._fail(f"{selector} still visible after {budget:.0f}s")
            self.wait(0.6)

    # -------------------------------------------------------------- evidence
    def screencap(self, name: str) -> Path:
        self._shot += 1
        path = self.evidence_dir / f"{self._shot:02d}-{_safe(name)}.png"
        last = "unknown"
        for attempt in range(3):
            proc = self._adb("shell", "screencap", "-p", REMOTE_SHOT)
            if proc.returncode != 0:
                last = (proc.stderr or proc.stdout or "").strip() or f"exit {proc.returncode}"
                self.wait(0.8)
                continue
            pull = self._adb("pull", REMOTE_SHOT, str(path))
            if pull.returncode == 0 and path.exists() and path.stat().st_size > 0:
                self._adb("shell", "rm", "-f", REMOTE_SHOT)
                self.evidence(path)
                return path
            last = (pull.stderr or "").strip() or "pull produced no file"
            self.wait(0.8)
        raise DriverError(f"screencap failed for {name!r}: {last}")

    def logcat_since(self, tag: str, marker: str | None = None, lines: int = 2000) -> Path:
        """Write a bounded logcat excerpt containing `tag` (and `marker`)."""
        raw = self.shell("logcat", "-d", "-v", "time", "-t", str(lines))
        term = tag.lower()
        keep = [
            ln for ln in raw.splitlines()
            if term in ln.lower() and (marker is None or marker.lower() in ln.lower())
        ]
        stamp = datetime.now().strftime("%H%M%S")
        path = self.evidence_dir / f"logcat-{_safe(tag)}-{stamp}.txt"
        header = (
            f"# logcat excerpt tag={tag!r} marker={marker!r} "
            f"at={datetime.now().isoformat(timespec='seconds')}\n"
            f"# matched {len(keep)} of {len(raw.splitlines())} lines\n"
        )
        path.write_text(header + "\n".join(keep) + ("\n" if keep else ""), encoding="utf-8")
        self._record(f"logcat_since {tag}", f"{len(keep)} lines -> {path.name}")
        return self.evidence(path)

    def app_settings_read(self, package: str = "dev.k3vinb5.freesocial") -> dict[str, str]:
        """Read-only peek at shared_prefs/settings.xml via `run-as`."""
        proc = self._adb(
            "shell", "run-as", package, "cat", "shared_prefs/settings.xml"
        )
        raw = proc.stdout or ""
        if proc.returncode != 0 or "<map" not in raw:
            self.log(f"app_settings_read {package}: unavailable ({(proc.stderr or '').strip()[:80]})")
            return {}
        import xml.etree.ElementTree as ET

        values: dict[str, str] = {}
        try:
            root = ET.fromstring(raw)
        except ET.ParseError as exc:
            self.log(f"app_settings_read {package}: parse error {exc}")
            return {}
        for el in root:
            name = el.get("name")
            if not name:
                continue
            # Typed prefs (<int>/<long>/<boolean>) carry their value in the
            # `value` attribute; only <string> uses element text.
            values[name] = el.text if el.text is not None else el.get("value", "")
        self._record(f"app_settings_read {package}", f"{len(values)} keys (read-only)")
        return values

    def pid(self, package: str) -> str:
        return self.shell("pidof", package).strip()

    def elapsed(self) -> float:
        return (datetime.now() - self._started).total_seconds()
