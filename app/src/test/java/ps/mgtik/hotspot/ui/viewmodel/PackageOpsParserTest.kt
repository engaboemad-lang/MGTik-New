package ps.mgtik.hotspot.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackageOpsParserTest {
    @Test fun blankValidityIsNotSetAndNeverZero() {
        assertNull(PackageOpsParser.validityHours(PackageOps()))
        assertNull(PackageOpsParser.sharedUsers(PackageOps()))
    }

    @Test fun validityConvertsUnitsToHours() {
        assertEquals(48, PackageOpsParser.validityHours(PackageOps(validityText = "2", validityUnit = ValidityUnit.DAYS)))
        assertEquals(720, PackageOpsParser.validityHours(PackageOps(validityText = "1", validityUnit = ValidityUnit.MONTHS)))
        assertEquals(5, PackageOpsParser.validityHours(PackageOps(validityText = "5", validityUnit = ValidityUnit.HOURS)))
    }

    @Test fun zeroAndGarbageAreErrorsNotUnlimited() {
        assertEquals(-1, PackageOpsParser.validityHours(PackageOps(validityText = "0")))
        assertEquals(-1, PackageOpsParser.sharedUsers(PackageOps(sharedUsersText = "0")))
        assertEquals(-1, PackageOpsParser.sharedUsers(PackageOps(sharedUsersText = "10000")))
    }

    @Test fun unlimitedIsExplicitAndIgnoresText() {
        assertNull(PackageOpsParser.validityHours(PackageOps(validityText = "3", validityUnlimited = true)))
        assertNull(PackageOpsParser.sharedUsers(PackageOps(sharedUsersText = "3", sharedUnlimited = true)))
    }
}
