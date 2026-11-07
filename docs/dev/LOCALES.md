# Localization (app only)

The Android app follows the **device locale** automatically — Android picks the best matching
`res/values-*/` folder. There is no in-app language picker, by design. The website stays
English-only and is out of scope.

## Where strings live

| Folder | Locale | Notes |
| --- | --- | --- |
| `app/src/main/res/values/` | English (default) | The only complete source of truth. Every user-facing string lives here. |
| `app/src/main/res/values-pt/` | pt-PT | Portuguese (Portugal). Hand-translated, informal "tu". |
| `app/src/main/res/values-es/` | es-ES | Spanish (Spain). Hand-translated, "tú". |

Not translated on purpose: `Log.*` messages, dump JSON payloads, rule ids/surfaces, preference
keys, intent extras, and MIME types. Dump `screen`/`surface` names stay stable English so a dump
captured in Lisbon opens the same in Madrid; only the labels shown to the user are localized
(`CaptureSurfaces.nameRes`).

## Adding a locale

1. Copy `values/strings.xml` to `values-<bcp47>/strings.xml` (e.g. `values-fr/`).
2. Translate every `<string>` that does not have `translatable="false"` and every `<plurals>` item.
3. Keep placeholders byte-identical: `%1$s`, `%1$d`, `%2$d` … may be reordered for grammar but not
   renamed or dropped. Use `<plurals>` for count-bearing copy (the English file has 11).
4. Run `./gradlew test`. `LocaleResourcesTest` fails until every key exists and every
   `<plurals>` quantity (`one`/`other`) is present with matching placeholders.
5. Screenshot the hub, schedule and FLASH copy on a device set to that locale.

## Formatting rules

- Clock times go through `SettingsRepository.formatClock(minutes, locale)` →
  `core/LocaleFormat.clock`, which uses `DateTimeFormatter.ofLocalizedTime(SHORT)`: 12-hour locales
  get "10:30 PM", 24-hour locales "22:30".
- Dates/timestamps use `LocaleFormat.dateTime(millis, zone, locale)`
  (`DateFormat.getDateTimeInstance(MEDIUM, SHORT)`).
- The schedule time picker asks the system for the 12/24-hour preference
  (`android.text.format.DateFormat.is24HourFormat`), never a hardcoded `is24Hour = true`.
- Translation strings must not hardcode "h"/"m" semantics if the locale needs different units —
  `schedule_duration` is fully translatable.

## Guard rails

- `LocaleResourcesTest` — key/quantity completeness and placeholder parity for every locale.
- `HardcodedTextLintTest` — style lint over `ui/`: fails when a Compose `Text("…")`,
  `text = "…"`, `title = "…"`, `contentDescription = "…"` literal (or a `Toast.makeText` literal)
  is reintroduced. Log tags and keys are intentionally outside the pattern list.
