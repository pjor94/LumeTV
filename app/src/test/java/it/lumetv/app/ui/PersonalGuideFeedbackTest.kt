package it.lumetv.app.ui

import it.lumetv.app.data.PersonalGuideStatus
import org.junit.Assert.*
import org.junit.Test

class PersonalGuideFeedbackTest {
    @Test fun `guide feedback distinguishes loading errors matching and an empty schedule`() {
        assertTrue(PersonalGuideFeedback.message(null,false).contains("disattivata"))
        assertTrue(PersonalGuideFeedback.message(PersonalGuideStatus("a",refreshing=true)).startsWith("Aggiorno"))
        assertTrue(PersonalGuideFeedback.message(PersonalGuideStatus("a",error="Server non disponibile.")).contains("riprova"))
        assertTrue(PersonalGuideFeedback.message(PersonalGuideStatus("a",updated=1)).contains("nessun canale associato"))
        assertTrue(PersonalGuideFeedback.message(PersonalGuideStatus("a",updated=1,matched=3)).contains("questa fascia"))
        assertFalse(PersonalGuideFeedback.message(null).contains("non ha una guida"))
    }
}
