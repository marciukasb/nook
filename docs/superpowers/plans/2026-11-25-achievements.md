# Achievements in Nook — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A fourth tab and a widget toggle for long-term goals, with editable descriptions and a lasting Done list.

**Architecture:** `Kind.ACHIEVEMENTS` reuses the list machinery; a pure `core/Achievements.kt` owns the date-line format and the done/open split; the engine gains achieve/reopen/describe built from `item_move` + `item_update`. Room goes to version 2 with a real migration.

**Tech Stack:** Kotlin, Compose, Room, Glance; JUnit + Robolectric.

**Spec:** `docs/superpowers/specs/2026-11-25-achievements-design.md`

## Global Constraints
- Public repo: no names or household details in code, tests, docs or commits; author = the repo's noreply address.
- `. scripts/env.sh`; tests `./gradlew testDebugUnitTest` → `BUILD SUCCESSFUL`. No real Todoist account.
- Date line: last description line, exactly `Done: 25 Nov 2026`. Section `Done`, ignoring case; several → all count, lowest order used.
- Sync commands: the existing five plus `item_update` with `description` only; `description` on `item_add`.
- Description at most 16,000 characters. UI strings exactly as in the spec.
- A missing Achievements project never changes the status line, the other tabs or sign-in.

## Review Focus
1. Updating from 1.3 with unsent changes → they are still sent, once. (Task 2 `migration_keepsQueueAndMemory`)
2. Ticked offline before Done exists, app killed → on next start Done is created once, item in it, dated. (Task 3 `offlineAchieve_survivesRestart`)
3. Project created, filled and shared on another device after the update → tab fills without reinstalling. (Task 3 `projectAppearsLater`)
4. Undo after the move was sent, and Undo of one added seconds earlier (temp id). (Task 3 `undoAfterSent`, `undoOfJustAdded`)
5. Description being typed when a sync arrives → typed text is not overwritten. (Task 4 `syncWhileEditing_keepsTypedText`)

### Task 1: Format and grouping
**Files:** Create `app/src/main/java/lt/nook/app/core/Achievements.kt`; Modify `core/Grouping.kt` (ItemV gains `description: String = ""` last); Test `core/AchievementsTest.kt`
**Interfaces — Produces:** `splitDescription(text: String): Pair<String, LocalDate?>`, `joinDescription(text: String, day: LocalDate?): String`, `isDoneSection(name: String): Boolean`, `data class AchievementLists(val open: List<Group>, val done: List<DoneRow>)`, `data class DoneRow(val item: ItemV, val depth: Int, val date: LocalDate?)`, `fun achievementLists(items, sections): AchievementLists`.
- [ ] Failing tests: the same eight format cases as the kiosk plan's Task 1 (same inputs and outputs); open groups exclude Done sections; done newest first, undated last, children right after their done parent; two Done sections merged; no sections → one untitled open group.
- [ ] FAIL → implement → PASS → commit.

