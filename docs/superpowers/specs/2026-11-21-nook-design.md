# Nook — Android app for Groceries, Shopping and Tasks (design)

Date: 2026-11-21 · Status: draft for owner review

## Why

Two people in one household share three Todoist projects — **Groceries**, **Shopping** and **Shared tasks** — and both
use Android. In the Todoist app those lists are buried two menu levels deep, and the grocery categories (Todoist
*sections*) only appear when you open the project itself; a quick filter view flattens them. Both dislike this.
One of them tests software for a living: if anything behaves differently from what they expect, they will stop using it. So the
bar is not "has features", it is "every tap does the obvious thing, every time, including with bad signal".

Todoist stays the storage (sync between phones, backups, an existing kiosk display keeps working).
This app is only a better front end.

## Goals

- Three tabs in a bottom navigation bar — Groceries, Shopping, Tasks — and nothing else in the app chrome.
- A floating **+** button, bottom right above the content, that adds to whichever tab is open.
- Groceries grouped by food category, Shopping grouped by shop (IKEA, Senukai, DEPO…), both = Todoist sections.
- Typing suggests known items; a known item lands in its remembered category automatically.
- Tasks can be assigned to a specific person (either person) or left for anyone.
- Works in a shop with poor signal: no lost ticks, no duplicate items.

## Non-goals

- No other Todoist projects, filters, labels, priorities, comments, recurring-task editing, notifications or
  widgets (v1).
- No item renaming/editing in the app (rare; done in Todoist). Exception: moving an item to another section.
- No backend of our own, no Pi involvement, no Play Store listing.
- No change to the kiosk (it keeps showing flat lists; grouping it is a possible later, separate change).

## Anti-goals (would make it a failure even if it "works")

- An item that appears ticked on the phone but comes back later (lost write).
- A second "Milk" because a retry after a dropped connection was applied twice.
- A tap that hits the wrong item (list jumping under the finger during a refresh).
- Any screen that needs more than one level of navigation to reach a list.
- Silent failures: if something did not reach Todoist, the user must be able to see that.

## Constraints

- Kotlin, Jetpack Compose, Material 3. Their Android phones (minSdk to be chosen by the builder;
  26+ is fine).
- Todoist **free plan**, API v1 (`https://api.todoist.com/api/v1`), Bearer token.
- **Secrets:** each person pastes their own Todoist personal API token (Todoist → Settings → Integrations →
  Developer) into the app once. Stored encrypted on the phone (Android Keystore-backed). Never in the repo, logs,
  crash output or the APK. Ticks/additions are therefore attributed to whoever did them.
- Project names are configurable in the app's sign-in/settings screen, defaulting to exactly
  `Groceries`, `Shopping`, `Shared tasks` (the names the kiosk uses).
- Distribution (owner decision): signed release APK, first install by hand; afterwards **Nook updates itself from
  GitHub Releases** of the **public** repo `nook` (the code holds no secrets). On start (at most once a day) the app
  checks the latest release; if newer, a dismissible "Update available → Install" banner downloads the APK and hands
  it to Android's installer (REQUEST_INSTALL_PACKAGES; Android asks to confirm). Every release is signed with the
  same key, created in the first milestone and kept outside the repo; the owner is told where to back it up.
- The kiosk and this app edit the same projects concurrently; the app must cope
  with items changing underneath it.

## Approaches considered

1. **Per-action REST calls** (`POST /tasks`, `POST /tasks/{id}/close`, list calls to refresh). Simplest to read,
   but a retry after an unknown outcome (signal drops mid-request) can create duplicates; offline queue and
   dedupe would be hand-rolled.
2. **Todoist Sync endpoint** (`POST /api/v1/sync`) — **chosen by the owner.** Writes are *commands* each with a
   client-generated `uuid`; Todoist applies a given uuid once, so replaying a batch after a dropped connection is
   safe. New items use a `temp_id`, mapped to the real id in the response (`temp_id_mapping`). Reads are
   incremental via `sync_token` (one request returns changed projects, sections, items, collaborators). This is
   how Todoist's own clients work and it makes offline natural: queue commands locally, flush as one batch.
