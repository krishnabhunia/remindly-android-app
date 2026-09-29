# Remindly 2.11 — 29-Sep-2026

**The Buy tab now opens on your Lists.** Make a list first (Groceries, Monthly stock, a party…),
then add items inside it. Your existing groups became lists automatically; items without a group
wait in an "Unsorted" card until you move them.

- **Lists screen:** each list shows what's left to buy, a progress bar, where the items are bought,
  an estimated total and its shopping day. Sort by Recent, A–Z or your own order; pin favourites.
- **New list:** name, icon, usual shop, a shopping day (a reminder at 9 AM that day) and Private
  (every item PIN-locked).
- **List menu** (long-press or ⋮): send to WhatsApp, share, pin, rename, duplicate, restart (bought
  items go back to To buy), mark all bought, merge into another list, delete. Deleting asks what to
  do with the items — the default keeps them in Unsorted — and can be undone.
- **Inside a list:** everything you add belongs to it. Group by shop, category, priority or date.
  "Recently bought in this list" suggestions, and a warning when the item is already in another list.
  The item editor's Group field is now a List picker; moving an item says where it went, with Undo.
- **Sharing:** a Share icon and a one-tap WhatsApp icon in every list. The text is
  "Groceries:-" followed by numbered lines like "1. Milk - 2 / L - Urgent - Bought".
  Long-press the WhatsApp icon to preview and change what is included.
- **Buy Now** on arriving at a shop now shows that shop's items from every list, grouped by list.
- A list someone shared with you can be added to your own lists.
- Buy ⚙ → Lists and Buy ⚙ → Sharing a list hold the new options. "Classic" brings back the old
  single flat Buy list.

Source: https://github.com/krishnabhunia/remindly-android-app

# Previous release — 2.10 — 28-Sep-2026

**Every permission is now optional, asked when you use it, and explained first.** Before Android's
question appears, Remindly says what the permission is for and what still works if you say no.

Saying no never leaves a dead screen: Calls works by hand, call-backs show numbers instead of names,
and shop lists work without geofences.

- **Settings → Permissions** (new): every permission, whether it is on, what it enables, and one
  button to allow it. Special access (exact alarms, battery, installing updates) is listed too.
- **When Android stops asking** after you have refused twice, the button now opens Remindly's page in
  Android settings instead of doing nothing.
- **Calls tab:** the intro card now has "Not now". After that it shrinks to one line — "Auto-detection
  is off — add call-backs with +" — with Enable.
- **Shops and Places:** a geofenced shop that can't alert says so, with "Allow" or "Allow all the time".
- **Contacts, calendar, save contact:** the same explanation first. A refusal says why nothing happened.
- Notifications are still asked at launch — a reminder app can't work without them.

Under the hood: the remaining tests that copied production logic now call the real code, and three
permanent checks guard permission handling and those tests. This is also the first release built,
signed and published entirely by GitHub Actions.

Source: https://github.com/krishnabhunia/remindly-android-app

# Previous release — 2.9 — 28-Sep-2026

**Updates now come through the app.** Remindly checks its GitHub repository once a day and tells
you when a new version exists; Settings → Updates downloads it (Wi-Fi only by default), verifies
it, and hands it to Android's installer — one tap, data kept. The first time, Android asks you to
allow Remindly to install updates.

**Shopping-list features** (from your reference screens): an icon per group, a long-press group
menu (Rename · Change icon · Share · Duplicate · Complete all · Delete list), product suggestions
and a mic on quick add, a category chip on linked items, WhatsApp / Copy sharing with text
options, defaults in Buy ⚙ → Sharing, and a launch splash. Dark mode was already in the app
(Settings → Appearance → Theme).

Source: https://github.com/krishnabhunia/remindly-android-app

# Previous release — 2.8 — 12-Sep-2026

**Scheduled alerts can now be deleted.** On the Scheduled alerts page, each row's menu (or a swipe
to the left) offers:
- **Delete this alert** — the trigger goes, the record stays: an item becomes muted 🔕 (you can
  pick an alert type again in its editor), a snooze is dropped, a recurring return is cancelled,
  a shop's arrivals are silenced, a place is disabled.
