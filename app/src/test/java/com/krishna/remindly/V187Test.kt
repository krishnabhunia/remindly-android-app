package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.87 (N17) — list sharing, the pure layer. Firestore itself is device-checklist territory;
 * everything a unit can prove is proven here: the Q8 whitelist (price NEVER rides the wire),
 * the Q2 replace-on-accept dedupe, the 200-item cap, the block filter, and the legal-only
 * status transitions.
 */
class V187Test {

    private fun item(
        id: Long, title: String, tab: Tab = Tab.SHOP, note: String = "",
        qty: String? = null, unit: String? = null, price: String? = null,
        done: Boolean = false, personal: Boolean = false,
        dueAt: Long? = 123L, snoozedUntil: Long? = 99L, alertType: String = "A"
    ) = Item(
        id = id, tab = tab, title = title, notes = note, quantity = qty, unit = unit,
        price = price, done = done, personal = personal, dueAt = dueAt,
        snoozedUntil = snoozedUntil, alertType = alertType
    )

    private fun rec(
        id: String, from: String = "a@x.com", listName: String = "Groceries",
        status: String = SHARE_PENDING, tab: String = "SHOP",
        receiverDeleted: Boolean = false, senderDeleted: Boolean = false, sentAt: Long = 1L
    ) = ShareRecord(id, "uidA", from, "b@x.com", tab, listName,
        listOf(SharedItem("Milk")), sentAt, status, receiverDeleted, senderDeleted)

    // ---------------- Q8: the whitelist ----------------

    @Test fun wire_items_carry_exactly_the_whitelist_and_never_price() {
        val src = item(1, "Milk", qty = "2", unit = "L", note = "full fat", price = "89")
        val w = sharedItemsOf(listOf(src), Tab.SHOP).single()
        assertEquals("Milk", w.title); assertEquals("full fat", w.note)
        assertEquals("2", w.quantity); assertEquals("L", w.unit)
        // The TYPE is the proof: SharedItem has no price/date/alert/done/snooze fields at all.
        // Compose adds a synthetic ${'$'}stable marker — strip synthetics before asserting.
        val fields = SharedItem::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("${'$'}") }.map { it.name }.toSet()
        assertEquals(setOf("title", "note", "quantity", "unit"), fields)
    }

    @Test fun tasks_share_title_and_note_only() {
        val w = sharedItemsOf(listOf(item(1, "Call bank", tab = Tab.TASKS, note = "ref 44", qty = "9", unit = "kg")), Tab.TASKS).single()
        assertEquals("", w.quantity); assertEquals("", w.unit)
        assertEquals("ref 44", w.note)
    }

    // ---------------- selection + cap ----------------

    @Test fun default_selection_skips_done_and_locked() {
        val locked = item(3, "Gift", personal = true)
        val sel = defaultShareSelection(
            listOf(item(1, "Milk"), item(2, "Eggs", done = true), locked)
        ) { it.personal }
        assertEquals(setOf(1L), sel)
    }

    @Test fun the_cap_is_two_hundred_inclusive() {
        assertTrue(validShareCount(1)); assertTrue(validShareCount(SHARE_ITEM_CAP))
        assertFalse(validShareCount(0)); assertFalse(validShareCount(SHARE_ITEM_CAP + 1))
    }

    // ---------------- filters ----------------

    @Test fun blocked_senders_vanish_before_render() {
        val shares = listOf(rec("1", from = "Bad@X.com"), rec("2", from = "ok@x.com"))
        val vis = pendingReceived(shares, blocked = setOf("bad@x.com"))
        assertEquals(listOf("2"), vis.map { it.id })   // case-folded match
    }

    @Test fun receive_lists_split_and_hide_deleted() {
        val shares = listOf(
            rec("p1"), rec("a1", status = SHARE_ACCEPTED),
            rec("a2", status = SHARE_ACCEPTED, receiverDeleted = true),
            rec("d1", status = SHARE_DECLINED), rec("r1", status = SHARE_REVOKED)
        )
        assertEquals(listOf("p1"), pendingReceived(shares, emptySet()).map { it.id })
        assertEquals(listOf("a1"), acceptedReceived(shares, emptySet()).map { it.id })
    }

    @Test fun sender_view_hides_sender_deleted_only() {
        val shares = listOf(rec("1"), rec("2", senderDeleted = true), rec("3", receiverDeleted = true))
        assertEquals(listOf("3", "1"), visibleSent(shares).map { it.id }.sortedDescending())
    }

    // ---------------- Q2: replace on accept ----------------

    @Test fun accepting_a_reshare_retires_the_older_copy_same_sender_same_name() {
        val old = rec("old", from = "A@x.com", status = SHARE_ACCEPTED, sentAt = 1)
        val other = rec("other", from = "a@x.com", listName = "Pharmacy", status = SHARE_ACCEPTED)
        val fresh = rec("new", from = "a@X.com", status = SHARE_PENDING, sentAt = 2)
        assertEquals(listOf("old"), replaceKeysOnAccept(listOf(old, other, fresh), fresh))
    }

    // ---------------- legal transitions ----------------

    @Test fun only_the_four_legal_moves_pass() {
        assertTrue(canTransition(SHARE_PENDING, SHARE_ACCEPTED, byRecipient = true))
        assertTrue(canTransition(SHARE_PENDING, SHARE_DECLINED, byRecipient = true))
        assertTrue(canTransition(SHARE_PENDING, SHARE_REVOKED, byRecipient = false))
        assertFalse(canTransition(SHARE_PENDING, SHARE_ACCEPTED, byRecipient = false))
        assertFalse(canTransition(SHARE_PENDING, SHARE_REVOKED, byRecipient = true))
        assertFalse(canTransition(SHARE_ACCEPTED, SHARE_DECLINED, byRecipient = true))
        assertFalse(canTransition(SHARE_DECLINED, SHARE_ACCEPTED, byRecipient = true))
        assertFalse(canTransition(SHARE_REVOKED, SHARE_ACCEPTED, byRecipient = true))
    }

    // ---------------- text fallback + defensive parse + gates ----------------

    @Test fun share_as_text_reads_like_a_list() {
        val t = shareAsText("Groceries", listOf(
            SharedItem("Milk", quantity = "2", unit = "L"),
            SharedItem("Bread", note = "brown")
        ))
        assertEquals("Groceries\n\u2022 Milk (2 L)\n\u2022 Bread \u2014 brown", t)
    }

    @Test fun malformed_documents_parse_to_safe_defaults_never_crash() {
        val r = shareRecordOf("x", mapOf("items" to listOf(mapOf("title" to "Milk"), "junk"), "sentAt" to "NaN"))
        assertEquals(1, r.items.size)
        assertEquals(SHARE_PENDING, r.status)
        assertEquals(0L, r.sentAt)
        assertFalse(r.receiverDeleted)
    }

    @Test fun share_switch_resolves_per_tab_then_global_and_never_learn_calls() {
        val s = AppSettings(shareOn = true)
        assertTrue(shareEnabledFor(Tab.TASKS, s))
        assertFalse(shareEnabledFor(Tab.TASKS, s.copy(tasksShareOn = "OFF")))
        assertTrue(shareEnabledFor(Tab.SHOP, s.copy(shareOn = false, shopShareOn = "ON")))
        assertFalse(shareEnabledFor(Tab.LEARN, s))
        assertFalse(shareEnabledFor(null, s))
    }

    @Test fun plausible_email_is_a_light_gate_only() {
        assertTrue(plausibleEmail("a@bcd.co"))
        assertFalse(plausibleEmail("nope")); assertFalse(plausibleEmail("a b@c.com"))
    }
}
