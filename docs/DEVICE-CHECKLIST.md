# Remindly — DEVICE CHECKLIST (merged v1.83 → v1.89) — 08-Aug-2026

Everything below is code- and unit-test-verified but has NEVER run on a real phone.
Order chosen so the urgent OEM fix is proven first. Tick each ☐; anything odd → screenshot + Error Logs.

## A. THE OEM CALL-LOG FIX (v1.86 N26) — run on Redmi/MI/Vivo first, then Samsung

A1 ☐ FRESH INSTALL on a Redmi/Vivo with a long missed-call history: install v1.86 clean,
      grant call-log when asked. EXPECT: ZERO reminders from old calls; phone stays smooth
      (no hang at open). Error Logs show one "N26 first run: fence=…" line.
A2 ☐ Same phone: miss a real call now. EXPECT: exactly one reminder within ~2 min or at next
      app open; Error Logs "scan window …" line shows small row counts.
A3 ☐ UPGRADE path on the phone that flooded on v1.85: install v1.86 OVER it. EXPECT: the junk
      auto-reminders from pre-install calls are GONE from Calls AND from Recently Deleted;
      your MANUAL call reminders survive; log shows "N26 migration: … purged N …".
A4 ☐ After A3, wait/scan again. EXPECT: nothing re-ingests (dedupe ring holds).
A5 ☐ Revoke call-log permission, re-grant. EXPECT: no new flood, no reminders for pre-install
      calls — the fence is installation, not granting.
A6 ☐ Samsung regression: miss a call → one reminder, once. Decline a call with "Treat declined
      as missed" ON → reminder; OFF → skipped (logged).
A7 ☐ Install v1.86 fresh, DENY call-log for a day while missing a call, then grant.
      EXPECT (⚑ new): that in-between missed call DOES get a reminder.

## B. Schedule kinds (v1.86 N24/N25)

B1 ☐ Tasks editor, new item: radio shows No Reminders / One-time / Repeat. Pick No Reminders:
      date+time grey out and ignore taps; "This item never alerts." hint; Reminder Type box
      hidden. Save → item listed, never alerts.
B2 ☐ Reopen that item → opens on No Reminders. Give an old dateless item a look: it opens on
      No Reminders too (⚑ announced).
B3 ☐ Item with a LIVE snooze → set No Reminders → Save. EXPECT: snooze gone from Coming up,
      nothing fires later (⚑ announced; logged).
B4 ☐ Shop editor: only One-time / Repeat (no third radio). Calls editor: same two, now labelled
      "Repeat" (rename everywhere).
B5 ☐ Settings → Adding Items (Global): "Default Schedule" chips work; per-tab gear shows the
      row with Inherit (Shop lacks No Reminders). Set Learn=Repeat → new Learn item opens on
      Repeat with DAILY preselected.

## C. Missed-repeat roll + View Alert (v1.86 N20/N21)

C1 ☐ Create a DAILY repeat due yesterday 20:00 (set clock back, or wait a night). Cross
      midnight / reboot / reopen app. EXPECT: due moves to today 20:00; editor shows
      "Missed Alerts" with yesterday's entry; Error Logs one "ROLL …" line.
C2 ☐ Snooze a past-due repeat so the snooze is still pending overnight. EXPECT: NOT rolled.
C3 ☐ Card ▸ expand ▸ View Alert: Future Occurrences (matches editor preview), Missed Alerts,
      Coming up. Non-repeating item: no Future box.
C4 ☐ Cloud sync sanity: item with missed entries syncs to the other phone; a v1.85 phone
      editing the same item does not wipe the missed list.

## D. Test Alarm + samples (v1.86 N19)

D1 ☐ Settings → Alert Tests: THREE buttons. "Test Alarm" → after 1 s the REAL full-screen card
      (TEST · Reminder / "Submit the report" / Urgent pill) with sound. Each of the four
      buttons AND tapping outside: toast "Test — no action taken", card closes, nothing in
      Coming up, no alarm later.
D2 ☐ "Test Notification": now shows the expanded BigText body (⚑ announced drift-fix); Dismiss
      button clears it, data untouched.

## E. Wording (v1.86 N18/N22/N11)

E1 ☐ Tasks sort = Calendar: header toggle reads "Upcoming | Past" (v1.89 wording), fits ONE line on your
      narrowest phone; switching filters exactly as Active/Done did.
E2 ☐ Settings Shop section: new expiry sentence reads correctly.
E3 ☐ Calls: a 90-minute offset rule shows "After 90 m" on chips and previews.

## F. Carried from v1.85 (still unproven on device)

F1 ☐ N16 snooze-cancel: cancel a snooze in the editor, Save, reopen — it STAYS cancelled;
      reboot — still gone. Repeat from the Coming-up "x" on the card.
F2 ☐ N16 cross-editor: while an editor is open, mark the same item done from a widget; Save in
      the editor — done-state survives (no photograph overwrite).

## G. Carried from v1.84