- **Delete the item — and every future alert** — the record goes to the Bin and **nothing of it
  stays armed**: due, future occurrences, snoozes, nags, quiet re-fires, geofences. The sheet lists
  exactly what it is removing. Restore from the Bin re-arms only what the record still has.

One Undo for a few seconds after either. Also fixed underneath: deleting a recurring call reminder
used to leave its recurring and overdue alarms armed; every deletion now cancels the full set, and
a test guards that for good.

# Previous release — 2.7 — 12-Sep-2026

Three things you asked for:
- **A checkbox on every group header** (Tasks, Learn, Buy — day, group and shop headers, Active
  and Done). Tap it to complete the whole group (or restore it from Done) after a quick confirm,
  with one Undo. The Buy Now view gets a single "Bought everything here". Switch it off in
  Settings → Lists, or per tab in that tab's ⚙.
- **Scheduled alerts** (Settings → Scheduled alerts → Open): every armed alarm, ring and
  notification in one list — items, calls, shop geofences, places — with the time, the style and
  the source. Rows in amber are armed but can't be opened (hidden tab, personal-locked, alerts
  off); their menu offers "Show the tab" or "Silence".
- **No more alarms with no card**: hiding a tab that still has upcoming reminders now asks
  whether to silence them too, and any alert that fires from a hidden tab is logged and pointed
  back to Scheduled alerts.

Version numbering: feature release → **2.7** (fixes on this line will be 2.7.1, 2.7.2 …).

# Previous release — 2.6.2 — 18-Aug-2026

**Bug fix — the Calls tab no longer touches your call history.**
On some phones (Vivo, Redmi) a first install could freeze the Calls tab and ring continuously while
it pulled in old missed calls from the phone's log. That path is gone for every device:

- The call log is now read **only to identify a call that has just ended** (a five-minute window),
  never to browse history. A fresh install starts with an empty Calls list, whatever is in your log.
- No scan on app start and none when you open the Calls tab — only a real call triggers one (with a
  short retry for phones that write the log entry late), plus the manual scan button.
- If several missed calls ever arrive together you get **one** alert, never a chain of alarms.
- Updating cleans out the junk reminders the old behaviour created; your manual ones stay.

Trade-off worth knowing: a call whose log entry the phone writes more than five minutes late is not
picked up. That is the cost of never reading history.

# Previous release — 2.6.1 — 18-Aug-2026

**Bug fix.** In Shop mode, tapping Buy / Shops / Products while a tab's ⚙ settings page was open did
nothing — the settings window stayed on screen. Those pages are a full-screen overlay, and only
Task-mode navigation knew to close it first; now every navigator does — tab taps, the sideways
swipe, and the ☰ mode switch all close the settings page and land where you tapped (with the usual
Save/Discard prompt if you had unsaved changes).

Version numbering also moves to the new scheme from here: **2.6.1** (x.y.z as plain counts).

# Previous release — 2.05 — 17-Aug-2026

**Arrivals now do something useful.**
- **Per-shop arrival alert**: once a shop has a geofence, its editor offers 🔔 Notify · 🔊 Ring ·
  ⏰ Alarm (any combination, or Default). Ring keeps sounding with Stop / Snooze / Open list; Alarm
  shows a full-screen card. Shops ⚙ holds the default and a per-shop re-alert cooldown (10 min).
- **"Buy Now"**: arriving at a shop adds a third view beside Active and Done — **Buy Now · <shop>** —
  showing only that shop's pending items. Tap an item to complete it. A newer arrival replaces it,
  and it stays until you tap **Hide ✕** (it survives restarts and reboots).
- Alert taps and "Open list" land straight on that view.

# Previous release — 2.04 — 17-Aug-2026

- **Mode moved to a ☰ menu** (top-left, every main screen): Task Mode / Shop Mode. The mode you were
  in opens next time — the old "Start in" setting is gone, and so is the header chip.
- **Each Shop-mode tab has its own ⚙**: Buy (buy list, groups, adding, PIN, reset), Shops (geofence
  alerts, default radius, location & battery, reset), Products (data export) — exactly like Tasks,
  Learn and Calls in Task mode. The Settings tab now shows the same General settings page in both modes.
