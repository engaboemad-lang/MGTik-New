package ps.mgtik.hotspot.domain.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportCsvTest {
    @Test fun cellEscapesCommasQuotesAndNewlines() {
        assertEquals("plain", ReportCsv.cell("plain"))
        assertEquals("\"a,b\"", ReportCsv.cell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", ReportCsv.cell("say \"hi\""))
        assertEquals("\"x\ny\"", ReportCsv.cell("x\ny"))
    }

    @Test fun cellBlocksSpreadsheetFormulasOnlyWhenGuarded() {
        assertEquals("'=SUM(A1)", ReportCsv.cell("=SUM(A1)", guardFormula = true))
        assertEquals("'@cmd", ReportCsv.cell("@cmd", guardFormula = true))
        assertEquals("-5.00", ReportCsv.cell("-5.00")) // الأرقام السالبة المشروعة لا تُمسّ
    }

    @Test fun buildWritesScalarsAndSections() {
        val data = linkedMapOf<String, Any?>(
            "invoicesCount" to 2,
            "byMethod" to listOf(mapOf("label" to "cash", "amountMinor" to 1250L, "count" to 1))
        )
        val csv = ReportCsv.build(data, listOf("S", "I", "M", "V"), { it }, { _, v -> v.toString() })
        val lines = csv.removePrefix("\uFEFF").trim().lines()
        assertEquals("S,I,M,V", lines[0])
        assertEquals(",,invoicesCount,2", lines[1])
        assertEquals("byMethod,cash,amountMinor,1250", lines[2])
        assertEquals("byMethod,cash,count,1", lines[3])
        assertTrue(csv.startsWith("\uFEFF"))
    }
}