G1 ☐ N13 Quiet: on the alarm card press Quiet — editor chip + Coming up now SAY Notification
      until it re-fires; after re-fire the type restores. Double-tap outside = same behaviour.
G2 ☐ N14 Coming-up "x" on a CALL reminder: row disappears immediately and nothing fires later
      (both snooze and demoted species).

## H. Carried from v1.83

H1 ☐ N10 snooze display: set snooze 90 → button says "Snooze 90 m", toast agrees, fires at 90.
H2 ☐ N12/N23: no gate scripts anywhere in the shipped source zip (spot-check ci/).

## I. LIST SHARING (v1.87 N17) — needs TWO phones (X sender, Y receiver), both signed in

I0 ☐ ONE-TIME: merge FIRESTORE-RULES-v1.87.rules into the Firebase console rules first.
I1 ☐ X: Tasks, By-Group sort, active list — share icon on each group header. Tap → sheet:
      not-done items preselected, a locked personal Shop item shows the padlock and cannot be
      selected. Send to Y's exact Google email → toast; entry appears under Shared → Sent
      as Pending.
I2 ☐ Y: red dot on the Shared inbox icon (no push — appears on open). Open → pending card →
      Accept. List appears under Receive; open it: read-only, tick two items (strike-through),
      kill + reopen the app — ticks persist; check X's phone — X's originals untouched, Sent
      row says Accepted.
I3 ☐ Y second device (same account): the accepted list shows there too; ticks do NOT travel
      (device-local by design).
I4 ☐ X shares the SAME group to Y again after editing it. Y accepts → the old copy is
      replaced by the new one (only one survives).
I5 ☐ Decline path: X shares → Y declines → X's Sent row says Declined; nothing in Y's Receive.
I6 ☐ Block path: X shares → Y taps Block → X sees plain Declined; X shares again → nothing
      surfaces on Y (no dot). Y: Settings → List Sharing → Unblock X → X shares → arrives.
I7 ☐ Revoke: X shares → BEFORE Y accepts, X taps Revoke → the pending card vanishes on Y.
I8 ☐ Q6: after Y accepted, X deletes the Sent record → Y's copy SURVIVES; toast on X says so.
      Y taps "Delete my copy" → gone on Y; X's list untouched.
I9 ☐ Offline: Y opens the pending card in airplane mode, taps Accept → reconnect → status
      reconciles (Firestore cache).
I10 ☐ Cloud sync OFF on both phones (only signed in): sharing still works end to end.
I11 ☐ Settings: master switch OFF hides the Shared icon and every group share icon on both
      tabs; Tasks=Off/Shop=On shows it on Shop only. "Share as text" opens the system sheet
      with a readable list.
I12 ☐ Signed OUT: the share sheet and hub show the sign-in prompt, no crash.

## Y. BUY LISTS FIRST + LIST SHARING (v2.11 N48) — install OVER 2.10 with real groups, then a clean install

Y1 ☐ UPGRADE over 2.10 with Buy groups (e.g. Groceries, Home) and some ungrouped items. EXPECT: Buy opens
      on the Lists screen; one card per old group with its N45 icon; ungrouped items in a dashed
      "Unsorted" card. Error Logs: one "LISTS reconcile: +N list(s)…" line. Close app, reopen → no new lines.
Y2 ☐ Card figures: "5 to buy · 2 done · updated …", progress bar, shop pill, "≈ ₹" (only when prices are
      known), "Due <day>" when a shopping day is set. Recent / A–Z / Custom reorder; Custom shows ▲▼.
      Pin a list → it stays on top in every sort.
Y3 ☐ "New list…" bar: type "Diwali" + keyboard Done → the new list opens with the add bar ready.
      ＋ circle → the editor (name, icon, usual shop, shopping day, Private) → "Create & add items".
Y4 ☐ Inside a list: ← and system Back return to Lists. Quick-add "Milk" → it is in THIS list only.
      With a usual shop set, the new item carries that shop. Sort chip offers By Shop / Category /
      Priority / Date / No Group; no list-name chip on the cards.
Y5 ☐ Duplicate warnings: "Toothpaste" open in Monthly stock → typing it in Groceries shows
      "Already in 📦 Monthly stock" (amber). Buy ⚙ → Lists → Warn on duplicates OFF → warning gone.
Y6 ☐ "Recently bought in this list": buy Tomato 1 kg, then type "to" → chip "Tomato · 1 / kg · bought
      here"; tap + ✓ → new item has qty 1, unit kg.
Y7 ☐ Editor: the Group field reads "List" with "+ New list…". Change an item's list → Save → toast
      "Moved to …" with Undo; Undo puts it back.
Y8 ☐ List menu: Duplicate (fresh open copies), Restart (bought → To buy, Undo), Mark all bought
      (Undo), Merge into another list (items move, list gone, Undo), Rename (items follow; old app
      versions / Classic view show the new name).
Y9 ☐ Delete a list: default "Keep items — Unsorted" → items in Unsorted with price history intact;
      "Move to…" → items in the chosen list; "Delete the items too" → items in Recently Deleted.
      Each: Undo restores the list AND its items.
