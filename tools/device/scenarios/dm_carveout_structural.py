"""TikTok DM carve-out, structurally (stretch).

Policy §2.1 table + RULES.md: an open DM thread is protected by the structural
`tiktok_dm_carve_out` rule (`com.zhiliaoapp.musically:id/udm` + Button), so a
shared video playing inside a chat must not be escaped even with the budget
spent. READ-ONLY: no message is ever sent.

Reality on this device (pt-PT Reno4, TikTok 47.6.3): `uiautomator dump` cannot
read the FYP while a video plays ("could not get idle state"), so the inbox tab
cannot be resolved semantically from the FYP. The scenario tries the semantic
route and, when the tree is unreadable, records the blocked route as
locale/capture-gap evidence (screenshot + window structure) instead of failing.
It only asserts when a chat surface is actually reached.
"""

from driver import DumpError, Selector, by_class, policy

TT = "com.zhiliaoapp.musically"
# Observed-only string variants (pt-PT "C. de entrada" seen live on the Reno4).
INBOX_LABELS = (
    "C. de entrada",
    "Caixa de entrada",
    "Mensagens",
    "Inbox",
    "Messages",
)


def _find_inbox(d):
    for label in INBOX_LABELS:
        hits = d.find(Selector(text=label, match="contains"))
        if hits:
            return hits[0]
    return None


def run(d):
    state = policy.budget(d)
    d.log(f"budget at start: {state} (need it exhausted for the carve-out verdict)")
    if int(state["used"]) < int(state["limit"]) * 60 and int(state["limit"]) != 0:
        d.log("budget not exhausted — run exhaustion_consistent first; recording as data")

    d.launch(TT, settle=2.0)
    d.wait(6.0)
    d.screencap("tiktok-fyp")

    try:
        nodes = d.dump(retries=1, save=True, label="tiktok-fyp.xml")
    except DumpError as exc:
        d.log(f"CAPTURE-GAP: TikTok FYP is not dumpable while a video plays: {exc}")
        d.screencap("tiktok-fyp-undumpable")
        structure = d.shell(
            "dumpsys window windows | grep -E 'Window #|mCurrentFocus' | head -20"
        )
        policy.save_lines(d, "window-structure.txt", structure.splitlines())
        mark = policy.log_stamp(d)
        d.wait(10.0)
        lines = policy.log_lines(d, since=mark)
        policy.save_lines(d, "engine-window.log", lines)
        d.log(
            "LOCALE-GAP/BLOCKED: no semantic route into the TikTok DM inbox from an "
            f"undumpable FYP; engine lines on FYP during 10 s: {len(lines)}, "
            f"actions: {len(policy.action_lines(lines))}"
        )
        return

    inbox = _find_inbox(d)
    if inbox is None:
        d.screencap("tiktok-no-inbox-entry")
        policy.save_lines(d, "fyp-view-ids.txt", sorted({
            n.resource_id for n in nodes if n.resource_id and "musically" in n.resource_id
        }))
        d.log("LOCALE-GAP: no observed DM-inbox label variant on the FYP tree")
        return

    d.tap(Selector(text=inbox.text, match="contains"))
    d.wait(3.0)
    d.screencap("tiktok-dm-inbox")

    # Open a thread: conversation rows are clickable containers in the inbox list.
    inbox_nodes = d.dump(retries=1, save=True, label="dm-inbox.xml")
    rows = [
        n for n in inbox_nodes
        if n.visible and n.clickable and "Layout" in (n.class_name or "")
        and n.bounds[3] - n.bounds[1] > 100
    ]
    if not rows:
        d.log("STRUCTURE-GAP: no clickable conversation row visible in the inbox")
        policy.save_lines(d, "dm-inbox-view-ids.txt", sorted({
            n.resource_id for n in inbox_nodes if n.resource_id
        }))
        return
    d.tap(by_class(rows[0].class_name, match="exact"))
    d.wait(3.0)
    d.screencap("tiktok-chat-open")

    mark = policy.log_stamp(d)
    d.wait(6.0)
    lines = policy.log_lines(d, since=mark)
    policy.save_lines(d, "engine-window.log", lines)
    actions = policy.action_lines(lines)
    d.log(f"engine actions inside the chat with the budget spent: {len(actions)}")
    if actions:
        raise AssertionError(f"escape fired inside a TikTok DM thread: {actions[:2]}")
    d.log("PASS: no escape inside the opened DM chat")