### Task 2: Kind, protocol, database 2
**Files:** Modify `data/Db.kt`, `data/Settings.kt`, `sync/Protocol.kt`, `sync/SyncEngine.kt` (merge, missingProjects), `auth/Session.kt`, `ui/SignInScreen.kt`, `NookApplication.kt` (migration, skip seeding for this kind), `ui/NookApp.kt` (title/icon); tests `FakeTodoist.kt`, `data/MigrationTest.kt`, `SyncEngineTest.kt`, `SignInTest.kt`
**Interfaces — Produces:** `Kind.ACHIEVEMENTS` (last; default name `Achievements`); `RItem.description`, `ItemE.description`; `PersonE.inAchievements`; `SyncEngine.people(kind)`; `updateDescription(id, text): Command`; `addItem(..., description: String? = null)`; `MIGRATION_1_2`.
- [ ] Failing tests: `migration_keepsQueueAndMemory` (build a version-1 file with raw SQL: one pending command, one known item, sync_token `v7`; open as 2 → rows intact, token `*`); `descriptionIsSynced`; `missingAchievements_isStillSynced` (status Synced, other lists work); `peopleOfEachProject`; `ourProjectsChanged_nextSyncIsFull`; `signIn_savesWithoutAchievements`.
- [ ] FAIL → implement (`exportSchema` stays false; migration SQL must match Room's expected schema, which the test proves by opening) → PASS → commit.

### Task 3: Achieve, reopen, undo, describe
**Files:** Modify `sync/SyncEngine.kt`; Test `sync/AchievementsEngineTest.kt`
**Interfaces — Consumes:** Tasks 1–2. **Produces:** `suspend fun achieve(id, today: LocalDate, sync = true): TickResult` (new case `Achieved(val undo: AchieveUndo)`), `suspend fun undoAchieve(u: AchieveUndo, sync = true)`, `suspend fun reopen(id, sync = true)`, `suspend fun describe(id, text: String, sync = true)`, `fun achievements(): Flow<AchievementLists>`, `fun myAchievementsFlow(): Flow<MyTasks>` (open only). Sub-items and repeating items fall through to `tick`.
- [ ] Failing tests: `achieve_movesAndDates_neverCloses`; `createsDoneOnce`; `usesExistingDoneIgnoringCase`; `offlineAchieve_survivesRestart`; `undoBeforeSent_sendsNothingForItem`; `undoAfterSent`; `undoOfJustAdded`; `reopen_removesDateAndSection`; `describe_keepsDateLine`; `describe_rejectsOver16000`; `goneElsewhere_message`; `projectAppearsLater`; `movePickerTargets_excludeDone`.
- [ ] FAIL → implement (local change + commands in one transaction, order section_add, item_move, item_update) → PASS → commit.

### Task 4: The tab
**Files:** Modify `ui/NookApp.kt`, `ui/Sheets.kt`; Create `ui/AchievementsTab.kt`, `ui/DetailSheet.kt`; Test `ui/AchievementsUiTest.kt`
**Interfaces — Consumes:** Task 3. **Produces:** `DetailSheet(engine, item, scope, onDismiss)`; `OpenRequest` gains `itemId: String? = null`.
- [ ] Failing tests: `fourTabs_achievementsLast`; `rowTap_opensDetails_doesNotTick`; `circle_achieves_undoRestores`; `addWithDescription`; `editDescription_saves`; `unsavedText_asks`; `syncWhileEditing_keepsTypedText`; `doneUnfolds_newestFirst_reopens`; `noProject_saysWhatToCreate`; `otherTabs_rowTapStillTicks`; `longPress_hidesDone`.
- [ ] FAIL → implement → PASS (run the suite twice with `--rerun`) → commit.

### Task 5: Widget toggle
**Files:** Modify `widget/MyTasksWidget.kt`, `widget/WidgetActions.kt`, `MainActivity.kt`; Test `widget/WidgetContentTest.kt`, `widget/MyTasksTest.kt`
**Interfaces — Consumes:** Task 3. **Produces:** `WidgetState` gains `selected: Kind`, `achievements: List<ItemV>`, `hasAchievements: Boolean`, `undoLabel: String?`; selection kept per widget in Glance preferences; `MainActivity.EXTRA_ITEM`.
- [ ] Failing tests: `toggleShowsBothCounts`; `achievementsSide_listsMine_notDone`; `undoIsALineUnderTheList`; `achievementRow_hasSeparateCircleAndName`; `noProject_saysWhatToCreate`; `achieveFromWidget_undoRestores`; `clear_forgetsBothUndos`; existing task tests adjusted only where the header changed.
- [ ] FAIL → implement → PASS → `./gradlew assembleRelease` SUCCESSFUL → commit.

### Finish
Whole-branch review, one fix pass, then ask the owner before `scripts/release.sh 1.4`.