Y10 ☐ SHARE icon → preview shows exactly:  Groceries:-  (blank line)  1. Milk - 2 / L - Urgent …
      Flip the 4 switches → preview follows. WhatsApp / Share… / Copy all work; "Send to a Remindly
      user instead…" opens the N17 sheet.
Y11 ☐ WHATSAPP icon: one tap → WhatsApp's chat picker with the text filled in (no Remindly sheet).
      Long-press → the preview. Buy ⚙ → Sharing → WhatsApp Business (both installed) → Business opens.
      Uninstall/disable WhatsApp → toast + Android share sheet. Icon OFF in settings → hidden.
Y12 ☐ Private list: create with Private ON (PIN asked if none) → card shows 🔒; opening asks the PIN;
      its items are Personal; locked items never appear in shared text ("… locked Personal items left out").
Y13 ☐ Shopping day: set tomorrow → at 09:00 one notification "Shopping day: 🛒 Groceries — N items to buy".
      Private list → "Shopping day: a private list". Reboot before 09:00 → still fires (persistence quartet).
Y14 ☐ Buy Now: walk into a geofenced shop with items in 2 lists → Buy opens the cross-list "Buy Now" view
      grouped by list; ← returns to Lists, whose banner offers Open / ✕.
Y15 ☐ Received Shop share (N17 hub) → "🛒 Add to my lists" → a new list with the sender's list name.
Y16 ☐ Two devices with cloud sync, both upgraded from the same groups → ONE list per name on both
      (no duplicates). Rename on phone A → phone B shows the new name after sync.
Y17 ☐ Buy ⚙ → Lists → "Classic (one flat list)" → the old Buy page is back (items added there go to the
      default list). Discard on the settings page never removes a list.
Y18 ☐ Persistence quartet for Y3/Y7/Y9: act → verify → close/reopen → verify → reboot → verify.

## X. PERMISSIONS OPTIONAL, ASKED IN CONTEXT (v2.10 N9) — run one phone as a CLEAN INSTALL

X1 ☐ UPDATE 2.9 → 2.10 from Settings → Updates (the W3 path). EXPECT: data intact; anything you had
      allowed before still shows "Allowed" in Settings → Permissions.
X2 ☐ CLEAN INSTALL on a spare phone/profile. Only the notification question appears at launch.
      DENY IT. EXPECT: no other permission dialog anywhere until you open the feature that needs it.
X3 ☐ With NOTHING granted, use Tasks, Buy, Learn, Shops and Products fully (add, edit, complete,
      group menu, share). EXPECT: all work; the only banner is the notifications one.
X4 ☐ Open Calls. EXPECT: the intro card with "Allow access" and "Not now". Tap Not now → the card
      becomes one line "Auto-detection is off — add call-backs with +" with Enable. Add a call-back
      by hand with + → works.
X5 ☐ Tap Enable → Android's dialog → Deny. Tap Enable again → Deny again. Tap Enable a third time.
      EXPECT: no dead tap — a sheet says Android won't ask again and "Open Android settings" opens
      Remindly's page there.
X6 ☐ Grant call log from Android settings, come back. EXPECT: the note disappears; the contacts
      explanation appears once (names instead of numbers).
X7 ☐ Shops: give a shop a geofence with location OFF. EXPECT: the note "Geofence alerts are off …" with
      Allow on the Shops page and inside the shop editor. Allow → "While using the app" only.
      EXPECT: the note changes to "only fire while Remindly is open" with "Allow all the time".
X8 ☐ Settings → Permissions. EXPECT: every row shows the TRUE state (Allowed / Not asked yet / Off /
      Off — change in Android settings), matching Android's own app-permission page.
X9 ☐ Error Logs. EXPECT: a "PERM … refused" line for each refusal in X2–X7 and "granted" lines for grants.
X10 ☐ ⚑ Notifications denied (from X2): set a 1-minute Alarm item. Record what actually happens:
      sound yes/no, card yes/no. (Android normally hides the full-screen card when notifications are
      off. The permission text says "alarms may still sound" — confirm or correct it.)

## W. IN-APP UPDATES + SHOPPING-LIST FEATURES (v2.9 N47+N45)

