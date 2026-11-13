package it.lumetv.app.ui

import it.lumetv.app.data.PersonalGuideStatus

/** Empty schedules do not imply that a playlist has no EPG configuration. */
internal object PersonalGuideFeedback {
    fun message(status:PersonalGuideStatus?,enabled:Boolean=true):String=when {
        !enabled->"La guida di questa lista è disattivata. Puoi riattivarla in Le mie liste, Guida e archivio."
        status?.refreshing==true->"Aggiorno la guida della lista…"
        status?.error?.isNotBlank()==true->"${status.error} Controlla Guida e archivio in Le mie liste e riprova ad aggiornare."
        status!=null && status.updated>0 && status.matched==0->"Guida scaricata, ma nessun canale associato. Controlla Associa canali in Le mie liste, Guida e archivio."
        status!=null && status.updated>0->"Nessun programma in questa fascia. Cambia giorno o aggiorna la guida."
        else->"Nessun programma disponibile. Controlla la guida in Le mie liste, Guida e archivio, quindi aggiorna."
    }
}
