# Aurviz Flagship UI Craft Blueprint (V3)

**Role:** Principal Android UI/UX Architect
**Goal:** Elevate Aurviz Personal Agent to ChatGPT / Proton Mail / Signal / Todoist flagship visual craft via exact pixel blueprints, layout root-cause fixes, and tonal surface hierarchy.
**Constraints:** Stock `androidx.compose.foundation` ONLY (zero Material 2/3, zero Compose Unstyled), `VelaTheme` (`Theme.kt`, Midnight + Daylight + System), existing 55 Phosphor icons in `Icons.kt` ONLY, `AurvizHaptics`, strict `GLOSSARY.md` compliance (locked exception: `Settings` screen title stays `Settings`).

---

## 1. Flagship Gap Analysis vs. Cropped References (`/tmp/aurviz_refs/`)

| Reference Crop | Flagship Craft Standard | Current Aurviz Gap | V3 Flagship Blueprint Resolution |
| :--- | :--- | :--- | :--- |
| **ChatGPT Composer & Header** (`/tmp/aurviz_refs/chatgpt_composer.png`, `/tmp/aurviz_refs/chatgpt_welcome.png`) | Centered model selector pill (`DeepSeek ▾`) with symmetrical left/right action slots; floating composer anchored cleanly `8dp` above the IME or navigation bar without double-padding or pushing the top bar behind the status bar; crisp starter pills on empty state. | 1. **Double IME + Nav-Bar Insets:** `CommandScreen.kt:148-150` applies `.navigationBarsPadding().imePadding()` on the outer `Column` AND `CommandScreen.kt:297` applies `.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))` on the inner Composer wrapper — doubling the keyboard height (~250dp gap above keyboard) and crushing the message list behind the status bar.<br>2. **Header Off-Center / Redundant Copy:** `ModelPill` (`ModelPill.kt:29,46-48`) supports a redundant `badge` slot (`Set`), and `CommandScreen` right actions (`44dp` + `44dp` + `4dp` gap = `92dp`) vs left navigation (`48dp`) causes visual weight imbalance unless `AppBar` reserves symmetric side slots and `ModelPill` renders only `providerDisplayName(effectiveModel)` + `Icons.CaretDown`.<br>3. **Menu sheet title:** Uses banned word `"More options"` (`CommandScreen.kt:213,345`). | 1. Remove `.windowInsetsPadding(...)` from the inner Composer wrapper (`CommandScreen.kt:297`); keep `.navigationBarsPadding().imePadding()` once on the root `CommandScreen` `Column` (`CommandScreen.kt:148-150`).<br>2. Strip `badge` from `ModelPill` so the header pill renders only `"DeepSeek ▾"` (`providerDisplayName` + `14.dp` `Icons.CaretDown`) centered inside `AppBar`.<br>3. Rename `"More options"` to `"Workspace"` across `CommandScreen`. |
| **ChatGPT Side Drawer** (`/tmp/aurviz_refs/chatgpt_side_drawer.png`) | Edge-to-edge full-height drawer (`surface` tonal sheet), borderless `44dp` active `Session` pill (`surfaceVariant` background, `12dp` corner radius, zero border), pinned bottom account/settings footer that always clears 3-button and gesture navigation bars. | 1. **Clipped Gmail Footer in Dialog:** `SideDrawer.kt:45` uses `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false))`, where Android's `Dialog` window often reports `0` for `WindowInsets.navigationBars` unless the dialog window itself fits system windows or pads `safeDrawing` bottom with a guaranteed `16.dp` floor (`SideDrawer.kt:72-81`).<br>2. **Session Row Polish:** Active `SessionRow` in `AgentNav.kt:494-540` must be a borderless `44.dp` pill (`RoundedCornerShape(12.dp)`, `Theme.colors.surfaceVariant`, zero `.border(...)`) with `Theme.typography.label` for active vs `Theme.typography.body` for inactive, and a `48.dp` accessible delete tap target (`IconButtonSize.Small`). | 1. Set `decorFitsSystemWindows = true` in `SideDrawer.kt` `DialogProperties` (matching `ModalBottomSheet.kt:45`) and apply `.statusBarsPadding().navigationBarsPadding().padding(bottom = 12.dp)` so the `64.dp` Gmail footer is never clipped off-screen on any OEM window.<br>2. Style active `SessionRow` as a borderless `44.dp` pill (`Theme.shapes.small`, `Theme.colors.surfaceVariant`, no border) with `48.dp` hit target on the trailing `Icons.Trash` button. |
| **ChatGPT Settings, Usage & Customize** (`/tmp/aurviz_refs/chatgpt_settings.png`, `/tmp/aurviz_refs/chatgpt_usage_limits.png`, `/tmp/aurviz_refs/chatgpt_customize_memory.png`) | Calm tonal grouped cards (`surface` on `background`, zero outer box border in Midnight, subtle `0.5dp` edge in Daylight), `16dp` inset row dividers (`CardDivider`), sleek `4dp` proportional progress bars with human provider names (`DeepSeek`), and generous `16dp` internal card padding around embedded controls. | 1. **Boxy Nested Borders:** `FeedCard`, `OptionGroup`, `ChoiceTile`, `TextField`, `SchedulesScreen` cards, and `OnboardingScreen` cards stack `1dp` borders inside `1dp` borders, creating a noisy "wireframe" look.<br>2. **Zero-Padding Card Edges in Settings:** In `SettingsScreen.kt:144`, the `System|Dark|Light` `ChoiceTile` row uses `12.dp` horizontal and `4.dp` bottom padding; in `SettingsScreen.kt:222-246`, the expanded OAuth paste-fallback text, `TextField`, and buttons sit directly inside `Section("Connections")` (`OptionGroup`) with **zero** horizontal/vertical padding, flush against the card wall; in `SettingsScreen.kt:414-418`, the expanded `OEM_AUTOSTART_GUIDANCE` text sits inside `OptionGroup` with **zero** padding (`Modifier.padding(16.dp)` missing).<br>3. **Telemetry & Copy:** `TelemetryBar` (`SettingsScreen.kt:498-543`) must use proportional `maxOf(planMs, toolMs, recallMs, 1000L)` scaling (with `4.dp` height, `surfaceVariant` track, `primary` fill) and `providerDisplayName` (`DeepSeek`, never raw lowercase id). Also verify zero `"options"` in Voice Command subtitle (`SettingsScreen.kt:402`: `"Live waveform and Voice Command preferences."`). | 1. Shift `OptionGroup` and `FeedCard` to calm tonal surface layering (`Theme.colors.card` on `Theme.colors.background`, borderless in Midnight or `0.5.dp` `Theme.colors.edge` subtle hairline, `16.dp` corner radius, `16.dp` internal padding).<br>2. Pad `Appearance & Feedback` tile row (`padding(16.dp)`), OAuth paste-fallback block (`padding(16.dp)`), `Always allow` block (`padding(16.dp)` + `fillMaxWidth()` button), and `OEM_AUTOSTART_GUIDANCE` (`padding(horizontal = 16.dp, vertical = 12.dp)`).<br>3. Upgrade `TelemetryBar` to proportional `4.dp` fill and `providerDisplayName`. |
| **ChatGPT Scheduled & Todoist Upcoming / Reporting** (`/tmp/aurviz_refs/chatgpt_scheduled.png`, `/tmp/aurviz_refs/todoist_upcoming.png`, `/tmp/aurviz_refs/todoist_reporting.png`) | Grouped section cards with `16dp` inner padding, full-width primary action pills, clean reporting timeline rows separated by `16dp` inset dividers instead of double-wrapping `FeedCard` inside `OptionGroup`. | **Zero-Padding & Double-Card Nesting in SchedulesScreen:** `Section(...)` wraps its content in `OptionGroup` (`0.dp` padding, `card` background). Inside `SchedulesScreen.kt`:<br>- `Section("New Schedule")` (`:108`) uses `14.dp` padding and a non-full-width `Preview Schedule` pill (`Modifier.height(44.dp)` without `.fillMaxWidth()`).<br>- `Section("Schedules")` (`:164-264`) nests `FeedCard` directly inside `OptionGroup` with **zero** outer padding or spacing, colliding card borders edge-to-edge.<br>- `Section("Pending confirmations")` (`:278-297`) renders `ConfirmationCard` and `Text(held.reason)` directly inside `OptionGroup` with **zero** padding (`held.reason` is flush against the card wall!).<br>- `Section("Recent Runs")` (`:309-350`) nests `FeedCard` rows directly inside `OptionGroup` with **zero** padding or dividers. | 1. In `Section("New Schedule")`, use `Modifier.padding(16.dp)` and make `Preview Schedule` full-width (`Modifier.fillMaxWidth().height(48.dp)`).<br>2. In `Section("Schedules")`, `Section("Pending confirmations")`, and `Section("Recent Runs")`, add `16.dp` internal padding / `CardDivider()` rhythm so rows never touch the `OptionGroup` wall and never double-stack card borders. |
| **Todoist In-App Browser & Onboarding** (`/tmp/aurviz_refs/todoist_inapp_browser.png`, `/tmp/aurviz_refs/todoist_onboarding.png`) | Single-line elliptical URL pill (`36dp`–`40dp` height, vertically centered `14sp` label, `singleLine = true`, `TextOverflow.Ellipsis`), optically centered empty state, and `>= 48dp` chrome touch targets; calm tonal onboarding cards. | 1. **Browser URL Pill Clipping & Tap Targets:** In `BrowserScreen.kt:158-198`, the `36.dp` URL pill uses `Theme.typography.body` (`16.sp` / `24.sp` lineHeight) inside a `36.dp` row without vertical centering on the `decorationBox`, causing placeholder copy (`"Paste a link, then Go"` / `"Enter URL"`) to clip or wrap onto 2 lines on narrow widths. Also the 4 top-bar icon boxes (`Close`, `Back`, `Forward`, `Reload`) are `36.dp` instead of `44.dp`–`48.dp` hit targets, and the bottom inspector `ChoiceTile` tabs carry heavy `2.dp`/`1.dp` borders.<br>2. **Browser Empty State:** When `panel != "Page"`, the empty state and inspector split space awkwardly; the empty state needs a `56.dp` tonal icon badge (`Theme.colors.surfaceVariant`) optically centered.<br>3. **Onboarding Polish:** `OnboardingScreen.kt:83,128` has a `40.dp` back target and `44.dp` secondary buttons instead of `48.dp` flagship touch targets, plus `1.dp` bordered boxes instead of tonal `surface` cards. | 1. Make `BrowserScreen` top chrome `56.dp` high with `48.dp` tap targets (`IconButtonSize.Small` 48dp hit box / 36dp visual), a `40.dp` tonal URL pill using `Theme.typography.label` (`14.sp`, `maxLines = 1`, `TextOverflow.Ellipsis`, `contentAlignment = Alignment.CenterStart`), and single-line placeholder `"Paste a link, then Go"`.<br>2. Replace heavy bordered `ChoiceTile` tabs in the bottom inspector tray with a sleek segmented tonal pill bar (`surfaceVariant` container, `surface` active pill, zero border).<br>3. Upgrade `OnboardingScreen` buttons and back target to `48.dp` and tonal `surface` cards. |
| **Todoist Ramble Voice UI** (`/tmp/aurviz_refs/todoist_ramble_voice.png`, `/tmp/aurviz_refs/todoist_ramble_top.png`) | Open-canvas hero sheet: single top-left `X` close button, breathing status caption (`Listening...`), large open-canvas quoted transcript (`20sp`–`22sp`, zero enclosing box border), `48dp` live bar waveform, and balanced bottom circular controls. | `VoiceCommandSheet.kt` must preserve the open-canvas *Ramble* layout: single top-left `X` from `ModalBottomSheet` (never a second `X` or cancel button in the body), open-canvas quoted transcript (never wrapped in a bordered rectangle), `48.dp` 28-bar live `Waveform`, `64.dp` primary mic/stop circle + `52.dp` confirm circle (`Theme.colors.surfaceVariant`, zero border when enabled, `Theme.colors.primary` check icon when `canConfirmVoice`). | Refine `VoiceCommandSheet.kt` typography (`20.sp` / `28.sp` centered transcript on open canvas, zero border box around transcript, single header `X`), smooth bar heights in `Waveform`, and remove the `1.dp` border on the `52.dp` confirm circle in favor of tonal `surfaceVariant` (or `primary.copy(alpha = 0.16f)` when ready). |
| **Proton Mail Compose & Signal Destructive Sheet** (`/tmp/aurviz_refs/proton_email_compose.png`, `/tmp/aurviz_refs/signal_sheet_banner.png`) | Proton: Clean hairline field stack (`To`, `From`, `Subject`, `Body`) separated by `1dp` `edge` hairlines inside a tonal `surfaceVariant` container, with a top-right pill toggle for `Edit`/`Done` and side-by-side `48dp` actions.<br>Signal: Restrained destructive sheet with tonal preview rows, clear blast-radius note, and stacked `48dp` `Destructive` + `Secondary` buttons. | 1. **Double-Nested Card in EmailConfirmationSheet:** `EmailConfirmationSheet.kt:64` wraps the body in `Section("Send this email?")`, which wraps everything in `OptionGroup` (`0.dp` padding, `card` background, `0.5dp` border) — so the intro text, unseen warning, `TextField`s, and the `1dp` bordered `HairlineRow` stack all sit flush against `OptionGroup`'s unpadded wall!<br>2. **Unpadded Rows in ForgetMemorySheet:** `ForgetMemorySheet.kt:46` also uses `Section("Delete these?")` (`OptionGroup` with `0.dp` padding), so the `preview.take(3)` `Text` rows and the blast-radius caption press directly against the `0.dp` left/top/bottom edges of `OptionGroup`! | 1. In `EmailConfirmationSheet.kt`, replace the outer `Section("Send this email?")` wrapper with a clean open-sheet header row (`"Send this email?"` title + tonal `"Edit"` / `"Done"` pill) above a tonal `Theme.colors.surfaceVariant` field stack (`RoundedCornerShape(16.dp)`, `0.5.dp` `Theme.colors.edge`, `CardDivider()` between `To` / `From` / `Subject` / `Body`) and `12.dp` padded warning banner.<br>2. In `ForgetMemorySheet.kt`, add `16.dp` internal padding and `CardDivider()` separators inside the preview card so each Memory row and the blast-radius note have crisp `16.dp` breathing room. |

