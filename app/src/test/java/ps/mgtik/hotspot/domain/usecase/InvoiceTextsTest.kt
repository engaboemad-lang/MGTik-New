package ps.mgtik.hotspot.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class InvoiceTextsTest {
    @Test fun dateOnlyTakesIsoDatePartOrEmpty() {
        assertEquals("2026-10-29", InvoiceTexts.dateOnly("2026-10-29 13:00:00"))
        assertEquals("", InvoiceTexts.dateOnly(null))
        assertEquals("", InvoiceTexts.dateOnly("bad"))
    }

    @Test fun ltrIsolatesNumbersInsideRtlText() {
        // الأرقام والعملة تُعزل (LRI..PDI) كي لا تنقلب داخل فقرة عربية
        assertEquals("\u206650.00 ₪\u2069", InvoiceTexts.ltr("50.00 ₪"))
    }
}