W1. INSTALL 2.9 from the GitHub release (or the repo's releases/ folder) over 2.8: data intact.
W2. UPDATES SECTION — Settings → Updates shows "Installed 2.9 · latest 2.9" after Check now, and
    "You have the latest version." No notification.
W3. NEXT RELEASE (2.10) — within a day of it being published: a notification "Remindly 2.10 is
    available"; Settings → Updates shows the Update button with the size. Tap → first time Android
    opens "install unknown apps" for Remindly (allow) → tap Update again → progress to 100% → the
    system installer dialog → Install → the app reopens as 2.10 with everything intact.
W4. WI-FI ONLY — on mobile data with the switch on, Update says why it did not download.
W5. TAMPER CHECK (optional) — Error Logs after an update shows "downloaded + verified"; a wrong
    SHA would show "SHA-256 mismatch — download discarded".
W6. GROUP MENU — Buy list sorted by group: long-press a group header → Rename / Change icon /
    Share / Duplicate / Complete all / Delete list; Rename renames every item; the icon shows in
    the header; Duplicate creates "<name> copy"; Delete sends the items to the Bin.
W7. SUGGESTIONS + MIC — type 2+ letters of a product in quick add: chips appear; picking one adds
    the item linked to the product (its category chip shows on the card). The mic fills the field
    from speech.
W8. SHARE — group → Share list → WhatsApp opens with the text; Copy copies; the two toggles change
    the text; Buy ⚙ → Sharing sets the defaults and the default group for new items.
W9. SPLASH — cold start shows the brand-colour splash with the icon, then the app.

## U. DELETE FROM SCHEDULED ALERTS (v2.8 N44)

U1. MENU — Settings → Scheduled alerts → Open → ⋮ on an item row: two red entries — "Delete this
    alert (Mute this item)" and "Delete the item — and every future alert".
U2. DELETE THIS ALERT (item) → sheet says the item stays and is muted → Delete alert: the row is
    gone at once, the Undo bar appears for ~6 s, the item shows 🔕 in its list, and its due time
    passes with NO sound or notification. Undo restores the previous alert type and re-arms it.
U3. FULL DELETE (item) → the sheet lists EVERY trigger (due, occurrences, snooze, nag, quiet
    re-fire) → Delete + all alerts: the header count drops by that many; the item is in the Bin;
    nothing fires afterwards. Bin → Restore: it returns and its due re-arms.
U4. RECURRING CALL REMINDER — full delete: the weekly reminder never fires again (the pre-2.8 gap).
    Bin restore re-arms it.
U5. SHOP — full delete from a "geofence armed" row: shop gone, fence gone, an armed Buy Now for
    that shop disappears; "Delete this alert" instead = arrivals silenced but the fence (and Buy
    Now) still work.
U6. PLACE — "Delete this alert" disables the place; full delete removes it; the fence stops firing.
U7. SWIPE — swipe a row left: a red Delete strip shows, the row snaps back, and the same confirm
    sheet opens; Cancel changes nothing.
U8. SNOOZE ROW — "Delete this alert" removes the snooze and the original due re-arms.
U9. REBOOT — after muting an item and after a full delete, reboot the phone: nothing re-arms.

## T. GROUP-HEADER CHECKBOXES + SCHEDULED ALERTS + HIDDEN-TAB CURE (v2.7 N43+N42+N40)

T1. HEADERS — Tasks/Learn/Buy, Active view: every header (year, month, day, and the flat group/shop
    headers when sorting by group or shop) shows a checkbox at the left; tapping it does NOT
    collapse the group. The Personal group's checkbox shows 🔒 until the PIN is entered.
T2. COMPLETE A GROUP — tap a header checkbox → sheet lists the items → "Complete all (N)" → all
    move to Done; the dark Undo bar appears at the top of the list for ~6 s; Undo brings them back.
    Buy: the checkout calculator does NOT appear for a group (the sheet says so).
T3. RESTORE A GROUP — Done view: headers show ✓; tap → "Restore all (N)" → items return to Active.
T4. MIXED STATE — a group where a recurring item is already done-for-today shows "–"; completing
    it only completes the pending ones.
T5. BUY NOW — after an arrival, the Buy Now view shows "Bought everything here ☐ N"; tapping it
    completes the whole visit.
T6. CONTROLS — Settings → Lists → Group header checkboxes OFF hides every checkbox; Tasks ⚙ →
    Adding Items → "Group header checkboxes: On" brings them back for Tasks only (Inherit/On/Off).
T7. SCHEDULED ALERTS — Settings → Scheduled alerts shows "N triggers armed · next …"; Open → the
    page lists every armed item/call/shop/place trigger by day with time, 🔔/🔊/⏰ icon and source;
    filters Next 24 h / 7 days / All and by source work; location rows show "—".
T8. AMBER ROWS — hide the Learn tab (choose "Hide anyway"): its reminders appear amber with
    "hidden tab"; ⋮ → "Show the Learn tab" restores it; "Silence Learn alerts" sets its alerts off
    and the amber flag changes to "alerts off". A personal Buy item shows "personal 🔒" until
    unlocked.
T9. HIDE WARNING — Appearance → Visible Tabs → switch Learn OFF while it has upcoming reminders:
    the "Hide Learn?" dialog appears; "Hide + silence" hides AND silences; Cancel changes nothing.
    Switching ON never asks.
T10. THE GUARD — with a tab hidden via "Hide anyway", let one of its reminders fire: it still
    alerts, Error Logs has an "UNREACHABLE" line, and the next app open shows a toast pointing at
    Scheduled alerts.

## S. CALL SCAN IS LIVE-ONLY (v2.6.2 N41) — run on the Vivo and the Redmi

S1. FIRST INSTALL (the reported bug) — install fresh on the Vivo/Redmi, grant READ_CALL_LOG, open the
    Calls tab: it opens INSTANTLY, the list is EMPTY, and there is NO sound at all, however large the
    phone's call history is. Error Logs shows "N41 first run: watermark anchored … history ignored".
S2. UPGRADE OVER A FLOODED BUILD — on a phone that already has the junk: after updating, the AUTO
    reminders created before the anchor are gone (manual ones untouched) and nothing rings on launch.
S3. LIVE MISSED CALL — have someone call and don't answer: within ~10–60 s ONE reminder appears with
    ONE alert (its per-call style). Repeat twice more — still one alert each.
S4. TWO CALLS AT ONCE — two different numbers miss within the same minute: at most ONE alert (a
    "2 missed calls" summary), never two ring/alarm starts.
S5. APP START — force-stop and relaunch with old missed calls in the phone's log: nothing is ingested
    and nothing sounds (app start only anchors the watermark now).
S6. TAB OPEN — opening the Calls tab never triggers a scan; the tab must not stutter even on a phone
    with thousands of log rows.
S7. MANUAL SCAN — the toolbar scan button still works but can only pick up a call from the last five
    minutes (use it right after a missed call when the receiver was suppressed).
S8. LATE-WRITTEN ROW — on the Vivo (writes rows late): a missed call should still appear within a
    minute or two thanks to the retry ladder. If it never appears, note it — that is the stated
    trade-off of ignoring history, and the window length is the only dial.
S9. ERROR LOGS — each sweep logs the live window and an "ignored-as-history" count; that count should
    be 0 in normal use and non-zero only right after an upgrade.

## Q. SETTINGS-OVERLAY NAVIGATION (v2.6.1 N39) — the reported freeze

Q1. SHOP MODE — Buy ⚙ → the Buy settings page opens. Now tap **Shops**, then **Products**, then
    **Settings**: each tap CLOSES the settings page and lands on the tapped tab (no frozen window).
Q2. Repeat from **Shops ⚙** and from **Products ⚙** — same result from every gear page.
Q3. SAME TAB — with Buy ⚙ open, tap **Buy** itself: the settings page closes and you are back on the
    Buy list (previously only the back gesture could leave).
Q4. UNSAVED CHANGES — change a toggle on Buy ⚙, then tap Shops: the Save/Discard popup appears;
    Confirm keeps the change AND lands on Shops; Discard reverts AND lands on Shops.
Q5. GESTURE SWIPE — with a gear page open, swipe the page sideways: same behaviour as a tab tap.
Q6. MODE FLIP — with Buy ⚙ open, open ☰ and choose Task Mode: the overlay is gone and you are in
    Task mode (previously it stayed on top). Repeat with unsaved changes → popup first, then flip.
Q7. TASK MODE REGRESSION — Tasks ⚙ / Learn ⚙ / Calls ⚙: tapping another tab still closes and lands
    exactly as before; the back gesture still closes a gear page.

## P. ARRIVAL ALERTS + "BUY NOW" VIEW (v2.05 N37+N38) — needs a real geofenced shop

P1. SETUP — give a shop a geofence (map pin + radius). The editor now shows "On arrival" with
    Default · 🔔 Notify · 🔊 Ring · ⏰ Alarm; the explanation line under it updates as you tap.
    Save, reopen: the choice persisted. A shop WITHOUT a location shows no "On arrival" row.
P2. DEFAULT — Shops ⚙ → Geofence alerts → Default arrival alert = Notify. A shop left on
    "Default" shows "Notify" in the city row; change the default to Ring → that row follows.
P3. NOTIFY — walk into the fence with pending items: the usual notification. TAP IT → the app
    opens Shop mode → Buy → the "Buy Now · <shop>" view (not the plain Shop list).
P4. RING — set that shop to Ring, leave and re-enter after the cooldown: continuous sound +
    notification with Stop / Snooze / Open list. Stop silences; Open list lands on Buy Now.
P5. ALARM — set Alarm: full-screen card with the shop name and up to 5 items; Open list / Snooze /
    Dismiss all work; double-tap outside = dismiss (nothing scheduled, nothing changed).
P6. SNOOZE — from Ring or Alarm: a toast says "snoozed for N min"; after the cooldown the alert
    fires once more (only if items are still pending).
P7. COOLDOWN — leave and re-enter the fence immediately: NO second alert; Error Logs shows
    "within the arrival cooldown". Set the cooldown to Off in Shops ⚙ → it alerts every entry.
P8. SILENT SHOP — deselect all three chips for a shop: arriving produces NO sound/notification,
    but the Buy Now view still appears (Error Logs: "silent: empty arrival type set … Buy Now armed").
P9. BUY NOW — on arrival the switch becomes Active | Buy Now · <shop> | Done and selects Buy Now;
    it lists ONLY that shop's active items. Tap an item → it completes (calculator first if
    enabled) and leaves the list. Clear them all → "Everything here is done 🎉" and the segment
    STAYS until you tap Hide ✕.
