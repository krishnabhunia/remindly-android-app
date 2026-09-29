# Remindly for Windows 1.0.1 — DEVICE CHECKLIST — 16-Sep-2026

Everything below is compiled, unit-tested and emulator-tested. On real Windows so far: 1.0.0 launched
and showed the main window, but every dialog failed (fixed in 1.0.1 — see README). Order chosen so the
launch, the dialogs and sign-in are proven first. Tick each ☐; anything odd →
screenshot + Settings → *Show error log* (or `%LOCALAPPDATA%\Remindly\remindly.log`).

## W. LAUNCH + SIGN-IN

W1 ☐ Run `Join-Remindly.cmd` → `Remindly.exe` appears (~68 MB); SHA-256 matches `Remindly.exe.sha256`.
W2 ☐ Launch. EXPECT: window ~1240×820, Task mode, sidebar Tasks / Learn / Calls / Scheduled alerts /
      Recently deleted / Settings, "Not signed in", "Signed out". Title bar buttons work; Win11 rounded
      corners. (SmartScreen may warn once.)
W2b ☐ **1.0.1 regression check** — open each dialog once; none may show "Something went wrong":
      Sign in with Google (setup dialog) · Shop mode → Shops → + New shop · Products → + New product ·
      Settings → Personal PIN → Set PIN (PIN dialog) · shop editor → Pick on map. EXPECT: every dialog
      opens centred over the main window.
W3 ☐ Sidebar → Sign in with Google → setup dialog → drop the client JSON → Save client. Sign in
      again → browser → same Gmail as the phone → "Signed in to Remindly" page → app asks to turn
      on Cloud sync → Yes. EXPECT: ● Connecting… then ● Live within ~10 s; the phone's lists appear.
W4 ☐ Quit (tray → Quit) and relaunch. EXPECT: still signed in (no browser), Live again, same lists.

## X. LISTS + EDITORS (Task mode)

X1 ☐ Tasks: type "Buy milk" + Enter. EXPECT: appears under Tomorrow (or your default), phone shows
      it within seconds. Double-click → editor opens on the right; change priority to High → Save →
      phone shows High.
X2 ☐ Repeat: new task → Repeat → Weekly Mon+Wed, 09:30 → preview shows 3 dates → Save. Complete it
      (checkbox). EXPECT: goes to Done with "Done · returns <next>"; Undo bar works; phone agrees.
X3 ☐ No reminders: set an item to No reminders → Save. EXPECT: dateless, listed under "No date · added…".
X4 ☐ Learn: add a course with platform, link, 40 % progress. EXPECT: progress bar on the card; the
      link button opens the URL.
X5 ☐ Sort: click the sort button → By group → By priority → By date; groups collapse/expand; the
      choice syncs to the phone's sort for that tab.
X6 ☐ Group header checkbox → "Complete all (N)" confirm → all Done; Undo restores.
X7 ☐ Search box filters as you type; clearing restores.

## Y. SHOP MODE

Y1 ☐ Switch to Shop mode (sidebar segment). EXPECT: Buy / Shops / Products; the last mode reopens
      on next launch.
Y2 ☐ Shops → ＋ City "Kalyan" → ＋ Shop "D-Mart – Kalyan" in Kalyan, Pick on map (WebView2 runtime
      present) → click the map → Use this location → radius 150 m → Save. EXPECT: row shows geofence
      + "On arrival: Default (Notify)"; phone shows the shop with the pin.
Y3 ☐ Products → ＋ Product "Basmati Rice · Grocery · kg", tick D-Mart with ₹440 / ₹88 → Save.
      EXPECT (only after the 2.9 rules are published): appears on the phone's Products tab. Before
      that: Settings shows "cities/chains/products/productlinks — rules missing" and the note on Shops.
Y4 ☐ Buy → type "bas" → 📦 chip lists Basmati Rice → pick. EXPECT: unit kg, shop D-Mart, cheapest
      hint line. Save.
Y5 ☐ Complete the buy item. EXPECT: Checkout dialog; unit 88 × qty 5 → cost 440 auto; paid 420 →
      "saved ₹20 (4.5%)" → Record & complete. Products shows ₹88/kg at D-Mart; item in Done.
Y6 ☐ Shops → "Buy Now" on D-Mart. EXPECT: Buy shows the "Buy Now · D-Mart" segment with only its
      pending items; Hide ✕ returns to Active | Done.
Y7 ☐ Personal: mark a buy item Personal → Save → sidebar "Personal items locked" → the row shows
      "Personal item" with a lock; click the lock → set a PIN → row reveals; relaunch → locked again.

## Z. ALERTS ON THE PC

Z1 ☐ Settings → Alert tests: Notification (card bottom-right + chime), Ring (card + looping sound,
      Stop sound works, auto-stops after Ring seconds), Test Alarm (full-screen red card with clock,
      sound; every button closes it with "Test — no action taken").
Z2 ☐ Real Notify: task due in 2 min, type Notify. EXPECT: card at due time; Done completes it on both
      devices; the phone ALSO rings (both alert — by design; switch off *Ring / notify on this PC* if unwanted).
Z3 ☐ Real Alarm: due in 2 min, type Alarm. EXPECT: full-screen card; Snooze → card closes, editor
      "Coming up: Snoozed · Alarm · <time>" on both devices; fires again after the snooze.
Z4 ☐ Quiet on the PC → the phone's editor shows Reminder Type Notify (N13 permanence).
Z5 ☐ Close the window (✕) → tray balloon; alerts still fire from the tray; double-click tray → window.
Z6 ☐ Tray → Pause alerts for 1 hour → title bar shows "Alerts paused"; due items don't ring; Resume.
Z7 ☐ Quit with a task due in 5 min; relaunch after it passed. EXPECT: ONE "While you were away"
      card listing it (not an alarm). Switch it off in Settings if unwanted.
Z8 ☐ Start with Windows ON → sign out of Windows and back in. EXPECT: Remindly in the tray, no window.

## AA. SCHEDULED ALERTS + BIN + BACKUP

AA1 ☐ Scheduled alerts: every armed item/call listed with time, 🔔/🔊/⏰, source; 24 h / 7 d / All.
AA2 ☐ Row ⋯ "Delete this alert" → item shows 🔕 muted; Undo restores the type. "Delete the item —
      and every future alert" → Bin; nothing fires later.
AA3 ☐ Delete an item → Recently deleted; Restore brings it back; Delete forever removes it locally
      (the phone keeps its tombstone in its own bin, as designed).
AA4 ☐ Settings → Export data → file; import the same file on the phone (Backup & Restore) → nothing
      duplicates. Export from the phone → Import on the PC → same.

## AB. CALLS

AB1 ☐ Miss a call on the phone. EXPECT: appears on the PC's Calls tab within seconds with the Auto
      pill; **Call** opens Phone Link's dialer (or asks which app handles `tel:`); WhatsApp opens wa.me.
AB2 ☐ PC: ＋ Call reminder, tomorrow 18:00, Repeat weekly Mon. EXPECT: phone shows the Manual reminder
      with "↻"; the PC fires at 18:00 (Notify card) if alerts are on.

## AC. THEME + SETTINGS SYNC

AC1 ☐ Change Theme to Dark on the phone. EXPECT: the PC follows within seconds (unless "This PC
      follows the synced theme" is off).
AC2 ☐ Change Snooze minutes on the PC to 30. EXPECT: the phone's chip row shows 30 m selected.
AC3 ☐ Hide the Learn tab on the PC (Settings → Lists). EXPECT: sidebar loses Learn; the phone's
      Visible Tabs shows Learn off.