3. Dropped: Flutter / web app in a wrapper (owner chose native Kotlin); own backend on the Pi (owner chose
   Todoist storage).

## Design

### Screens

```
┌─────────────────────────┐
│ Groceries     ⟳ synced  │   top bar: tab name + sync status
│ DAIRY                   │
│ ○ Milk                  │
│ ○ Yogurt                │
│ FRUIT & VEG             │
│ ○ Bananas               │
│ OTHER                   │   items with no section, always last
│ ○ Batteries        (+)  │   floating + bottom right
├────────┬────────┬───────┤
│Groceries│Shopping│ Tasks │
└────────┴────────┴───────┘
```

- **Groceries / Shopping:** open items grouped under their section headers, in Todoist's section order; items
  within a section in Todoist order. Items without a section in an **Other** group at the bottom. Empty sections
  are hidden.
- **Tasks:** open tasks of Shared tasks, each showing its assignee (a person's name or "Anyone") and the
  due date if Todoist has one. Sections in Shared tasks, if any, are shown the same way; otherwise one flat list.
- **Tick off:** tap the circle (or the row) → item disappears immediately, a snackbar "Milk ticked off · Undo"
  shows for ~5 s. Undo reopens it in Todoist (`item_uncomplete`) and it returns to the same place.
  Several quick ticks: each gets undone individually; the snackbar shows the latest.
  - *Repeating tasks* (builder's/assistant's call): ticking moves them to their next date in Todoist, so they stay
    on the list showing the new date; the snackbar says "Next: Mon 24 Nov" with **no Undo** (undoing would need a
    due-date edit, which the app never does).
  - *Sub-items* (call): shown indented under their parent. Ticking a parent with open sub-items first asks
    "Also ticks 3 sub-items?"; Undo reopens the parent and those sub-items.
- **Move:** long-press an item → bottom sheet listing the project's sections (+ "Other" for no section) → tap one
  → item moves (`item_move` with `section_id`, or `project_id` for no section) and the category memory is updated.
  Drag-and-drop is explicitly deferred. In Tasks, Move works the same if Shared tasks has sections (no memory
  involved); without sections there is no long-press action.
- **Pull down to refresh** on every tab. Also refresh on app open/resume and every 30 s while visible.
- **Sync status** in the top bar: "Synced", "Syncing…", "Not synced yet (N changes)" when offline/failed, and a
  tappable error state (e.g. token rejected → go to sign-in). Never a silent failure.

### Adding (the + button)

Opens a bottom sheet with a text field focused and the keyboard up.

- **Groceries / Shopping:** as the user types, suggestions appear under the field from the category memory:
  `Milk · Dairy`, `Mint · Fruit & veg`. Matching: case-insensitive, diacritic-insensitive (so `pien` matches
  `Pienas`), prefix of any word first, then contains; ranked by how often the item was used, recent first.
  - Tap a suggestion → added to its remembered section, sheet stays open for the next item (shopping lists are
    entered in bursts), field cleared.
  - Press enter/Add on text that matches a known item exactly (case/diacritic-insensitive) → same as tapping it.
  - New text → a row of section chips appears ("Dairy", "Bakery", …, "Other", "+ New category"); pick one to add.
    "+ New category" creates a Todoist section in that project (`section_add`) and uses it. In Shopping this is how
    a new shop (e.g. "DEPO") is added.
  - If the same item is already open on the list, don't add a second one: show "Milk is already on the list" and
    highlight it.
- **Tasks:** text field with suggestions from earlier task names (no section memory needed, and **no** "already on
  the list" check — two "Call plumber" tasks for different people are fine), then a row of
  assignee chips: each collaborator of Shared tasks + "Anyone" (default). Optional due date is **not** in v1.
- Item text is stored exactly as typed (trimmed); Lithuanian characters are fine. App UI language: English.

### Category memory

A local table `name_key → (section name, project, use count, last used)` where `name_key` is the item text
lowercased, trimmed, diacritics stripped. Keyed by **section name**, not id, so it survives a section being
deleted and recreated; if the remembered section no longer exists, fall back to asking.

Fed from three sources, all automatic:
1. Every sync: each open item seen in Groceries/Shopping with a section records (name → section). This includes
   what the other person added, so in practice both phones learn together.
2. Every add or move done in the app.
3. Seeding: on first sign-in and then occasionally, read completed items of Groceries/Shopping via
   `GET /api/v1/tasks/completed/by_completion_date` (date windows of ≤ 3 months, paged) going back as far as the
   account allows (unknown whether the free plan limits this; the app must work with zero history).

Latest observation wins for the section (a move in Todoist re-teaches it). Suggestions also include names from the
memory that have no section (Shopping items without a shop).

### Sync engine

- Local database (Room) holds: projects, sections, items, collaborators of the three projects, the `sync_token`,
  the pending command queue, and the category memory.
- The UI only reads the local database. User actions update the local database immediately (optimistic) and
  append a command to the queue. A worker flushes the queue with `POST /api/v1/sync` (`commands` +
  `sync_token`, `resource_types` limited to what's needed) — so one round trip both sends changes and receives
  the latest state.
- Commands used (and no others): `item_add`, `item_complete` / `item_close` (builder picks the right one for
  non-recurring vs recurring), `item_uncomplete`, `item_move`, `section_add`.
- Each command keeps its uuid forever until acknowledged; retries resend the same uuid. Commands are sent in
  order. A per-command error from `sync_status` (e.g. item deleted elsewhere) drops that command, reverts the
  local optimistic change and shows a short message ("Eggs was already removed").
- Network flush runs on: every user action, app resume, the 30 s timer, pull-to-refresh, and when connectivity
  returns (WorkManager with a network constraint so it also completes if the app is closed).
- Conflicts: the server is the truth after each sync, except that items with unacknowledged local commands keep
  their local state until the commands are acknowledged.
- List stability: a refresh must never move rows under a finger. Incoming changes are applied with stable keys
  (item id / temp id) so Compose animates them instead of jumping.

### Sign-in / settings screen

Only reachable when there is no token or the token is rejected, plus via a small overflow item. Fields: token
(paste), the three project names (prefilled), a "Test" button that does a sync and shows which projects/
collaborators were found. Sign out clears the token and the local database.

### People

Assignees come from the Shared tasks project's collaborators (shared project). Display their Todoist names. The
signed-in user is shown as their own name (not "Me") so both phones show the same words.

## Testing

- Unit tests (JVM) for: name normalisation and suggestion ranking; category memory updates; command queue
  (ordering, retry with same uuid, temp-id mapping, per-command error revert); grouping/sorting of items into
  sections incl. Other.
- Sync engine tested against a **fake Todoist sync server** (in-process, implements the subset above and can
  simulate: dropped response after applying, duplicate uuid replay, item deleted elsewhere, invalid token, 429).
- Compose UI tests on the emulator for: tab switching, + adds to the current tab, tick + undo, long-press move,
  suggestion tap, duplicate warning, offline badge.
- Nothing in tests or development uses the real Todoist account or a real token.
- Final check by both users on real phones.

## Left to the builder

Package name, module layout, HTTP/JSON libraries, Room schema details, colours/typography (follow Material 3 with
dynamic colour; dark mode follows the system), exact refresh/backoff numbers within the spirit above, minSdk.

## Calls made by the assistant (owner may override)

- App UI in English; item text kept as typed. Ticked items disappear (kept as completed in Todoist).
- Long-press → "Move to" sheet instead of drag-and-drop (drag deferred).
- Add sheet stays open after adding, for bursts of items.
- Refresh on open/resume, pull-down and every 30 s while visible; offline changes queue with a visible badge.
- Repeating tasks: tick moves to next date, no Undo. Sub-items indented; ticking a parent confirms first.
- Tasks: no duplicate-name check; due dates shown but not set in v1.
- Category memory keyed by normalised name → section name, learned from every sync, add, move and completed history.

## Repo

`~/Documents/github/nook`, public on GitHub (needed for self-updates).