- **Tag fix**: "Chain" / "Local" no longer break into stacked letters next to long shop names, and
  they are in sentence case.

# Previous release — 2.03 — 17-Aug-2026

**Google map picker is live.** This build carries your Maps SDK key (Key A) inside the app package
(Android-restricted). Everything else is 2.02: Google default with OSM fallback, limit + monthly lock,
API-keys card (add Key B on the phone for Google address lookups), GPS-centred picker, 13-stop radius.

If the map ever shows blank/grey tiles: check in Cloud Console that Key A is restricted to package
`com.krishna.remindly` + your SHA-1 and that **Maps SDK for Android** is enabled — the SDK reports
auth failures silently, so a wrong restriction looks like an empty map.

# Previous release — 2.02 — 17-Aug-2026

**Google Maps by default, OSM as the safety net.**
- **Provider**: Settings → Maps & Location → Google (default) or OSM. Google is used only when it can
  be: key present, Play Services present, not locked, under your limit — otherwise the app quietly
  uses OSM and says why (badge on the map, "Now using" line in Settings).
- **Limit + lock**: set a monthly cap for Google address lookups (default 80 % of the free tier;
  India 7× toggle). Hitting it — or a quota/denied answer from Google — **locks Google until the 1st**;
  it cannot be re-selected until then. Map loads are free and never counted.
- **API keys card**: keys are never shown — status only. Add new / Remove, each with two
  confirmations (type SAVE / Remove). Your Geocoding key lives only on the phone.
- **Map picker**: opens at your current GPS position, "Current GPS location" button, address
  search, radius drawn as a circle.
- **Radius scale everywhere**: 50 · 100 · 150 · 200 · 250 · 300 · 400 · 500 · 750 · 1000 m · 1.5 · 2 · 3 km
  (dropdown). Existing radii moved to the nearest stop (you get one toast if any moved).

**This build has no Maps SDK key yet** — the map picker runs on OSM until you send Key A and I
rebuild (see GOOGLE-CLOUD-SETUP-v2.02.md). Google address lookups start as soon as you add Key B in
the API keys card. Nothing to do in Firebase.

# Previous release — 2.01 — 16-Aug-2026

Five fixes from your 2.00 review, all in:
- **Start in** (Last used / Task / Shop) now lives in **General settings** too — first card — and
  still in Shop Settings (same card).
- **Shop Settings is one collapsible deck**: Mode, Buy list, Geofence alerts, Data, Shop Groups,
  Adding, Location & Battery, Personal PIN, Reset — all the same accordion cards.
- **Every edit popup follows one standard**: big Cancel / Save (and Delete when editing) in a row,
  and a large clear band below — **a quarter of the screen** — so nothing sits under the Android
  buttons. This applies to Tasks, Learn, Buy, Calls, shop, product, city/chain and share sheets.
  The clearance is measured from the app window, not guessed from the popup, and shrinks while
  the keyboard is open.
- Reset in General now also resets Start in.

Nothing to do in Firebase.

# Previous release — 2.00 — 16-Aug-2026

**Version 2.** Remindly is now permanently two apps in one — the header chip flips between
**Task mode** (Tasks · Learn · Calls · Settings) and **Shop mode** (Buy · Shops · Products · Shop
Settings). The v1.90 "Classic layout" switch is gone; a phone that was left in Classic comes back
to the two-mode layout automatically on update.

## What changed since 1.90
- **Shop Settings is the one settings page of Shop mode**: Mode (Start in), Buy list, Geofence
  alerts, Data, then a **General settings** row that jumps to Task-mode Settings, then the shop
  sections that used to hide behind the ⚙ (Shop Groups, Adding, Location & Battery, Personal PIN,
  Reset) — all inline. Reset now also covers the shop-mode toggles and Start in.
- **Leaving a settings page with unsaved changes** — by tab, swipe or the mode chip — asks
  Save / Discard on both settings pages.
- **Buy ☰ opens the Shops tab** (the old slide-in shop drawer is retired — one shop editor only).
- Task-mode Settings → Visible Tabs lists Tasks / Learn / Calls (Shop lives in Shop mode).
- Version numbers move to a 2.xx line: 2.00 today, then 2.01, 2.02 …

