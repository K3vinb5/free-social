"""YouTube Home Shorts-shelf observer.

Selectors keyed on (from live Reno dumps, pt-PT UI):
  - Home anchor:  com.google.android.youtube:id/tabs_bar (only exists on Home)
  - shelf/header: content-desc == "Shorts" (locale-independent brand string)
  - shelf cards:  content-desc contains "reproduzir Short"
  - scroll area:  com.google.android.youtube:id/results (scrollable feed container)

This is an observer: it only documents whether the Shorts shelf is present on
Home and how the feed behaves after two swipes. It never taps a Short.

YouTube resumes on whatever screen was last open (often the watch player), so
the scenario first backs out to Home using the tabs bar as the anchor.
"""

from driver import by_desc, by_id

PKG = "com.google.android.youtube"
HOME_TABS = by_id("com.google.android.youtube:id/tabs_bar")
FEED = by_id("com.google.android.youtube:id/results")
SHORTS_HEADER = by_desc("Shorts", match="exact")
SHORTS_CARD = by_desc("reproduzir Short", match="contains")


def _ensure_home(d):
    for attempt in range(3):
        if d.find(HOME_TABS):
            return
        d.log(f"Home tabs not visible (attempt {attempt + 1}); backing out once")
        d.back()
        d.wait(1.2)
    d.wait_for(HOME_TABS, timeout=15, recover=False)


def run(d):
    d.launch(PKG)
    _ensure_home(d)
    d.wait_for(FEED, timeout=15)
    d.screencap("yt-home")

    header = None
    for attempt in range(4):
        hits = d.find(SHORTS_HEADER)
        if hits:
            header = hits[0]
            d.log(f"Shorts header found after {attempt} look-scroll(s)")
            break
        d.log(f"Shorts header not visible; feed swipe {attempt + 1}/4 to look for it")
        d.swipe(container=FEED, direction="up", times=1)
        d.wait(0.8)

    # Observer semantics: the Shorts shelf may legitimately be absent from the
    # personalised Home feed. Record that as data (with evidence), but still
    # require the driver to see a live Home feed and never leave YouTube.
    if header is not None:
        cards = d.find(SHORTS_CARD)
        if cards:
            d.log(f"{len(cards)} Shorts shelf cards visible; first: {cards[0].label(70)}")
        d.assert_visible(SHORTS_HEADER)
        d.log("SHELF_PRESENT: YouTube Home exposes a Shorts shelf on this run")
    else:
        d.log("SHELF_ABSENT: no Shorts header/cards after 4 look-scrolls; "
              "recorded as observer data, not a failure")
        d.save_dump(
            "observation.txt",
            "SHELF_ABSENT: no content-desc='Shorts' and no 'reproduzir Short' cards "
            f"after 4 look-scrolls on {d.serial}\n",
        )
    d.assert_visible(FEED)
    d.screencap("yt-shorts-shelf-observation")

    d.swipe(container=FEED, direction="up", times=2)
    d.screencap("yt-after-2-feed-swipes")

    pkg = d.current_package()
    d.log(f"foreground package after 2 swipes: {pkg or 'unknown'}")
    if pkg != PKG:
        raise AssertionError(f"left YouTube after swiping the feed (foreground={pkg!r})")
    d.assert_visible(FEED)
    d.screencap("yt-still-in-youtube")
