"""YouTube Home Shorts-shelf observer.

Selectors keyed on (from live Reno dumps, pt-PT UI; string variants below are
observed-only — EN from the rule-authoring dumps, PT from pt-yt-home3.xml; add
new locales to the variant lists when actually observed):
  - Home anchor:  com.google.android.youtube:id/tabs_bar (only exists on Home) — locale-proof
  - shelf/header: content-desc == "Shorts" (brand string, unchanged in pt-PT)
  - shelf cards:  content-desc contains "reproduzir Short" (PT) / "play Short" (EN)
  - scroll area:  com.google.android.youtube:id/results (scrollable feed container) — locale-proof

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
# Observed string variants only (see header); a missing locale means
# SHELF_ABSENT_OR_LOCALE, never a guess.
SHORTS_CARD_DESCS = ("reproduzir Short", "play Short")


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
        cards = []
        for desc in SHORTS_CARD_DESCS:
            cards = d.find(by_desc(desc, match="contains"))
            if cards:
                d.log(f"{len(cards)} Shorts shelf cards via desc variant {desc!r}; "
                      f"first: {cards[0].label(70)}")
                break
        d.assert_visible(SHORTS_HEADER)
        d.log("SHELF_PRESENT: YouTube Home exposes a Shorts shelf on this run")
    else:
        d.log("SHELF_ABSENT_OR_LOCALE: no Shorts header/cards after 4 look-scrolls; "
              "absent from the feed OR this locale's strings unobserved — data, not a failure")
        d.save_dump(
            "observation.txt",
            "SHELF_ABSENT_OR_LOCALE: no content-desc='Shorts' header and no card desc "
            f"variant matched ({', '.join(SHORTS_CARD_DESCS)}) after 4 look-scrolls on "
            f"{d.serial}. Absent from feed OR unobserved locale strings.\n",
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