Nothing to do in Firebase. Cloud sync for cities/chains/products remains queued (N29).

# Previous release — v1.90 — 14-Aug-2026

Two apps in one. A chip in the header flips between **Task mode** (Tasks · Learn · Calls ·
Settings) and **Shop mode** (Buy · Shops · Products · Shop Settings). (The 1.90 "Dual-mode layout" toggle was removed in 2.00 — the app is always two-mode.)

## Shop mode
- **Buy** — your shopping list as before, now with product autocomplete: type a name, tap the
  📦 chip, and the unit + cheapest shop fill themselves.
- **Shops** — organised by **City**, with an Unassigned bucket. **Chains** (D-Mart, Reliance
  Smart Bazaar…) hold branches across cities; a branch names itself "Chain – City". Geofence
  per shop, radius in 50 m steps.
- **Products** — your own product database. Each product lists the shops that stock it with a
  last-known price; the green line names the cheapest. Completing a purchase through the
  checkout calculator teaches it real prices.
- **Shop Settings** — layout & start-mode, buy-list behaviour, geofence arrival alert +
  default radius, JSON export, and About Me.

Your existing shops' "Area" text became Cities automatically on first launch (one-time,
no duplicates). Nothing to do in Firebase for this release — the new catalogue data is
on-device; cloud sync for it is queued as N29.

# Previous release — v1.89

versionCode 89 · versionName 1.89 · schema ver 34 · 840 tests green

## Calendar wording: "Upcoming | Past"

Your correction, applied: in calendar mode the header toggle now reads **Upcoming | Past**
instead of "Present/Future | Past". Shorter, cleaner, same behaviour — it still filters by
time, and the normal Active | Done wording outside calendar mode is untouched.

# Previous release — v1.88


## The share window no longer hides under the Android buttons

Your report, fixed: the share pop-up's bottom row was sitting underneath Back/Home/Recents,
so "Share as text" (and sometimes Send) couldn't be tapped. Both sharing windows now end with
a spacer that always clears the system buttons — on 3-button phones and gesture phones alike,
in every state including the signed-out prompt.

The sweep also caught one more window with the same flaw that had never been reported: the
Calls tab's edit pop-up. Fixed the same way. Every bottom window in the app now follows the
same rule — the last row always stays above the Android buttons — and that rule is recorded
permanently so no future window ships without it.

Nothing else changed: no data, no settings, no behaviour — v1.87's sharing works exactly as
described, just fully tappable now.

# Previous release — v1.87


## Share a list — a frozen, read-only copy, straight to another Remindly

Tasks and Shop groups can now travel. On any active group header you'll find a share icon:
pick the items (everything not-done is preselected; locked personal items stay locked), type
the recipient's Google email — or tap a recent one — and Send. They get a red dot on the new
Shared icon in their header, open it, and Accept, Decline, or Block.

The copy is FROZEN and read-only by design: your later edits never flow, their taps never
touch your originals. They can tick items off locally (a strike-through that lives only on
their phone), and they can delete their whole copy — your list doesn't notice. Share the same
list again and accepting the new one quietly replaces their old copy. Prices are never shared.

Sending needs both sides signed in with Google once (Settings → Cloud sync) — the personal
Cloud-sync switch itself can stay off. There's no push notification: the red dot on the Shared
icon is the whole story, next time the app opens.

Everything is switchable: Settings → List Sharing has the master switch, per-tab Tasks/Shop
switches, and your Blocked senders (a blocked sender only ever sees "Declined").

Prefer plain text? "Share as text" in the same sheet opens the normal Android share menu.

**One setup step (once, by me):** the Firebase security rules in FIRESTORE-RULES-v1.87.rules
must be merged into the console. Until then the servers refuse sharing writes — safely.

# Previous release — v1.86


## The Redmi / Vivo flood is fixed — old calls stay out, and the phone stays smooth

Your report: on Redmi, MI and Vivo, Remindly was pulling in your ENTIRE past missed-call
history and the phone was basically hanging. Both are gone, and the cause turned out to be a
design flaw, not an OEM quirk — it just took those phones to expose it.