P10. REPLACE + PERSISTENCE — arrive at a second shop: the segment, banner and list switch to it
    (only one Buy Now ever). Force-stop and relaunch, then reboot: Buy Now is still there. Tap
    Hide ✕ → back to Active | Done. Delete the armed shop → Buy Now disappears by itself.

## O. ☰ MODE DRAWER + PER-TAB SHOP SETTINGS + TAG FIX (v2.04 N35+N36)

O1. TAGS — Shops → a city with long shop names (e.g. Kalyan / "Reliance Bazar – Kalyan"): the
    "Chain" / "Local" tag sits on ONE line, sentence case; the long name truncates with … instead of
    squeezing the tag. Rotate to landscape and back: still one line.
O2. ☰ DRAWER — the hamburger appears top-left on Tasks, Learn, Calls, Settings (Task mode) and Buy,
    Shops, Products, Settings (Shop mode). Tap → "MODE" with Task Mode / Shop Mode, a check on the
    current one. Tap the other → drawer closes, the bottom nav swaps.
O3. SUB-PAGES — inside a city and in the Chains list the left slot is ← (no ☰); back returns to the
    cities list.
O4. LAST-USED RELAUNCH — switch to Shop mode, force-stop, relaunch → opens in Shop mode. Repeat from
    Task mode. There is no "Start in" setting any more (Settings has no Mode section).
