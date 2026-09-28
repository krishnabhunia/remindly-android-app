package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.58 schema pin. Parser tests removed in v1.69 with the rules machinery (Q4/Q5 purge). */
class V158Test {
    @Test fun schema_version_is_pinned() {
        assertEquals(41, AppSettings().ver)   // v2.04 schema
    }
}
