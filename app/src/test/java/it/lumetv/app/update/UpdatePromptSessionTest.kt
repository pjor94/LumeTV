package it.lumetv.app.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePromptSessionTest {
    @Test fun `later suppresses the same version until the next launch`() {
        val session = UpdatePromptSession("first")
        assertTrue(session.shouldOffer(17))
        session.acknowledge(17)
        assertFalse(session.shouldOffer(17))
        assertTrue(session.shouldOffer(18))
        session.enter("next")
        assertTrue(session.shouldOffer(17))
    }

    @Test fun `recreation and interface changes retain the acknowledgement`() {
        val original = UpdatePromptSession("first").apply { acknowledge(17) }
        val restored = UpdatePromptSession(original.launchId, original.snapshot())
        assertFalse(restored.shouldOffer(17))
        restored.acknowledge(18)
        assertFalse(UpdatePromptSession(restored.launchId, restored.snapshot()).shouldOffer(18))
        assertTrue(original.shouldOffer(18))
    }

    @Test fun `a fresh launch clears acknowledgement even when Android restores old screen state`() {
        val restored = UpdatePromptSession("old", intArrayOf(17))
        restored.enter("new")
        assertTrue(restored.shouldOffer(17))
        restored.enter("new", intArrayOf(17))
        assertFalse(restored.shouldOffer(17))
    }
}