O5. LEAVE-GUARD — open a settings page, change a toggle, then flip mode from the ☰ drawer: the
    Save/Discard popup appears first; Confirm keeps the change and flips, Discard reverts and flips.
O6. BUY ⚙ — opens the Buy settings page: Buy list · Shop Groups · Adding Items · Personal PIN ·
    Reset·Buy. Change "Group by shop" → back → the Buy list regroups.
O7. SHOPS ⚙ — opens Shops settings: Geofence alerts (arrive toggle, default-radius dropdown) ·
    Location & Battery · Reset·Shops. The dropdown offers the 13 stops only.
O8. PRODUCTS ⚙ — opens Products settings: Data → Export as JSON opens the share sheet.
O9. PER-TAB RESETS — Reset on the Buy page restores checkout calc / cheapest hint / group-by /
    adding defaults and leaves the arrive alert + default radius untouched; Reset on the Shops page
    does the opposite. Neither touches theme/fonts.
O10. SETTINGS TAB (Shop mode) — shows the SAME General page as Task mode (Maps & Location, API keys,
    Alerts, Appearance, …, About Me once), with the shop-green header.

## N. GOOGLE MAPS DEFAULT + OSM FALLBACK + LIMIT/LOCK + GPS PICKER + RADIUS SCALE (v2.02 N33)

N1. FRESH START (no Key B yet, this build has no Key A) — Settings → Maps & Location: Provider shows
    Google selected; "Now using · Map: OSM · Address lookup: OSM" with the reasons "Google map key not
    configured" / "Google key not added". Open the map picker: badge "OSM · Google map key not
    configured". Nothing is broken; everything works on OSM.
N2. RADIUS SCALE — place editor, shop editor, Shop Settings → Geofence alerts, Maps & Location, and
    the picker all show the SAME dropdown with exactly 50 m · 100 m · 150 m · 200 m · 250 m · 300 m ·
    400 m · 500 m · 750 m · 1000 m · 1.5 km · 2 km · 3 km. On first launch after update a toast
    reports how many stored radii were adjusted (only if any were off-scale); those shops/places
    now show the nearest stop.
N3. GPS-CENTRED PICKER — add a NEW shop → Pick on map: the map opens at your position, pin already
    placed, subtitle "Your position (±N m)". Editing an EXISTING shop opens at its saved pin. Tap
    "Current GPS location": map re-centres and the pin moves to you. Deny location permission →
    picker still opens (last area) with the notice line.
N4. PICKER SEARCH — type an address → 🔍: pin + camera move; subtitle says "Found via OSM: …"
    (or "via Google" once Key B is set). Radius dropdown in the picker draws the circle live.
N5. API KEYS DECK — Settings → API keys: three rows with STATUS ONLY, no characters anywhere; tap
    the Geocoding row → Add new (paste field hidden while typing, no eye icon) → Confirm 1 →
    Confirm 2 requires typing SAVE exactly (button disabled otherwise) → status SET, toast. Row now
    offers only Remove → Confirm 1 → type Remove → NOT SET, toast. Error Logs show "API key
    added/removed" lines with no key material. Kill + relaunch: status persists.
N6. GOOGLE ADDRESS LOOKUP (with Key B) — picker/place-editor search says "Found via Google";
    Maps & Location usage counter increments per search; "Now using · Address lookup: Google → OSM
    fallback".