---

## 2. Global Token, Surface, Divider & WindowInsets Contracts

### 2.1 Tonal Surface Hierarchy (`Theme.kt`)
Replace the boxy 1dp wireframe look with three distinct tonal elevation layers in both Midnight (Dark) and Daylight (Light) modes. **Never use `.alpha(0.72f)` dimming on settled cards**—always use explicit `onBackgroundMuted` / `inkTertiary` tokens.

| Token | Midnight (`DarkThemeColors`) | Daylight (`LightThemeColors`) | Usage Contract |
| :--- | :--- | :--- | :--- |
| `background` | `Color(0xFF0E0F12)` | `Color(0xFFF6F7F9)` | Root canvas (`ScreenScaffold`, `Page`, `AppBar`). Deep obsidian in Midnight; warm alabaster in Daylight so white `surface`/`card` layers pop naturally without heavy borders. |
| `surface` | `Color(0xFF17181C)` | `Color(0xFFFFFFFF)` | Elevated sheets (`SideDrawer`, `ModalBottomSheet`, `AlertDialog`) and primary grouped cards. |
| `card` | `Color(0xFF17181C)` | `Color(0xFFFFFFFF)` | Grouped section containers (`OptionGroup`, `FeedCard`, `StarterRow`, `ConfirmationCard`). |
| `surfaceVariant` | `Color(0xFF22242A)` | `Color(0xFFEEF0F4)` | Active drawer `Session` pill, secondary pills/buttons, search fields, unselected filter chips, telemetry bar tracks. |
| `inset` | `Color(0xFF131418)` | `Color(0xFFF1F3F6)` | Code blocks, confirmation detail wells, text-input wells. |
| `composer` | `Color(0xFF1B1C22)` | `Color(0xFFFFFFFF)` | Floating `Composer` pill fill. |
| `composerRing` | `Color(0xFF2C2E36)` | `Color(0x1F141518)` | `0.5.dp`–`1.dp` subtle ring around the `Composer` pill. |
| `edge` | `Color(0x14F5F5F7)` (~8% white) | `Color(0x14141518)` (~8% ink) | Subtle hairline border (`0.5.dp`) and `1.dp` inset `CardDivider` color. |
| `border` | `Color(0xFF2E3038)` | `Color(0x26141518)` | Explicit outline buttons (`Run now`, `MorningBriefingChip`) and switches when off. |
| `onBackground` | `Color(0xFFF5F5F7)` | `Color(0xFF141518)` | Primary ink on `background` and `card`. |
| `onSurface` | `Color(0xFFF5F5F7)` | `Color(0xFF141518)` | Primary ink on `surface` and sheets. |
| `onSurfaceVariant` | `Color(0xFFE4E5E9)` | `Color(0xFF24262B)` | Ink on `surfaceVariant` pills and chips. |
| `onBackgroundMuted` | `Color(0xA6F5F5F7)` (65% alpha) | `Color(0xA8141518)` (66% alpha, >4.8:1 AA contrast) | Secondary descriptions, timestamps, settled receipt text, metadata captions. |
| `inkTertiary` | `Color(0x7AF5F5F7)` (48% alpha) | `Color(0x80141518)` (50% alpha) | Chevrons, section sub-headers (`Today`, `Previous`), subtle icons. |
| `bubbleUser` | `Color(0xFF262830)` | `Color(0xFF18191D)` | User command bubble in `CommandScreen`. |
| `bubbleUserInk` | `Color(0xFFF5F5F7)` | `Color(0xFFFFFFFF)` | User command bubble text. |
| `primary` | `Color(0xFF1084FE)` | `Color(0xFF0E6VD6 -> 0xFF0E6CD6)` | Electric Blue primary CTA, active progress/telemetry fill, selected chip. |
| `accentText` | `Color(0xFF70B8FF)` | `Color(0xFF0B63C4)` | Inline interactive text (`Details`/`Hide`, `Fires in …`, `Retry`). |
| `success` | `Color(0xFF34D399)` | `Color(0xFF057A4B)` | Completed step checks, `Granted` status, `SUCCESS` run dot. |
| `warning` | `Color(0xFFFBBF24)` | `Color(0xFF9A5B00)` | Unseen recipient banner, `Action needed` permission status. |
| `destructive` | `Color(0xFFFF5667)` | `Color(0xFFC92532)` | Delete/revoke actions, `FAILED` status dot and error banner. |