What was wrong: on first run the old engine asked the phone "what's your newest call row?"
using a query trick those brands reject. When the trick failed, the engine wrote down "newest
row = 0" — and from that moment "everything after row 0" meant your whole history. It then
walked every one of those rows with no cap, on the app's main thread, creating reminders and
alarms as it went. That is the flood AND the hang in one sentence.

What it does now: missed calls count strictly from the moment you INSTALLED Remindly — the
phone's own installation timestamp, which every brand reports the same way. The scan asks only
for calls after that fence (plus a 48-hour safety overlap), processes at most 200 rows per
sweep off the main thread, and remembers each call it has already seen so a phone that
renumbers its call log can never sneak the same call in twice.

On update, Remindly also CLEANS UP after the old bug: auto-created reminders born from
pre-install calls are removed, together with any alarms they were still holding. Reminders you
created by hand are untouched.

One honest note: if you granted the call-log permission days after installing, missed calls
from that in-between stretch now DO get reminders — that is what "from installation" means.

## "No Reminders" — an item can now simply be a note

Tasks and Learn editors have a third choice next to One-time and Repeat: **No Reminders**. Pick
it and the date and time grey out, the Reminder Type box steps aside, and the item never
alerts — it just sits in your list. Saving it also clears any pending snooze, because an item
that never alerts must not ring. Shop stays One-time/Repeat — a purchase always has a buy plan.
An existing item that already had no date now opens on No Reminders, because that is the truth
about it. And "Repeating" is now called **Repeat** everywhere.

You can also choose which of the three a NEW item starts on: Settings → Adding Items has a
"Default Schedule" row, globally and per tab (tabs can Inherit).

## Missed repeats roll forward — and keep a written record

A repeating reminder you dismissed and forgot no longer sits in the past. Every night at
midnight (and on boot, and when you open the app) it moves to its next occurrence from today,
and each skipped date is written into a **Missed Alerts** list — newest first, last ten — shown
in the editor and in View Alert. Misses never count toward an "ends after N times" repeat, and
a reminder you snoozed is still coming, so it is never rolled.

## View Alert — everything the item plans to do, in one popup

The card's expanded row has a new **View Alert** button: future occurrences (the same list the
editor previews), the Missed Alerts record, and the live Coming-up box, together.

## The Alert Tests are finally complete

Settings → Alert Tests now has THREE buttons: Notification, Ring, and the new **Test Alarm** —
the real full-screen card with the real sound and all four buttons. In the test, every button
just closes it: "Test — no action taken." The test notification is now built by the exact same
code as real ones, so it can never drift from reality again.

## Small honesty fixes

- Calendar mode's toggle now reads **Present/Future | Past** — it filters by time, not by done.
- The expiry sentence in Settings now says what actually happens: expiry shows on the card, and
  a done Shop item is kept until 9 AM on its expiry day.
- Call-reminder offsets speak minutes everywhere: "After 90 m", matching the snooze labels.

# Previous release — v1.85

## Cancelling a snooze now sticks — Save no longer undoes it

Your report: cancel the snooze, press Save, reopen — and the "Coming up" row is back. You were
right, and it was worse than the row: Save was re-arming the alert itself.

Here is why it happened. The editor takes a photograph of your item the moment it opens. When you
pressed Save, it wrote that photograph back — including the snooze you had just cancelled inside
that very editor. The store said "no snooze"; the photograph said "snooze at 14:30"; the
photograph won, and the scheduler then re-armed the alarm from it.

The fix: at the moment of saving, the editor now re-reads the item as it exists RIGHT NOW and
applies your edits on top of that. Everything you edit in the sheet behaves exactly as before.
Everything you do NOT edit there — snoozes, done, deletions — can no longer be dragged backward
in time by a Save.

This also fixes three things you had not hit yet:

- The reverse: snooze something from the alarm card while its editor is open, then Save — the
  fresh snooze used to be silently KILLED. It survives now.
- Mark something done from a notification while its editor is open, then Save — it used to come
  back as not-done. It stays done.
- The same photograph problem existed in BOTH Calls sheets (the note editor and the save-to-
  contacts sheet). A call snoozed from the card lost its snooze on the next Save there. Both
  fixed with the same mechanism.

## About your "why do I have to poke every time"

