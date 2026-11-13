package it.lumetv.app.ui

import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import it.lumetv.app.data.PlaylistAccessRule
import it.lumetv.app.data.PlaylistResource
import it.lumetv.app.data.PlaylistSettings
import it.lumetv.core.PlaylistContentKind
import it.lumetv.core.PlaylistEncoding
import it.lumetv.core.PlaylistFormat
import it.lumetv.core.PlaylistFormatException
import it.lumetv.core.PlaylistGroupProfile
import it.lumetv.core.PlaylistReadOptions
import it.lumetv.core.PlaylistVodDetection

/** Editors are ordinary pages. Access changes stay staged until the page is saved. */
internal class PlaylistAdvancedEditor(private val pages:PlaylistPages) {
    fun reading(initial:PlaylistSettings,subtitle:String="",groupChoices:(suspend(String,Int)->Pair<List<String>,Int>)?=null,save:suspend(PlaylistSettings)->Unit) {
        pages.push("reading","Lettura e contenuti",subtitle){page->
            var groupContent=initial.reading.groupContent
            val kind=page.choice("Tipo dei contenuti",listOf("Automatico · indicazione di ogni voce","Tutte dirette","Tutti video on demand"),when(initial.contentOverride){PlaylistContentKind.LIVE->1;PlaylistContentKind.VOD->2;else->0})
            val detection=page.choice("Riconoscimento VOD",listOf("Prudente · indicazioni e percorsi riconoscibili","Esteso · anche gruppi video e file multimediali"),initial.reading.vodDetection.ordinal)
            page.text("Esteso riconosce anche un file video in un gruppo come Film o Serie. Il solo nome del gruppo o la sola estensione non bastano.")
            val ruleRow=page.row("Regole per i gruppi") {groupRules({groupContent},{groupContent=it},subtitle,groupChoices)}
            fun ruleLabel(){ruleRow.bind("Regole per i gruppi",if(groupContent.isEmpty())"Nessuna regola manuale" else "${groupContent.size} regole · Dirette o Video per gruppo")}
            page.onRefresh=::ruleLabel;ruleLabel()
            page.text("Riconoscimento e regole si applicano con Tipo dei contenuti Automatico. Tutte dirette e Tutti video on demand hanno la precedenza.")
            val format=page.choice("Formato della lista",listOf("M3U estesa · nomi e metadati","M3U semplice · un indirizzo per riga"),initial.reading.format.ordinal)
            val encoding=page.choice("Codifica del testo",listOf("UTF-8","Windows-1252","Latin-1"),initial.reading.encoding.ordinal)
            val groups=page.choice("Compatibilità dei gruppi",listOf("Attuale · gruppo ereditato","Kodi","OTT Navigator · singola voce"),initial.reading.groups.ordinal)
            val interval=page.field("Aggiornamento automatico, in minuti",initial.refreshMinutes?.toString().orEmpty(),5).apply {inputType=InputType.TYPE_CLASS_NUMBER}
            page.text("Da 15 minuti a 7 giorni. Vuoto: usa l’indicazione della lista, oppure 24 ore. Il controllo avviene mentre usi l’app.")
            page.text("Formato, codifica e gruppi si applicano alla prossima verifica, al prossimo aggiornamento o alla sostituzione del file.")
            fun fingerprint()=listOf(pages.selected(kind),pages.selected(detection),groupContent.toSortedMap(),pages.selected(format),pages.selected(encoding),pages.selected(groups),interval.text.toString()).toString()
            page.protect(::fingerprint)
            page.action("Annulla"){pages.pop()}
            page.action("Salva modifiche",true){
                try {
                    val raw=interval.text.toString().trim()
                    val minutes=if(raw.isBlank())null else raw.toIntOrNull() ?: throw PlaylistFormatException("Inserisci un intervallo in minuti valido.")
                    val next=initial.copy(reading=PlaylistReadOptions(PlaylistFormat.entries[pages.selected(format)],PlaylistEncoding.entries[pages.selected(encoding)],PlaylistGroupProfile.entries[pages.selected(groups)],
                        PlaylistVodDetection.entries[pages.selected(detection)],groupContent),
                        contentOverride=when(pages.selected(kind)){1->PlaylistContentKind.LIVE;2->PlaylistContentKind.VOD;else->null},refreshMinutes=minutes).validated()
                    pages.run(page,"Salvo le impostazioni…"){save(next);pages.saved(page)}
                }catch(error:PlaylistFormatException){page.message(error.message.orEmpty(),true)}
            }
        }
    }
    private fun groupRules(current:()->Map<String,PlaylistContentKind>,update:(Map<String,PlaylistContentKind>)->Unit,subtitle:String,
                           choices:(suspend(String,Int)->Pair<List<String>,Int>)?) {
        pages.push("group-content","Regole per i gruppi",subtitle,navigation=true){page->
            page.text("Scegli Dirette o Video per un gruppo. Le regole saranno applicate quando salvi Lettura e contenuti. Se una voce appartiene a gruppi con regole diverse, prevale Dirette.")
            val empty=page.text("Nessuna regola manuale.")
            val rows=LinearLayout(page.root.context).apply {orientation=LinearLayout.VERTICAL}
            page.body.addView(rows)
            fun edit(original:String?=null) {
                pages.push("group-content-edit",if(original==null)"Aggiungi regola" else "Modifica regola",subtitle){editor->
                    val name=editor.field("Nome del gruppo",original.orEmpty(),100)
                    if(choices!=null)editor.row("Scegli un gruppo della lista"){pages.paged("Scegli il gruppo",subtitle,key="classification-group-picker",load=choices,label={it}) {selected->
                        if(editor.alive){name.setText(selected);pages.back()}
                    }}
                    editor.text("Usa il nome del gruppo nella lista. Maiuscole e minuscole sono equivalenti.")
                    val kind=editor.choice("Contenuti del gruppo",listOf("Dirette","Video on demand"),if(original?.let {current()[it]}==PlaylistContentKind.VOD)1 else 0)
                    editor.protect {name.text.toString()+"|"+pages.selected(kind)}
                    editor.action("Annulla"){pages.pop()}
                    if(original!=null)editor.action("Rimuovi regola"){update(current()-original);pages.saved(editor,"Regola rimossa dalla bozza.")}
                    editor.action("Salva regola",true) {
                        try {
                            val value=name.text.toString().trim()
                            if(current().keys.any {it!=original && it.equals(value,true)})throw PlaylistFormatException("Esiste già una regola per questo gruppo. Aprila per modificarla.")
                            val rules=current().toMutableMap().apply {original?.let(::remove);put(value,if(pages.selected(kind)==1)PlaylistContentKind.VOD else PlaylistContentKind.LIVE)}
                            PlaylistReadOptions(groupContent=rules).validated();update(rules.toMap());pages.saved(editor,"Regola pronta. Salva Lettura e contenuti per applicarla.")
                        }catch(error:PlaylistFormatException){editor.message(error.message.orEmpty(),true)}
                    }
                }
            }
            fun render() {
                rows.removeAllViews();empty.visibility=if(current().isEmpty())View.VISIBLE else View.GONE
                current().toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach {(name,kind)->page.addRow(rows,name,if(kind==PlaylistContentKind.VOD)"Video on demand" else "Dirette"){edit(name)}}
            }
            page.onRefresh=::render;render()
            page.row("Aggiungi regola","Fino a 100 gruppi"){edit()}
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