### 2.2 Typography Scale (`ThemeTypography` in `Theme.kt`)
- `display`: `26.sp` / `32.sp` lineHeight, `FontWeight.SemiBold`, `FontFamily.SansSerif`, `letterSpacing = (-0.4).sp` (Onboarding hero title).
- `title`: `17.sp` / `22.sp` lineHeight, `FontWeight.SemiBold`, `FontFamily.SansSerif`, `letterSpacing = (-0.2).sp` (`AppBar` title, sheet title, empty-state title).
- `body`: `15.sp` / `22.sp` lineHeight, `FontWeight.Normal`, `FontFamily.Default` (Markdown prose, Memory text, transcript, input fields).
- `label`: `14.sp` / `20.sp` lineHeight, `FontWeight.Medium`, `FontFamily.Default` (OptionRow title, buttons, drawer items, starter rows).
- `caption`: `12.sp` / `16.sp` lineHeight, `FontWeight.Normal`, `FontFamily.Default` (Section headers, secondary descriptions, metadata, telemetry numbers).
- `code`: `12.sp` / `17.sp` lineHeight, `FontWeight.Normal`, `FontFamily.Monospace` (Code blocks, confirmation detail wells, step args).

### 2.3 Shared Card, Divider & Segmented Control Primitives
1. **`CardDivider` (move to shared `app/src/main/java/com/aurviz/agent/ui/components/Section.kt` or `OptionGroup.kt` as a public `@Composable fun CardDivider(inset: Dp = 16.dp)`):**
   ```kotlin
   @Composable
   fun CardDivider(modifier: Modifier = Modifier, inset: Dp = 16.dp) {
       Box(modifier.fillMaxWidth().padding(horizontal = inset)) {
           Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.colors.edge))
       }
   }
   ```
2. **`OptionGroup.kt`:**
   - Shape: `RoundedCornerShape(16.dp)` (`Theme.shapes.grouped`).
   - Background: `Theme.colors.card`.
   - Border: `0.5.dp` `Theme.colors.edge` (invisible-soft hairline; never `1.dp` `Theme.colors.border`).
3. **`FeedCard.kt`:**
   - Shape: `RoundedCornerShape(16.dp)` (`Theme.shapes.grouped`).
   - Background: `Theme.colors.card`.
   - Border: `0.5.dp` `Theme.colors.edge`.
   - Padding: `16.dp` (`Space * 4`) all around (upgraded from cramped `12.dp`).
   - Vertical spacing: `Arrangement.spacedBy(10.dp)`.
4. **`ChoiceTile.kt`:**
   - Selected: `Theme.colors.surfaceVariant` fill, `1.dp` `Theme.colors.primary` border (down from heavy `2.dp`).
   - Unselected: `Theme.colors.inset` fill, `0.5.dp` `Theme.colors.edge` border (down from `1.dp` `Theme.colors.border`).
   - Minimum height: `44.dp` (`Modifier.heightIn(min = 44.dp)`), horizontally & vertically centered content when used as a mode/tab pill.
5. **`TextField.kt`:**
   - Background: `Theme.colors.inset` (contrasts cleanly against both `card` and `surface`).
   - Border: `1.dp` `Theme.colors.primary` when `focused`, `0.5.dp` `Theme.colors.edge` when unfocused (down from heavy `1.dp` `Theme.colors.border`).