That conversation produced a standing rule, and this is the first release built under it:

1. **Class sweep** — every place that writes from a captured snapshot was enumerated and given a
   verdict. Three were broken (all fixed above — the third, the contacts sheet, you had never
   reported). Eight more were checked and are safe. The full table is in the BACKLOG.
2. **The test states your sentence** — there is now a test literally asserting "after cancel THEN
   save, the snooze is null and the row is gone".
3. **Proof the tests bite** — I put the old behaviour back and ran them: 7 of 8 failed. Restored
   the fix; everything green.
4. **The device checklist now includes Save, reopen, and reboot** around every state change —
   the exact steps where this bug lived.

Nothing about timing changed in this release. It only removes the ways a Save could quietly
rewind what you had just done.

---

# Remindly v1.84 — 08-Aug-2026

versionCode 84 · versionName 1.84 · schema ver 32 · 374 tests green

## Quiet now changes the Reminder Type to Notify — and you can see it

Your bug 1. Pressing "Quiet 90 m" always delivered the next alert as a quiet notification, but
the item's record still said Alarm — so the edit popup's Reminder Type chip and the "Coming up"
box kept showing the old type. The demotion was hidden inside the scheduler where no screen could
read it.

Quiet now writes it onto the item itself. Open the item after pressing Quiet and you will see:

| Where | Now shows |
|---|---|
| Reminder Type chip | **Notify** |
| Coming up | **Snoozed · Notify** + the exact return time |

Tapping outside the alarm card (the double-tap quiet) does the same thing now, and that gesture
also gained two things it never had: its defer shows in "Coming up", and it survives a reboot.

**Two things to know.** The change is permanent, as you asked: a repeating reminder will notify —
not alarm — on every future occurrence after one Quiet, until you set the type back in the
editor. And cancelling the snooze restores the original *time* only; the type stays Notify.

## The "x" in Coming up actually cancels now

Your bug 2 — and you were completely right. One function cancels an item's alarms, and it knew
about four alarm types but not the fifth: the Quiet re-fire. So the x cleared the row's data and
re-armed the original due, while the quiet notification stayed scheduled and arrived anyway. The
same hole meant editing an item's due time, deleting it, or marking it done also left a pending
Quiet alive. One line fixes all of those at once.

Separately, the row itself wouldn't disappear even when things worked, because the editor was
showing you a snapshot taken when it opened. The box now reads the live item, so the row vanishes
the moment you confirm.

To prove the fix is real I wrote a test that schedules the Quiet re-fire and cancels it, then ran
that test against the OLD code: 4 of 5 fail there, and all pass now.

## Yes / No

Your bug 3. The old dialog offered "Cancel snooze" and "Cancel" — two buttons that both start
with Cancel, one of which cancels the cancelling. It now asks "Cancel the snooze?" with **Yes**
and **No**. Only this dialog changed.

## Also fixed on the way

The Calls tab's snooze confirmation said "rings in 1 h" while actually scheduling your 90-minute
setting — the fifth label caught lying since v1.83. It now reads the setting. And a call-card
quiet could never be cancelled at all (nothing anywhere cancelled its alarm type); it dies now
with the same actions that cancel a call snooze.

## Housekeeping

`duration_gate.py` is deleted from the project and from this delivery, as you instructed. The
label protections live on as regular tests that run with every build.

---

# Remindly v1.83 — 08-Aug-2026

versionCode 83 · versionName 1.83 · schema ver 32 · 356 tests green

## Your snooze was never 60 minutes — the label was lying

You sent two screenshots: the alert card reading "Snooze 1 h" / "Quiet 1 h", and the settings chip
row reading **10 m · 30 m · 1 h · 1 h · 2 h**. That second screenshot is what solved it — two chips
cannot both say "1 h" unless something is collapsing two different numbers into the same words.

**Nothing was ever wrong with the timing.** Since v1.81 your reminders have been coming back after
exactly 90 minutes. What was wrong is the text printed on the button. One function turned minutes
into words by dividing by 60 and throwing away the remainder, so 90 became "1 h" — and 60 became
"1 h" too, which is why the same label appeared twice in your settings.

Everything now reads in plain minutes, as you asked:

