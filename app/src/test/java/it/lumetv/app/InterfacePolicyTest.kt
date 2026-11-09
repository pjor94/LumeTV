package it.lumetv.app

import org.junit.Assert.assertEquals
import org.junit.Test

class InterfacePolicyTest {
    @Test fun televisionFeaturesTakePrecedenceOverTouchAndDisplaySize() {
        assertEquals(InterfaceMode.TV, InterfacePolicy.resolve(InterfaceMode.AUTOMATIC, true, true, 411))
    }

    @Test fun phonesUseTheTouchInterface() {
        for (width in listOf(320, 360, 411, 599))
            assertEquals(InterfaceMode.SMARTPHONE, InterfacePolicy.resolve(InterfaceMode.AUTOMATIC, false, true, width))
    }

    @Test fun tabletsBoxesAndUnknownDisplaysKeepTheTvInterface() {
        for (width in listOf(0, 600, 840, 1280))
            assertEquals(InterfaceMode.TV, InterfacePolicy.resolve(InterfaceMode.AUTOMATIC, false, true, width))
        assertEquals(InterfaceMode.TV, InterfacePolicy.resolve(InterfaceMode.AUTOMATIC, false, false, 411))
    }

    @Test fun manualPreferenceOverridesAutomaticClassification() {
        assertEquals(InterfaceMode.SMARTPHONE, InterfacePolicy.resolve(InterfaceMode.SMARTPHONE, true, false, 960))
        assertEquals(InterfaceMode.TV, InterfacePolicy.resolve(InterfaceMode.TV, false, true, 360))
    }

    @Test fun missingOrUnknownPreferenceFallsBackToAutomatic() {
        assertEquals(InterfaceMode.AUTOMATIC, InterfaceMode.fromStored(null))
        assertEquals(InterfaceMode.AUTOMATIC, InterfaceMode.fromStored("obsolete"))
        assertEquals(InterfaceMode.SMARTPHONE, InterfaceMode.fromStored("SMARTPHONE"))
    }
}