### 2.4 WindowInsets, IME & Dialog Contracts (Root-Cause Fixes)
1. **Top Status Bar Contract (`ScreenScaffold.kt` & `AppBar.kt`):**
   - `ScreenScaffold` owns `.statusBarsPadding()` **once** on the outer `Box(Modifier.fillMaxSize().background(Theme.colors.background).statusBarsPadding())`.
   - `Page` is called **inside** `AgentNav`'s `ScreenScaffold` (`AgentNav.kt:95`). Because `Page.kt:52` currently wraps its content in a **second** `ScreenScaffold(modifier)`, every `Page` destination (`Schedules`, `Memory`, `Settings`) currently double-pads `.statusBarsPadding()`!
   - **Fix in `Page.kt:52`:** Replace the inner `ScreenScaffold(modifier)` in `Page.kt` with a plain `Box(modifier.fillMaxSize().background(Theme.colors.background))` (or idempotent `ProvideContentColor(Theme.colors.onBackground)`), because `AgentNav` already wraps the entire `NavHost` in `ScreenScaffold` (and standalone tests that call `Page` get `statusBarsPadding` if `ScreenScaffold` consumes insets via `Modifier.consumeWindowInsets(WindowInsets.statusBars)`!).
   - Specifically: in `ScreenScaffold.kt:24`, chain:
     ```kotlin
     Box(
         modifier
             .fillMaxSize()
             .background(Theme.colors.background)
             .statusBarsPadding()
             .consumeWindowInsets(WindowInsets.statusBars),
     ) {
         ProvideContentColor(Theme.colors.onBackground) { content() }
     }
     ```
     Adding `.consumeWindowInsets(WindowInsets.statusBars)` guarantees that even if `ScreenScaffold` is nested inside `Page`, the child `statusBarsPadding()` reads `0.dp` and **never double-pads**!
2. **CommandScreen IME & Navigation Bar Contract (`CommandScreen.kt`):**
   - **Root Cause:** `CommandScreen.kt:148-150` applies `.navigationBarsPadding().imePadding()` to the outer `Column`, and `CommandScreen.kt:297` applies `.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))` to the inner Composer `Column`. When the keyboard opens (~300dp), both modifiers pad 300dp (total ~600dp!), leaving a ~250dp blank gap above the keyboard and crushing the `LazyColumn` and `AppBar` upward.
   - **Fix:** Keep `.navigationBarsPadding().imePadding()` on the outer `Column` (`CommandScreen.kt:148-150`) and **delete** `.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))` from the inner Composer `Column` (`CommandScreen.kt:297`). The inner Composer `Column` uses only `.fillMaxWidth().background(Theme.colors.background).padding(horizontal = 16.dp, vertical = 8.dp)`.
3. **SideDrawer Dialog Footer Inset Contract (`SideDrawer.kt`):**
   - **Root Cause:** `SideDrawer.kt:45` uses `DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)`. On Android OEM builds (including Realme/Xiaomi gesture nav), a custom `Dialog` window with `decorFitsSystemWindows = false` does not receive reliable bottom navigation/gesture insets unless the window decor fits system windows or uses `safeDrawing` with a minimum bottom floor.
   - **Fix in `SideDrawer.kt:45-85`:**
     - Use `DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)` (matching `ModalBottomSheet.kt:45`), OR if `decorFitsSystemWindows = false` is kept for full-bleed scrim, apply:
       ```kotlin
       Column(
           Modifier.fillMaxSize()
               .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom))
               .windowInsetsPadding(
                   WindowInsets.navigationBars
                       .union(WindowInsets.systemGestures)
                       .only(WindowInsetsSides.Bottom),
               )
               .padding(top = 8.dp, bottom = 16.dp),
       )
       ```
     - Furthermore, inside `AgentNav.kt:416-461`, give the bottom profile/settings footer `Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp)` so the 2-line Gmail + `"Settings & Memory"` block never clips vertically.

---

## 3. Exact Pixel Blueprints by Screen & Sheet

### 3.1 `CommandScreen.kt` + `AppBar.kt` + `ModelPill.kt` + `Composer.kt` + `SideDrawer.kt` + `AgentNav.kt`

#### A. `AppBar.kt` & `ModelPill.kt` (True Optical Centering)
- **File:** `app/src/main/java/com/aurviz/agent/ui/components/ModelPill.kt`
  - Remove the redundant `Set`/`Not set` badge display in the header pill (keep the `badge: String? = null` parameter defaulted to `null` for source compatibility, or only render `model` + `Icons.CaretDown`).
  - Pill Modifier:
    ```kotlin
    Box(
        modifier
            .heightIn(min = 36.dp)
            .clip(Theme.shapes.fullyRounded)
            .background(Theme.colors.surfaceVariant)
            .clickable(
                onClick = onClick,
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.foundation.LocalIndication.current,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    )
    ```
  - Inner Row: `horizontalArrangement = Arrangement.spacedBy(6.dp)`, `Text(model, style = Theme.typography.label, color = Theme.colors.onSurface)` + `Icon(Icons.CaretDown, contentDescription = null, modifier = Modifier.size(14.dp), tint = Theme.colors.onBackgroundMuted)`.
- **File:** `app/src/main/java/com/aurviz/agent/ui/screens/CommandScreen.kt`
  - Top `AppBar`:
    - `navigation`: `48.dp` hit target with `Icons.Menu` (`22.dp`, `contentDescription = "Open menu"`) and a `7.dp` `Theme.colors.primary` dot at `Alignment.TopEnd` when `state.pendingApprovals.isNotEmpty()`.
    - `title`: `ModelPill(model = providerDisplayName(effectiveModel), onClick = { showProviderSheet = true })` — renders `"DeepSeek ▾"`, `"Gemini ▾"`, or `"Meta ▾"`, horizontally centered across the screen width by `AppBar`'s custom `Layout` (`(constraints.maxWidth - titlePlaceable.width) / 2`).
    - `actions`: Two `48.dp` targets (`IconButton` or `Box(Modifier.size(48.dp))`):
      1. `Icons.NotePencil` (`20.dp`, `contentDescription = "New Session"`, disabled while `state.agentRunning`).
      2. `Icons.DotsThree` (`22.dp`, `contentDescription = "Workspace"` — **replace** banned word `"More options"`!).
  - Workspace Sheet (`showMenuSheet`):
    - `ModalBottomSheet(visible = showMenuSheet, onDismiss = { showMenuSheet = false }, title = "Workspace")` (replaces `"More options"`).

