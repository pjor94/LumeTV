package it.lumetv.app.ui

import android.text.InputType
import it.lumetv.app.data.PlaylistAccessRule
import it.lumetv.app.data.PlaylistResource
import it.lumetv.app.data.PlaylistSettings
import it.lumetv.core.PlaylistContentKind
import it.lumetv.core.PlaylistEncoding
import it.lumetv.core.PlaylistFormat
import it.lumetv.core.PlaylistFormatException
import it.lumetv.core.PlaylistGroupProfile
import it.lumetv.core.PlaylistReadOptions

/** Editors are ordinary pages. Access changes stay staged until the page is saved. */
internal class PlaylistAdvancedEditor(private val pages:PlaylistPages) {
    fun reading(initial:PlaylistSettings,subtitle:String="",save:suspend(PlaylistSettings)->Unit) {
        pages.push("reading","Lettura e contenuti",subtitle){page->
            val kind=page.choice("Tipo dei contenuti",listOf("Automatico · indicazione di ogni voce","Tutte dirette","Tutti video on demand"),when(initial.contentOverride){PlaylistContentKind.LIVE->1;PlaylistContentKind.VOD->2;else->0})
            val format=page.choice("Formato della lista",listOf("M3U estesa · nomi e metadati","M3U semplice · un indirizzo per riga"),initial.reading.format.ordinal)
            val encoding=page.choice("Codifica del testo",listOf("UTF-8","Windows-1252","Latin-1"),initial.reading.encoding.ordinal)
            val groups=page.choice("Compatibilità dei gruppi",listOf("Attuale · gruppo ereditato","Kodi","OTT Navigator · singola voce"),initial.reading.groups.ordinal)
            val interval=page.field("Aggiornamento automatico, in minuti",initial.refreshMinutes?.toString().orEmpty(),5).apply {inputType=InputType.TYPE_CLASS_NUMBER}
            page.text("Da 15 minuti a 7 giorni. Vuoto: usa l’indicazione della lista, oppure 24 ore. Il controllo avviene mentre usi l’app.")
            page.text("Formato, codifica e gruppi si applicano alla prossima verifica, al prossimo aggiornamento o alla sostituzione del file.")
            fun fingerprint()=listOf(pages.selected(kind),pages.selected(format),pages.selected(encoding),pages.selected(groups),interval.text.toString()).toString()
            page.protect(::fingerprint)
            page.action("Annulla"){pages.pop()}
            page.action("Salva modifiche",true){
                try {
                    val raw=interval.text.toString().trim()
                    val minutes=if(raw.isBlank())null else raw.toIntOrNull() ?: throw PlaylistFormatException("Inserisci un intervallo in minuti valido.")
                    val next=initial.copy(reading=PlaylistReadOptions(PlaylistFormat.entries[pages.selected(format)],PlaylistEncoding.entries[pages.selected(encoding)],PlaylistGroupProfile.entries[pages.selected(groups)]),
                        contentOverride=when(pages.selected(kind)){1->PlaylistContentKind.LIVE;2->PlaylistContentKind.VOD;else->null},refreshMinutes=minutes).validated()
                    pages.run(page,"Salvo le impostazioni…"){save(next);pages.saved(page)}
                }catch(error:PlaylistFormatException){page.message(error.message.orEmpty(),true)}
            }
        }
    }
    fun access(initial:PlaylistSettings,subtitle:String="",save:suspend(PlaylistSettings)->Unit) {
        pages.push("access","Accesso ai server",subtitle){page->
            var rules=initial.access
            page.text("Configura solo le funzioni che richiedono dati di accesso. Gli header restano separati per lista e funzione.")
            val rows=linkedMapOf<PlaylistResource,OptionsRowView>()
            fun updateLabels(){rows.forEach {(role,row)->val rule=rules[role] ?: PlaylistAccessRule();row.bind(role.label,if(rule.headers.isEmpty() && rule.origins.isEmpty())"Nessun header aggiuntivo" else "${rule.headers.size} header · ${rule.origins.size} altri server")}}
            PlaylistResource.entries.forEach {role->rows[role]=page.row(role.label){rule(role,rules[role] ?: PlaylistAccessRule(),subtitle){value->rules=rules+(role to value)}}}
            val cookies=page.toggle("Mantieni cookie di sessione",initial.sessionCookies)
            page.text("I cookie restano nella sessione di questa lista. Cambiare accesso o disattivarla li rimuove.")
            val artwork=page.toggle("Consenti HTTP per i loghi",initial.artworkHttp)
            page.text("Riguarda soltanto le immagini. Con HTTP, indirizzi e dati di accesso viaggiano senza cifratura.")
            page.onRefresh=::updateLabels;updateLabels()
            page.protect {initial.copy(access=rules,sessionCookies=cookies.isChecked,artworkHttp=artwork.isChecked).json().toString()}
            page.action("Annulla"){pages.pop()}
            page.action("Salva modifiche",true){
                try {val next=initial.copy(access=rules,sessionCookies=cookies.isChecked,artworkHttp=artwork.isChecked).validated()
                    pages.run(page,"Salvo l’accesso…"){save(next);pages.saved(page)}
                }catch(error:PlaylistFormatException){page.message(error.message.orEmpty(),true)}
            }
        }
    }
    fun rule(role:PlaylistResource,initial:PlaylistAccessRule,subtitle:String="",save:suspend(PlaylistAccessRule)->Unit) {
        pages.push("access-${role.name}",role.label,subtitle){page->
            val headers=page.field("Header HTTP · un header per riga",initial.headers.entries.joinToString("\n"){"${it.key}: ${it.value}"},32*1024,true)
            page.text("Scrivi Nome: valore. Usa i dati forniti dal servizio; questi valori prevalgono su quelli delle singole voci.")
            val origins=page.field("Altri server autorizzati · uno per riga",initial.origins.joinToString("\n"),16*1024,true)
            page.text("Il server iniziale è già autorizzato. Per gli altri indica protocollo e porta, senza percorso: per esempio https://cdn.example.com.")
            page.protect {headers.text.toString()+"\u0000"+origins.text.toString()}
            page.action("Annulla"){pages.pop()}
            page.action("Salva accesso",true){
                try {
                    val servers=origins.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
                    if(servers.size>16)throw PlaylistFormatException("Puoi autorizzare fino a 16 server per funzione.")
                    val rule=PlaylistAccessRule(PlaylistSettings.parseHeaders(headers.text.toString()),servers.map(PlaylistSettings::canonicalOrigin).toSet())
                    pages.run(page,"Salvo l’accesso…"){save(rule);pages.saved(page,"Accesso aggiornato.")}
                }catch(error:PlaylistFormatException){page.message(error.message.orEmpty(),true)}
            }
        }
    }
}