N7. LIMIT + HARD LOCK — set the limit slider to a tiny value (e.g. 1,000 → use the smallest step) or,
    for a quick check, temporarily set it to 0: the next lookup falls to OSM; when the counter
    reaches the limit a toast "Google limit reached … using OSM until 1 <Mon>" appears once, the
    Google chip is DISABLED with the red "Locked until …" line, the picker badge reads "OSM · Google
    limit reached". Reboot: still locked. (Auto-unlock cannot be tested before the 1st — the pure
    roll-over is unit-tested.)
N8. INDIA TOGGLE — turning it off clamps the limit to ≤ 10,000 and the slider range shrinks; on
    again → range 70,000.
N9. RESET — General → Reset: provider back to Google, limit 56,000, India on, default radius back;
    the API key status is UNCHANGED (keys are never reset).
N10. KEY A BUILD (N34, later) — picker badge "Google · Maps SDK", Google map tiles, blue dot, tap
    to move the pin, circle for the radius; Maps SDK row reads IN APP.

## M. OPTION-D SHEETS + SETTINGS DECK (v2.01 N32) — run on 3-BUTTON nav AND gesture nav

M1. EVERY SHEET CLEARS THE BUTTONS — open each: Tasks item editor, Learn item editor, Buy item
    editor, Calls note / save-to-contacts / edit, shop editor, product editor, City editor, Chain
    editor, Share list, Shared hub. In each: the action row sits well above Back/Home/Recents with
    roughly a quarter of the screen of sheet below it (Option D). Nothing overlaps; Save always tappable.
M2. KEYBOARD — in each editor tap a text field: the keyboard rises, the sheet keeps only a small
    gap under the action row (no big empty band under the keyboard); dismiss the keyboard → the
    Option-D band returns.
M3. FULLY EXPANDED — no sheet opens half-height (drag handle at the top; content readable at once).
M4. ACTION ROWS — shop editor, product editor, City/Chain editors show Cancel (outlined) + Save
    (filled green), equal width, tall (50 dp), one row; editing an existing shop/product adds
    Delete on the left → tapping it closes the sheet and shows the same delete confirm as the 🗑 row.
M5. CITY / CHAIN EDITORS — now bottom sheets, not centered dialogs; add + rename work.
M6. GENERAL SETTINGS → first accordion is "Mode" with Start in chips (Last used / Task / Shop);
    pick each, force-stop, relaunch → lands accordingly. The same section is first in Shop Settings
    and shows the same value; opening it on one page opens it on the other (shared open-key).
M7. SHOP SETTINGS DECK — Mode, Buy list, Geofence alerts, Data, Shop Groups, Adding, Location &
    Battery, Personal PIN, Reset are ALL collapsible cards of the same look; one open at a time;
    below them the "General settings" row and About Me (once, expanded, Version 2.01 + today).
M8. GEOFENCE SLIDER — default radius for new shops moves in 50 m steps 50 m–2 km; a new shop's
    editor pre-fills that value.
M9. RESETS — General → Reset: Start in returns to Last used (Shop toggles untouched). Shop
    Settings → Reset: checkout calc / cheapest hint / arrive alert / default radius / Group by
    shop / Start in return to defaults (General theme etc. untouched).
M10. SHARE SHEETS — Close, Share as text, Send, Accept, Decline are 50 dp tall; both share sheets
    end with the Option-D band; landscape/split-screen: band still clears the buttons.

## L. ALWAYS-DUAL + SETTINGS ADJUSTMENTS (v2.00 N31) — update over v1.90 AND fresh install

L1. UPDATE-OVER-CLASSIC — (only if a v1.90 phone was left in Classic) install 2.00 over it: the
    app opens dual-mode (chip on every header, Task/Shop bars). No toggle exists anywhere to turn
    dual off — confirm by reading Shop Settings top to bottom and main Settings → Appearance.
L2. MODE CARD — Shop Settings → "Mode" shows only Start in (Last / Task / Shop); cycle it and
    verify each cold-start landing (persistence quartet: act → verify → reopen → reboot).
L3. GENERAL SETTINGS ROW — tap it in Shop Settings → lands on Task-mode Settings (title
    "Settings", subtitle "General · Task mode"); chip flips back to Shop mode.
L4. INLINED SHOP SECTIONS — below the General row: Shop Groups / Adding Items / Location &
    Battery / Personal PIN / Reset accordions open and work exactly as the old ⚙ page did; edit a
    Shop group and a new-item default; About Me appears ONCE at the very bottom, expanded, showing
    Version 2.00 and today's date.
L5. RESET · SHOP — change Checkout calculator, Cheapest suggestions, arrive alert, default radius
    and Start in; tap Reset in the inlined Reset section → all five return to defaults (calc on,
    hint on, alert on, 150 m, Last).
L6. LEAVE-GUARD (Shop Settings) — flip a toggle, then (a) tap Buy, (b) swipe the nav bar,
    (c) tap the chip: each shows "Save settings changes?" listing the change; Confirm keeps it and
    navigates; Discard reverts and navigates. Then the mirror on Task-mode Settings: change a
    global setting and tap the chip → the same popup (this exit was unguarded in v1.90).