#### B. `Plan` Step Timeline & Expandable `StepRow` (`CommandScreen.kt:723-801`)
- **Upgrade (High-Value UI Craft #1 — Connected Plan Timeline):**
  - Instead of loose floating `• Tool 1 of 2: Search emails` lines, render the active/settled `Plan` steps inside a sleek tonal `Column` (`Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Theme.colors.surface).border(0.5.dp, Theme.colors.edge, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp)`).
  - Each `StepRow` has `Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(vertical = 4.dp)`:
    - `StepState.DONE`: `18.dp` circle (`Theme.colors.success.copy(alpha = 0.14f)`) with `12.dp` `Icons.Check` (`tint = Theme.colors.success`).
    - `StepState.FAILED`: `18.dp` circle (`Theme.colors.destructive.copy(alpha = 0.14f)`) with `12.dp` `Icons.X` (`tint = Theme.colors.destructive`).
    - `StepState.READY` / `StepState.RUNNING`: `18.dp` circle (`Theme.colors.primary.copy(alpha = 0.14f)`) with a `6.dp` `Theme.colors.primary` dot.
    - Step label: `Text(stepDisplayTitle(step), style = Theme.typography.label, color = if (step.state == StepState.FAILED) Theme.colors.destructive else Theme.colors.onSurface, modifier = Modifier.weight(1f))`.
    - Expanded args well: `Box(Modifier.padding(start = 26.dp, top = 4.dp, bottom = 4.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Theme.colors.inset).padding(horizontal = 10.dp, vertical = 6.dp))` containing `Text(step.argsSummary, style = Theme.typography.code, color = Theme.colors.onBackgroundMuted)`.

#### C. `Composer.kt` (`app/src/main/java/com/aurviz/agent/ui/components/Composer.kt`)
- Single-row floating pill:
  ```kotlin
  Row(
      modifier
          .fillMaxWidth()
          .heightIn(min = 52.dp)
          .clip(RoundedCornerShape(26.dp))
          .background(Theme.colors.composer)
          .border(1.dp, Theme.colors.composerRing, RoundedCornerShape(26.dp))
          .padding(horizontal = 6.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
  )
  ```
- Left action: `IconButton(onClick = onAddClick, contentDescription = "Add to command", variant = IconButtonVariant.Tonal, size = IconButtonSize.Small)` with `18.dp` `Icons.Plus`.
- Middle field: `BasicTextField` (`maxLines = 4`, `Theme.typography.body.copy(color = Theme.colors.onSurface)`), placeholder `"Ask Aurviz"` in `Theme.colors.onBackgroundMuted`.
- Right state-driven action (`48.dp` touch target):
  - `MainAction.Voice`: `IconButton(onClick = onVoiceClick, contentDescription = "Voice Command", variant = IconButtonVariant.Filled)` with `20.dp` `Icons.Waveform`.
  - `MainAction.Send`: `IconButton(onClick = onSend, contentDescription = "Send", variant = IconButtonVariant.Filled)` with `20.dp` `Icons.ArrowUp`.
  - `MainAction.Stop`: `48.dp` circle (`Theme.colors.primary`) with `20.dp` `Icons.Stop` (`contentDescription = "Stop response"`).

#### D. `SideDrawer.kt` & `AgentNav.kt` (`SessionRow`)
- **File:** `app/src/main/java/com/aurviz/agent/ui/AgentNav.kt` (`lines 204-540`)
  - Top brand bar: `56.dp` height, `16.dp` horizontal padding, `20.dp` `Icons.VelaMark` (`tint = Theme.colors.primary`), `8.dp` spacer, `Text("AURVIZ", style = Theme.typography.title)`, `Spacer(Modifier.weight(1f))`, `48.dp` `IconButton` (`Icons.NotePencil`, `contentDescription = "New Session"`).
  - Pinned navigation rows (`Schedules`, `Browser`, plus `Memory`):
    - Each row: `Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).clickable(...).padding(horizontal = 12.dp)`.
  - `SessionRow` (`AgentNav.kt:494-540`):
    - **Borderless `44.dp` pill:**
      ```kotlin
      val rowModifier = Modifier
          .fillMaxWidth()
          .height(44.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(if (active) Theme.colors.surfaceVariant else Color.Transparent)
      ```
    - Title: `Text(session.title, style = if (active) Theme.typography.label else Theme.typography.body, color = if (active) Theme.colors.onSurface else Theme.colors.onBackgroundMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))`.
    - Delete button: `48.dp` hit target (`IconButtonSize.Small` or `Box(Modifier.size(44.dp))`) with `16.dp` `Icons.Trash` (`contentDescription = "Delete Session"`, `tint = Theme.colors.inkTertiary`).
  - Bottom Pinned Gmail & Settings Footer (`AgentNav.kt:415-461`):
    - `1.dp` full-width `Theme.colors.edge` divider.
    - `Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(...).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically)`:
      - `36.dp` circular avatar (`Theme.colors.surfaceVariant`, zero border) with `18.dp` `Icons.User`.
      - `Column(Modifier.weight(1f).padding(horizontal = 12.dp))`:
        - `Text(state.gmailAccount ?: "Not connected", style = Theme.typography.label, color = Theme.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)`
        - `Text("Settings & Memory", style = Theme.typography.caption, color = Theme.colors.onBackgroundMuted, maxLines = 1)`
      - `20.dp` `Icons.SlidersHorizontal` (`tint = Theme.colors.onBackgroundMuted`).

---

### 3.2 `OnboardingScreen.kt` (`app/src/main/java/com/aurviz/agent/ui/screens/OnboardingScreen.kt`)
- Reference: `/tmp/aurviz_refs/todoist_onboarding.png`, `/tmp/aurviz_refs/chatgpt_welcome.png`.
- Top progress bar (`56.dp` height):
  - Left: `48.dp` back button (`Icons.CaretLeft`, `contentDescription = "Back"`) when `step > 0`, else `Spacer(Modifier.width(48.dp))`.
  - Center: 3 progress capsules (`36.dp` × `4.dp`, `RoundedCornerShape(50)`, `Theme.colors.primary` for `i <= step` else `Theme.colors.surfaceVariant`).
  - Right: `48.dp` min-height `"Skip"` pill (`Modifier.heightIn(min = 48.dp).clip(Theme.shapes.fullyRounded).clickable(onClick = onComplete).padding(horizontal = 12.dp)`, `contentAlignment = Alignment.Center`).
- Step 0 (`OnboardingWelcome`):
  - Hero mark: `64.dp` rounded square (`RoundedCornerShape(20.dp)`, `Theme.colors.surface`, `0.5.dp` `Theme.colors.edge`) with `30.dp` `Icons.VelaMark` (`tint = Theme.colors.primary`).
  - Feature card: tonal `Theme.colors.surface` container (`RoundedCornerShape(20.dp)`, `0.5.dp` `Theme.colors.edge`, `padding(16.dp)`), 3 `OnboardingFeature` rows separated by `CardDivider(inset = 0.dp)`.
- Step 1 (`OnboardingConnect`):
  - Gmail card & Model API Key card: tonal `Theme.colors.surface` (`RoundedCornerShape(20.dp)`, `0.5.dp` `Theme.colors.edge`, `padding(16.dp)`).
  - Provider selector pills (`Gemini`, `DeepSeek`, `Meta`): `44.dp` height, `RoundedCornerShape(50)`, selected uses `Theme.colors.primary` fill with `Theme.colors.onPrimary` text (or `Theme.colors.surfaceVariant` with `1.dp` `Theme.colors.primary` border); unselected uses `Theme.colors.inset` with `0.5.dp` `Theme.colors.edge`.
  - Secondary CTA buttons (`Connect Gmail`, `Save key`, `Use pasted link`, `Dismiss`): `48.dp` height (`Modifier.fillMaxWidth().height(48.dp)`).
- Step 2 (`OnboardingPermissions`):
  - Tonal card (`RoundedCornerShape(20.dp)`, `Theme.colors.surface`, `0.5.dp` `Theme.colors.edge`), rows padded `16.dp`, separated by `CardDivider(inset = 16.dp)`, `"Allow"` buttons at `heightIn(min = 40.dp)` with `48.dp` touch target.
- Bottom primary CTA: `Button(..., modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp).height(48.dp))`.

---

### 3.3 Sheets: `VoiceCommandSheet.kt`, `EmailConfirmationSheet.kt`, `ForgetMemorySheet.kt`

#### A. `VoiceCommandSheet.kt` (Todoist *Ramble* Open-Canvas Hero)
- Reference: `/tmp/aurviz_refs/todoist_ramble_voice.png`, `/tmp/aurviz_refs/todoist_ramble_top.png`.
- **File:** `app/src/main/java/com/aurviz/agent/ui/sheets/VoiceCommandSheet.kt`
- **Single Close Button Rule:** `ModalBottomSheet` already renders the top-left `Icons.X` close button (`contentDescription = "Close"`). `VoiceCommandSheet` MUST NOT render any second `X` or cancel button inside its body.
- **Open-Canvas Transcript Rule:** The transcript sits directly on the open sheet canvas (`Theme.colors.surface`) with **zero** enclosing box, border, or card rectangle:
  - Status caption: `Text(statusCaption, style = Theme.typography.caption.copy(textAlign = TextAlign.Center), color = if (state.listening) Theme.colors.primary else Theme.colors.onBackgroundMuted, modifier = Modifier.fillMaxWidth())` where `statusCaption` is `"Listening..."`, `"Paused"`, or `"Ready to listen"`.
  - `Spacer(Modifier.height(16.dp))`
  - Transcript / Prompt area (`Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(horizontal = 12.dp)`, `contentAlignment = Alignment.Center`):
    - When `heard.isBlank()`:
      `Text("Tap the mic and speak — typing always works too.", style = Theme.typography.title.copy(fontSize = 20.sp, lineHeight = 28.sp, textAlign = TextAlign.Center), color = Theme.colors.onBackgroundMuted, maxLines = 4, overflow = TextOverflow.Ellipsis)`
    - When `heard.isNotBlank()`:
      `Text("“$heard”", style = Theme.typography.title.copy(fontSize = 20.sp, lineHeight = 28.sp, textAlign = TextAlign.Center), color = if (state.isPartial) Theme.colors.onBackgroundMuted else Theme.colors.onSurface, maxLines = 4, overflow = TextOverflow.Ellipsis)`
  - `Spacer(Modifier.height(24.dp))`
  - Live `Waveform`: `Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp)` — 28 rounded vertical bars (`WAVE_BARS = 28`) from the 32-sample RMS ring buffer (`minH = 4.dp`, active bars in `Theme.colors.primary`, idle bars in `Theme.colors.surfaceVariant`).
  - Bottom controls (`Row` with `horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)`, `verticalAlignment = Alignment.CenterVertically`):
    - Primary Mic / Stop circle: `64.dp` (`Theme.colors.primary`), `24.dp` `Icons.Stop` when `state.listening` else `Icons.Microphone` (`tint = Theme.colors.onPrimary`, `contentDescription = if (state.listening) "Stop" else "Speak"`).
    - Confirm circle: `52.dp` (`Theme.shapes.fullyRounded`), background `if (canConfirm) Theme.colors.surfaceVariant else Theme.colors.surfaceVariant.copy(alpha = 0.45f)`, **zero border**, `24.dp` `Icons.Check` (`tint = if (canConfirm) Theme.colors.primary else Theme.colors.inkTertiary`, `contentDescription = "Use as command"`).

#### B. `EmailConfirmationSheet.kt` (Proton Mail Hairline Field Stack)
- Reference: `/tmp/aurviz_refs/proton_email_compose.png`.
- **File:** `app/src/main/java/com/aurviz/agent/ui/sheets/EmailConfirmationSheet.kt`
- **Root-Cause Fix:** Do NOT wrap the sheet body in `Section("Send this email?")` (which wraps everything in an unpadded `0.dp` `OptionGroup` card and causes double-borders and flush text!).
- **Exact Layout inside `ModalBottomSheet(title = "Confirmation")`:**
  1. **Header Row (`Modifier.fillMaxWidth()`, `verticalAlignment = Alignment.CenterVertically`):**
     - Left `Column(Modifier.weight(1f))`:
       - `Text("Send this email?", style = Theme.typography.label, color = Theme.colors.onSurface)`
       - `Text("Review and edit before confirming — nothing sends until you tap Confirm send.", style = Theme.typography.caption, color = Theme.colors.onBackgroundMuted)`
     - Right Edit Pill (`Modifier.heightIn(min = 36.dp).clip(Theme.shapes.fullyRounded).background(Theme.colors.surfaceVariant).clickable { editing = !editing }.padding(horizontal = 12.dp, vertical = 6.dp)`):
       - `Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically)` with `14.dp` `Icons.PencilSimple` + `Text(if (editing) "Done" else "Edit", style = Theme.typography.caption.copy(fontWeight = FontWeight.SemiBold), color = Theme.colors.onSurfaceVariant)`.
  2. **Unseen Recipient Banner (when `draft.to` intersects `unseen`):**
     - `Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Theme.colors.warning.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 8.dp))`
     - `Text("New recipient — never emailed before.", style = Theme.typography.caption.copy(fontWeight = FontWeight.Medium), color = Theme.colors.warning)`
  3. **Proton Hairline Field Stack (Read-Only vs Editing):**
     - When `!editing`:
       ```kotlin
       Column(
           Modifier.fillMaxWidth()
               .clip(RoundedCornerShape(16.dp))
               .background(Theme.colors.inset)
               .border(0.5.dp, Theme.colors.edge, RoundedCornerShape(16.dp)),
       ) {
           HairlineRow(label = "To", value = to.ifBlank { "—" })
           HairlineDivider()
           HairlineRow(label = "From", value = from ?: "Gmail")
           HairlineDivider()
           HairlineRow(label = "Subject", value = subject.ifBlank { "—" })
           HairlineDivider()
           HairlineRow(label = "Body", value = body.ifBlank { "—" }, maxLines = 6)
       }
       ```
       Where `HairlineRow` uses `Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)` with a `64.dp` `Theme.typography.caption` muted label and `Theme.typography.body` value, and `HairlineDivider` is a `1.dp` `Theme.colors.edge` divider inset by `16.dp` (`CardDivider(inset = 16.dp)`).
     - When `editing`:
       `Column(verticalArrangement = Arrangement.spacedBy(10.dp))` with `TextField(to, label = "To")`, `TextField(subject, label = "Subject")`, `TextField(body, label = "Message", minLines = 4)`.
  4. **Bottom Action Row (`Row(horizontalArrangement = Arrangement.spacedBy(10.dp))`):**
     - `Button(onClick = { onApprove(to, subject, body) }, enabled = to.isNotBlank(), modifier = Modifier.weight(1f).height(48.dp)) { Text("Confirm send") }`
     - `Button(onClick = onReject, variant = ButtonVariant.Secondary, modifier = Modifier.weight(1f).height(48.dp)) { Text("Reject") }`

#### C. `ForgetMemorySheet.kt` (Signal Destructive Sheet)
- Reference: `/tmp/aurviz_refs/signal_sheet_banner.png`.
- **File:** `app/src/main/java/com/aurviz/agent/ui/sheets/ForgetMemorySheet.kt`
- **Root-Cause Fix:** Inside `Section("Delete these?")`, `OptionGroup` has `0.dp` padding, so the `preview.take(3)` rows currently touch the card wall!
- **Exact Layout:**
  - Inside `Section("Delete these?")`:
    - Render each previewed `Memory` (`preview.take(PREVIEW_ROWS)`) inside a `Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp))`:
      - Between preview items, render `Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.colors.edge))`.
      - Each item: `Text(memory.text, style = Theme.typography.body, color = Theme.colors.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)`.
      - If `overflow > 0`: `Text("+$overflow more", style = Theme.typography.caption, color = Theme.colors.onBackgroundMuted)`.
      - Blast-radius footer inside the card (or as `Section(..., footer = ...)`):
        `Text("This only deletes the ${preview.size} listed ${if (preview.size == 1) "Memory" else "Memories"} — nothing else.", style = Theme.typography.caption, color = Theme.colors.onBackgroundMuted)`.
  - Stacked `48.dp` buttons:
    - `Button(onClick = onApprove, variant = ButtonVariant.Destructive, modifier = Modifier.fillMaxWidth().height(48.dp))` with `18.dp` `Icons.Trash` + `Text("Delete ${preview.size}")`.
    - `Button(onClick = onReject, variant = ButtonVariant.Secondary, modifier = Modifier.fillMaxWidth().height(48.dp))` with `Text("Keep")`.

---

### 3.4 `SettingsScreen.kt`, `SchedulesScreen.kt`, `MemoryScreen.kt`, `BrowserScreen.kt`

#### A. `SettingsScreen.kt` (`app/src/main/java/com/aurviz/agent/ui/screens/SettingsScreen.kt`)
- Reference: `/tmp/aurviz_refs/chatgpt_settings.png`, `/tmp/aurviz_refs/chatgpt_usage_limits.png`, `/tmp/aurviz_refs/proton_settings.png`, `/tmp/aurviz_refs/proton_privacy_toggles.png`, `/tmp/aurviz_refs/signal_settings_list.png`.
- **Locked Title:** `Page(title = "Settings", modifier = modifier, onBack = onBack)` (Glossary exception: keep screen titled **`Settings`**).
- **Fix 1 — `Appearance & Feedback` `System | Dark | Light` Tiles (`SettingsScreen.kt:142-171`):**
  - Give the `Row` `Modifier.fillMaxWidth().padding(16.dp)` (replacing `start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp`).
  - Each `ChoiceTile` gets `modifier = Modifier.weight(1f).heightIn(min = 44.dp)` and centers its label (`Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(label, style = Theme.typography.label) }`).
- **Fix 2 — `Connections` Pasted-Link Fallback Zero-Padding Bug (`SettingsScreen.kt:220-247`):**
  - Wrap the expanded `if (state.pasteFallbackVisible)` content in `Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp))` so the hint `Text`, `TextField`, and `Row` of `48.dp` buttons (`Use pasted link` + `Dismiss`) have `16.dp` padding from the `OptionGroup` card wall!
- **Fix 3 — `Performance Telemetry` Proportional `4.dp` Bars & Capitalized Provider (`SettingsScreen.kt:268-285, 498-543`):**
  - Compute `val maxScaleMs = maxOf(perf.planMs ?: 0L, perf.toolMs ?: 0L, perf.recallMs ?: 0L, perf.planAvgMs ?: 0L, perf.toolAvgMs ?: 0L, perf.recallAvgMs ?: 0L, 1000L).toFloat()` and pass `maxScaleMs` to `TelemetryBar(label, lastMs, avgMs, maxScaleMs = maxScaleMs)`:
    - Track: `Modifier.fillMaxWidth().height(4.dp).clip(Theme.shapes.fullyRounded).background(Theme.colors.surfaceVariant)`.
    - Fill: `val sample = lastMs ?: avgMs`; `val fraction = if (sample == null) 0f else (sample.toFloat() / maxScaleMs).coerceIn(0.06f, 1f)`.
    - Fill Box: `Modifier.fillMaxWidth(fraction).height(4.dp).clip(Theme.shapes.fullyRounded).background(Theme.colors.primary)`.
  - Provider row (`SettingsScreen.kt:276-284`): use `providerDisplayName(state.selectedProvider ?: state.providerUsed ?: BuildConfig.LLM_DEFAULT_PROVIDER)` so `"DeepSeek"`, `"Gemini"`, or `"Meta"` is always capitalized via the shared `CommandPlanUi.kt` helper.
- **Fix 4 — `Confirmation Rules` `Always allow` Block (`SettingsScreen.kt:334-352`):**
  - Give the `Column` `Modifier.fillMaxWidth().padding(16.dp)` (symmetric `16.dp` padding, replacing `top = 8.dp`) and make the `"Always allow"` `Button` full-width `Modifier.fillMaxWidth().height(48.dp)` (`enabled = newAllow.isNotBlank()`).
- **Fix 5 — `Permissions & Background` Voice Command Subtitle & OEM Guide Padding (`SettingsScreen.kt:399-419`):**
  - Voice Command row (`:400-405`): keep `"Live waveform and Voice Command preferences."` (verify zero banned word `"options"`).
  - Expanded OEM guide (`:413-418`): wrap `Text(OEM_AUTOSTART_GUIDANCE, ...)` in `Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))` with a preceding `CardDivider()` so the 22-line guide is never flush against the card wall!

#### B. `SchedulesScreen.kt` (`app/src/main/java/com/aurviz/agent/ui/screens/SchedulesScreen.kt`)
- Reference: `/tmp/aurviz_refs/chatgpt_scheduled.png`, `/tmp/aurviz_refs/todoist_upcoming.png`, `/tmp/aurviz_refs/todoist_reporting.png`.
- **Fix 1 — `Section("New Schedule")` Padding & Full-Width Pill (`SchedulesScreen.kt:106-129`):**
  - Pad the inner `Column` with `Modifier.fillMaxWidth().padding(16.dp)` and `verticalArrangement = Arrangement.spacedBy(12.dp)`.
  - Make the `"Preview Schedule"` button full-width and `48.dp` tall:
    ```kotlin
    Button(
        onClick = { onPreviewSchedule(draft) },
        enabled = draft.isNotBlank(),
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Text("Preview Schedule")
    }
    ```
- **Fix 2 — `Section("Schedules")` Zero-Padding / Double-Card Fix (`SchedulesScreen.kt:156-266`):**
  - When `state.schedules.isNotEmpty()`, wrap the schedule cards in `Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp))` (or render each schedule item with `Modifier.fillMaxWidth().padding(16.dp)` and `CardDivider()` between items) so cards never collide zero-padded against the `OptionGroup` wall!
- **Fix 3 — `Section("Pending confirmations")` Zero-Padding Fix (`SchedulesScreen.kt:269-299`):**
  - When `state.pendingApprovals.isNotEmpty()`, wrap the held items in `Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp))` so `ConfirmationCard` and `Text(held.reason, style = Theme.typography.caption, color = Theme.colors.onBackgroundMuted, modifier = Modifier.padding(horizontal = 4.dp))` have `16.dp` breathing room inside the section card.
- **Fix 4 — `Section("Recent Runs")` Todoist Reporting Rhythm (`SchedulesScreen.kt:300-352`):**
  - When `state.scheduleRuns.isNotEmpty()`, render each run as a clean `64.dp` reporting row inside the section card (`Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp)`) separated by `CardDivider()` (instead of nesting a `FeedCard` border directly against `OptionGroup`'s border with zero margin!).

#### C. `MemoryScreen.kt` (`app/src/main/java/com/aurviz/agent/ui/screens/MemoryScreen.kt`)
- Reference: `/tmp/aurviz_refs/chatgpt_customize_memory.png`.
- **Filter Chips (`MemoryScreen.kt:155-184`):**
  - Minimum touch height `36.dp` visual pill (`padding(horizontal = 14.dp, vertical = 8.dp)`), selected uses `Theme.colors.primary` with `Theme.colors.onPrimary` (`FontWeight.Medium`), unselected uses `Theme.colors.surfaceVariant` with `Theme.colors.onSurfaceVariant`.
- **`Section("Remembered")` Card Rhythm (`MemoryScreen.kt:185-255`):**
  - When `shown.isNotEmpty()`, render each `Memory` item with `16.dp` padding (`Modifier.fillMaxWidth().padding(16.dp)`) separated by `CardDivider()` inside the `OptionGroup` (or inside `Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp))` if keeping `FeedCard`), ensuring `48.dp` hit targets on `Edit Memory` (`Icons.PencilSimple`) and `Forget` (`Icons.Trash`) and zero double-border collision.

#### D. `BrowserScreen.kt` (`app/src/main/java/com/aurviz/agent/ui/screens/BrowserScreen.kt`)
- Reference: `/tmp/aurviz_refs/todoist_inapp_browser.png`.
- **Fix 1 — Top Chrome & Single-Line URL Pill (`BrowserScreen.kt:100-213`):**
  - Top bar `Row`: `Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)`, `verticalAlignment = Alignment.CenterVertically`, `horizontalArrangement = Arrangement.spacedBy(4.dp)`.
  - Make `Close Browser` (`Icons.X`), `Back` (`Icons.CaretLeft`), `Forward` (`Icons.CaretRight`), and `Reload` (`Icons.ArrowClockwise`) use `IconButton(..., size = IconButtonSize.Small)` (`48.dp` accessible hit box with `32.dp` visual circle) so every browser control passes the `>= 48.dp` tap-target audit while leaving maximum width for the URL pill!
  - URL Pill (`Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(Theme.colors.surfaceVariant).padding(horizontal = 12.dp)`):
    - Leading icon: `14.dp` `if (chrome.showSecureIcon) Icons.LockSimple else Icons.Globe` (`tint = Theme.colors.onBackgroundMuted`).
    - `BasicTextField`:
      - `textStyle = Theme.typography.label.copy(color = Theme.colors.onSurfaceVariant)` (`14.sp` / `20.sp` lineHeight — fits a `40.dp` pill with zero vertical clipping!).
      - `singleLine = true`, `maxLines = 1`.
      - `decorationBox`:
        ```kotlin
        decorationBox = { field ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                if (url.isEmpty()) {
                    Text(
                        "Paste a link, then Go",
                        style = Theme.typography.label,
                        color = Theme.colors.onBackgroundMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                field()
            }
        }
        ```
      - Because `textStyle` is `Theme.typography.label` (`14.sp`), `singleLine = true`, `maxLines = 1`, `overflow = TextOverflow.Ellipsis`, and `contentAlignment = Alignment.CenterStart`, `"Paste a link, then Go"` **never** wraps onto 2 lines!
- **Fix 2 — Optically Centered Empty State (`BrowserScreen.kt:235-261`):**
  - Center the empty state inside `Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center)`:
    - `Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp))`:
      - `56.dp` tonal circle (`Modifier.size(56.dp).clip(Theme.shapes.fullyRounded).background(Theme.colors.surfaceVariant)`, `contentAlignment = Alignment.Center`) with `26.dp` `Icons.Globe` (`tint = Theme.colors.primary`).
      - `Text("No page open yet", style = Theme.typography.title.copy(textAlign = TextAlign.Center), color = Theme.colors.onBackground)`.
      - `Text("Ask the Agent to open a link, or paste one above.", style = Theme.typography.body.copy(textAlign = TextAlign.Center), color = Theme.colors.onBackgroundMuted)`.
- **Fix 3 — Segmented Tonal Inspector Bar (`BrowserScreen.kt:290-319`):**
  - Replace the 3 heavy-bordered `ChoiceTile` boxes in the bottom tray with a sleek segmented tonal pill bar:
    ```kotlin
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Theme.colors.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    )
    ```
    Each segment (`"Page"`, `"Text"`, `"Links (${page?.links?.size ?: 0})"`):
    ```kotlin
    Box(
        Modifier.weight(1f)
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Theme.colors.surface else Color.Transparent)
            .clickable { panel = tabKey }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Theme.typography.label,
            color = if (selected) Theme.colors.onSurface else Theme.colors.onBackgroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    ```

---

## 4. High-Value UI-Only Upgrades Included (and Gimmicks Explicitly Cut)

### Included High-Value, Low-Risk UI Upgrades
1. **Connected Tonal `Plan` Timeline Card (`CommandScreen.kt`):** Groups `visiblePlanSteps` into a tonal `surface` container with `18.dp` semantic status badges (`Check` in `success`, `X` in `destructive`, dot in `primary`) and a monospace `inset` drawer for expanded arguments.
2. **Idempotent `WindowInsets` Consumption (`ScreenScaffold.kt` + `Page.kt` + `CommandScreen.kt`):** Chaining `.consumeWindowInsets(WindowInsets.statusBars)` in `ScreenScaffold` and removing duplicate `windowInsetsPadding` in `CommandScreen` permanently eliminates both double top-bar padding and the ~250dp keyboard gap.
3. **Proportional `4.dp` Telemetry Bars (`SettingsScreen.kt`):** Normalizes `Plan`, `Tool`, and `Recall` latency bars against the session's peak stage latency (`maxOf(..., 1000L)`) so users immediately see relative stage cost at a glance.
4. **Segmented Tonal Inspector & `40.dp` Single-Line Browser Pill (`BrowserScreen.kt`):** Eliminates 2-line URL placeholder clipping and replaces boxy `2.dp`-bordered radio tiles with a flagship segmented pill control.

### Gimmicks Explicitly Cut
- **NO** backdrop blur shaders, glassmorphism, or custom RenderEffect chains (breaks older Android API levels and hurts scroll frame times).
- **NO** typing-character streaming animations or fake skeleton shimmer timers (adds artificial latency to settled answers).
- **NO** floating action buttons or persistent bottom tab bars (conflicts with the ChatGPT-style `Composer` + `SideDrawer` shell).
- **NO** custom font binaries or new icon vectors (strictly reuses the 55 Phosphor icons in `Icons.kt` and system sans/monospace typography).

---

## 5. ViewModel / UiState Hook Assessment
- **Zero backend or ViewModel changes required.** `AgentUiState`, `AgentViewModel`, Room v6, `VoiceUiState`, `PerfTimings`, and `SessionSummary` already expose every field needed (`levelDb`, `isPartial`, `planAvgMs`, `toolAvgMs`, `recallAvgMs`, `runs`, `successRate`, `sessions`, `onboardingCompleted`, `pendingApprovals`, `allowAlways`). All 5 tickets are 100% pure UI + JVM source/presentation test slices.

---

## 6. Sprint Ticket Architecture (Single Branch `feat/ui-flagship-craft` → Single PR)

- **T1:** `Theme.kt` tonal hierarchy + shared card/divider/button primitives (`OptionGroup`, `FeedCard`, `ChoiceTile`, `TextField`, `CardDivider`) + `ScreenScaffold`/`Page`/`SideDrawer` inset fixes.
- **T2 (blocked by T1):** `CommandScreen` (IME double-padding fix, centered `ModelPill` without redundant `Set`, connected `Plan` step timeline, `"Workspace"` sheet title) + `Composer` + `SideDrawer`/`AgentNav` (borderless `44.dp` active `Session` pill, unclipped Gmail footer) + `OnboardingScreen` polish.
- **T3 (blocked by T1):** `VoiceCommandSheet` (open-canvas *Ramble* hero, single header `X`, borderless transcript & confirm circle), `EmailConfirmationSheet` (Proton hairline field stack without double-nested `Section` card), `ForgetMemorySheet` (Signal destructive sheet with `16.dp` padded preview rows).
- **T4 (blocked by T1):** `SettingsScreen` (padded `System|Dark|Light` row, padded paste-fallback & OEM guide, proportional `4.dp` telemetry bars, `providerDisplayName` `"DeepSeek"`, full-width `"Always allow"` block), `SchedulesScreen` (`16.dp` padded sections, full-width `"Preview Schedule"`, reporting rows with `CardDivider`), `MemoryScreen`, `BrowserScreen` (`40.dp` single-line `"Paste a link, then Go"` pill, optically centered empty state, segmented tonal inspector bar).
- **T5 (blocked by T2, T3, T4):** Midnight/Daylight contrast parity, strict `GLOSSARY.md` copy sweep (zero banned words, `Settings` title locked), `>= 48dp` tap-target QA pass + JVM source-pin tests + `docs/VALIDATION_LOG.md` close-out.
