# Achievements in Nook (design)

Date: 2026-11-25 · Status: approved by the owner 2026-11-25

## Why
Big, slow goals should not sit in the same list as small tasks. They get their own list ("Achievements"), room for a
longer description, and a lasting record of what was finished.

## Goals
- A fourth, rightmost tab **Achievements**; a Tasks | Achievements toggle in the home-screen widget.
- Optional longer description, readable and editable later.
- Finished achievements stay visible, with their date.

## Non-goals
Progress bars, deadlines, reminders, renaming, reassigning, creating sub-items (all still possible in Todoist).

## Anti-goals
A goal finished by a stray tap; a finished goal that disappears; an update that breaks the three existing tabs.

## Storage (shared with the kiosk, both must agree)
- Todoist project `Achievements` (name changeable in Settings), shared by the same people.
- **Done = a section named `Done`** in that project (matched ignoring case). Ticking MOVES the item there; it is not
  completed. The app creates the section the first time. If two devices each created one, all sections named Done
  count as done and the first by section order is used for new ones.
- The date is the last line of the description, exactly `Done: 25 Nov 2026` (English month, day without zero). An item
  moved to Done by hand has no date line: shown without a date, after the dated ones.
- The date line is never shown as description text and is kept when a description is edited.
- Descriptions are limited to 16,000 characters (Todoist's limit is 16,383; the rest is room for the date line).

## Approaches
1. **Done section + date line** — chosen by the owner: cannot expire, visible to everyone, works offline.
2. Complete in Todoist and read its completed history: catches ticks made in Todoist's own app, but how long a free
   account keeps history and whose completions are readable is undocumented.
3. A log on each device: not shared, lost on reinstall.

Known cost of 1: an item ticked in Todoist's own app is really completed and will not show in Done; finished items
count as open in Todoist (limit 300 per project); two people editing one description at the same moment: the last
one to sync wins.

## App
- **List:** open achievements as in Tasks (sections, assignee, "Anyone"), plus the first line of the description in
  small text. Below them a collapsed row `Done · 7`; tapping it unfolds the finished ones, newest first, with date.
- **Circle** = achieved: moves to Done. Snackbar `Achieved: <name>` + `Undo`, which restores its old section and
  description. The circle of a finished one reopens it (removes the date line, moves it out of Done, no section).
- **Tap on the row** opens details: name, assignee, description (editable), `Save`. Closing with unsaved text asks.
- **Long-press** moves between sections, as in Tasks; Done is not offered there.
- **Add sheet:** name, optional multi-line description, assignee chips (default Anyone). No duplicate check.
- Sub-items and repeating items made in Todoist behave as in Tasks (tick = close). An item whose parent is in Done
  is listed under it.
- **Assignees** are the members of the Achievements project (not those of Shared tasks).
- **No project yet:** only the Achievements tab says `Create a shared Todoist project called "Achievements"`. The other
  tabs, the sync status and sign-in work as before.

## Widget
```
+----------------------------------+
| Tasks 3 | Achievements 2       + |
| o Learn to weld                  |
| o Build the greenhouse           |
| Achieved: Run 10 km - Undo       |
+----------------------------------+
```
- The toggle replaces the title and the Undo button; each widget remembers its choice. Tapping the selected name opens
  Nook on that tab. `+` adds to the selected list with the signed-in person preselected.
- Undo is a line under the list for 10 minutes after a tick.
- Shows achievements assigned to the signed-in person, not Done. Circle = achieved; tapping the name opens its details
  in Nook. Task rows keep working as they do now.
- Without the project the toggle still shows; the Achievements side says what to create.

## Data and sync
- Sync commands gain one: `item_update` with `description` only (and `description` on `item_add`). No other fields.
- Ticking queues `section_add` (if needed), `item_move`, `item_update`, in that order; all offline-safe like the rest.
  If the item is gone, the existing "was already removed" message applies.
- Database version 2 with a real migration (item description, membership of the Achievements project). The pending
  queue and category memory must survive it. After migrating, the next sync is a full one so descriptions arrive.
- Whenever the set of our project ids changes, the next sync is a full one.
- Seeding from completed history is skipped for this list (suggestions are not needed).

## Testing
Unit: date-line parse/format/keep, done grouping and order. Engine against the fake: tick, undo, reopen, offline tick
then restart, two Done sections, missing project, project appearing later, description edit keeps the date line,
migration from a version-1 database with a queued command. UI: four tabs, tap opens details and does not tick, add with
description, Done unfolds, unsaved text asks. Widget: toggle, counts, undo line, tasks unchanged.

## Left to the builder
Icons, exact spacing, sheet layout.