| Where | Before | Now |
|---|---|---|
| Settings chips | 10 m · 30 m · 1 h · 1 h · 2 h | 10 m · 30 m · 60 m · 90 m · 120 m |
| Alert card | Snooze 1 h · Quiet 1 h | Snooze 90 m · Quiet 90 m |
| Toast after snoozing | Snoozed 1 h 30 min | Snoozed 90 m |

**Nothing about when a reminder returns has changed.** It was 90 minutes before this update and it
is 90 minutes after. Only the words changed.

## Two more things found while tracing it

The app had **two** functions turning minutes into words, and they disagreed: the button said
"1 h" while the toast that same button fired said "1 h 30 min". There is now one function, so the
button and the toast can never say different things about the same number.

The alert card also had the number **90 typed into it by hand** in one place. It looked correct
only because your setting happened to be 90 — the moment you changed the chip to 30, that message
would have claimed 90 while the alarm was actually set for 30. It reads your setting now.

## Why the checks did not catch this

The build check I added in v1.82 looked for hardcoded numbers in the *scheduling* code, and the
tests confirmed the *scheduling* was right. Neither ever asked the only question that mattered to
you: **does the text on the button name the number the app is actually using?** "1 h" is not blank,
and it is not equal to "10 m", so every test stayed green while you read a wrong number for two
releases.

That question is now a test — for every option in the list, and for every value from 1 minute to 24
hours. To prove it works, I put the old broken code back and re-ran: **9 of the 12 new tests
failed.** Then I restored the fix and the full suite went green.

## Settings can no longer show a value it is not using

If your saved snooze duration were ever a number outside the five chips, the screen used to fall
back to highlighting the first chip — showing "10 m" while the app used something else. That value
now appears as its own chip, and the mismatch is written to Error Logs.

## One thing I did NOT change, on purpose

The **Calls** reminder rules still read "After 1 h" and "After 1 h 30 min". That formatter keeps
the remainder, so it is not lying to you and no two values share a label — it just uses hours where
snooze now uses minutes. You asked for minutes on *snooze*, so I left Calls alone rather than
change it silently. It is queued as **N11** with a yes/no for you.

---

# Remindly v1.82 — 17-Aug-2026

versionCode 82 · versionName 1.82 · schema ver 32 · 344 tests green

## Snooze is 90 minutes everywhere now

You reported this three times, and each time I fixed the place you pointed at instead of the whole
problem. This release routes every snooze through one shared calculation, so there is nowhere left
for a stray value to hide.

Four places were wrong, not the two I first found:

- The Calls tab snoozed for 60 minutes
- A second copy of that hour that I had added myself
- The Ring notification had "Snooze 90 min" typed by hand, so changing the setting would have left
  that one button saying 90 while everything else obeyed you
- The Calls card button said "Snooze 1 h" — the same problem, and my own search missed it

There is now an automatic check that fails the build if anyone writes a snooze duration or a snooze
label by hand again. I tested the check by deliberately reintroducing the bug to confirm it catches
it.

## "Demote to Notify" is now "Quiet 90 min"

The old button sent a notification immediately and deferred nothing — you had just been looking at
the reminder, so the notification told you nothing new.

It now silences the alarm and brings the reminder back quietly, as a notification instead of an
alarm, after your snooze time. It shows up in the item's "Coming up" list and can be cancelled
there like any other snooze.

## Under the hood

Some of my tests contained their own copy of the app's logic instead of running the real thing —
which is why the snooze bug kept passing tests. Those are being rewritten to call the real code.
This release covers the snooze area; the rest follows.

The app manifest also had two permissions listed twice. Cleaned up, in preparation for the Play
Store listing.

## Files

| File | Purpose |
|---|---|
| Remindly-v1.82.apk | signed release build |
| Remindly-source-v1.82.zip | full source |
| ci/duration_gate.py | new — blocks hardcoded snooze durations and labels |
| ci/safezone_gate.py | icon safe-zone check |
| ci/build-apk.yml · ci/README-github-actions.md | build-it-yourself setup |
| BACKLOG.md | queue + shipped history |

## Still queued

Permissions made optional and explained · the call-screening investigation · the remaining test
rewrites.