L7. BUY ☰ — tap the hamburger on Buy → lands on the Shops tab (no drawer slides in). Buy ⚙ →
    lands on the Shop Settings tab.
L8. TASK-MODE SETTINGS — Appearance → Visible Tabs lists Tasks / Learn / Calls only, with the
    hint line about Shop mode; hiding Tasks+Learn+Calls leaves Settings alone and the Task bar
    still works.
L9. HEADER CHIP — present on Tasks, Learn, Calls, Settings, Buy, Shops, Products, Shop Settings;
    flips both ways from each.
L10. ABOUT ME — both modes read "Version 2.00 · <today dd-MMM-yyyy>"; links open.

## K. TWO-MODE REDESIGN (v1.90) — Task mode ⇄ Shop mode

Fresh install AND update-over-v1.89 both matter here (the seeding + DUAL default only fire on update).

K1. UPDATE PATH — install v1.90 over v1.89 with existing shops that carry Area text. Open the
    Shops tab in Shop mode: one city per distinct Area exists, shops sit inside them,
    area-less shops sit in "Unassigned". Kill + reopen: no duplicate cities (idempotent).
K2. MODE CHIP — every header (Tasks, Shop/Buy, Learn, Calls, Settings, Shops, Products,
    Shop Settings) shows the chip; tapping flips the mode BOTH ways; the bottom nav swaps
    between 4-tab Task set and Buy/Shops/Products/Settings.
K3. START-IN — Shop Settings → Start in: set SHOP, force-stop, relaunch → opens in Shop
    mode. Set LAST → relaunch lands wherever you were. Set TASK → relaunch in Task mode.
K4. (RETIRED in v2.00 — there is no Classic layout any more; see L1.)
K5. CITIES CRUD — add, rename, delete a city; on delete its shops appear under Unassigned
    and any buy items keep their shop names.
K6. CHAINS — add D-Mart; "＋ add to city" for two cities → two branches named
    "D-Mart – <City>" with the default radius; edit one branch name (stays edited). Delete
    the chain choosing KEEP: branches remain as local shops. Re-create, delete choosing
    DELETE BRANCHES: branches gone from the city lists; the default-shop star is not stranded.
K7. SHOP EDITOR — new shop in a city: radius slider moves in 50 m steps only, 50 m–2 km;
    default-radius setting (K10) pre-fills; map pick + Clear both work; default switch
    demotes the previous default (star moves).
K8. PRODUCTS — add "Basmati Rice / Grocery / kg", tick two shops with ₹/kg on each; the row
    shows "at 2 shops" and the green best line names the cheaper shop. Search and the
    category chips filter. Delete the product: buy items that referenced it keep their text.
K9. BUY ↔ PRODUCT — in Shop mode Buy → add item, type "bas": the 📦 chip appears; tap it →
    title/unit fill and the shop pre-fills to the best-price shop; the "Linked to product"
    line shows with Unlink. Free text (no chip tap) still saves fine. The cheapest hint row
    obeys its toggle (K10) and prefers the product's link prices.
K10. SHOP SETTINGS — every toggle round-trips (act → verify → reopen → verify → reboot →
    verify): Group by shop regroups Buy; Checkout calculator OFF → completing a Shop item
    skips the dialog entirely; Cheapest suggestions OFF → no hint row; arrive alert OFF →
    walk into a geofenced shop with pending items → NO notification, but Error Logs shows
    the suppressed line; default radius cycler steps 50→500 and pre-fills K7.
K11. CHECKOUT WRITE-BACK — complete a linked buy item via the calculator with a price at
    shop A; Products tab now shows that price as A's (and best updates if cheaper).
K12. AMPLE CLEARANCE (your 14-Aug report, on 3-button AND gesture nav) — open the shop
    editor sheet, the product editor sheet, and the Buy item editor: every action row sits
    clearly ABOVE the Android Back/Home/Recents with visible breathing room; "Save" always
    tappable.
K13. ABOUT ME — present and expanded at the bottom of BOTH Settings surfaces; version line
    reads 1.90 with today's date; mail/LinkedIn/Facebook links open.
K14. SHARE ROUTING — accept a shared shopping list (section I flow): the app lands on Shop
    mode's Buy surface with the prefill sheet; a Tasks share lands in Task mode.

## J. NAV-BAR CLEARANCE (v1.88 N27) — the fix for your untappable "Share as text"

J1 ☐ 3-BUTTON NAV phone: open the share sheet from a group header — the "Share as text" and
      Send row sits fully ABOVE Back/Home/Recents; both buttons tap cleanly. Signed-out state:
      the Close button also clears the bar.
J2 ☐ Same phone: open the Shared hub — the last list row in Receive AND in Sent is fully
      visible above the buttons (both signed-in and signed-out states).
J3 ☐ Calls tab → edit a reminder (the bottom sheet): its last row (the Add row) now clears the
      nav buttons too — this sheet was also missing the spacer and got fixed in v1.88.
J4 ☐ GESTURE-NAV phone: all three sheets show a comfortable bottom gap, nothing cramped.
