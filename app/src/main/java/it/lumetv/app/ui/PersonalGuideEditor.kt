package it.lumetv.app.ui

import it.lumetv.app.data.*
import it.lumetv.core.Channel
import it.lumetv.core.PlaylistFormatException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Guide settings, archive and matching share the management page stack; no dialog owns their state. */
internal class PersonalGuideEditor(private val pages:PlaylistPages,private val repository:PersonalPlaylistRepository) {
    private val guide=PersonalGuideRepository.get(pages.activity)
    private val refreshing=mutableSetOf<String>()
    fun show(source:PersonalPlaylistSource) {
        pages.push("guide-${source.id}","Guida e archivio",source.name,navigation=true){page->
            val status=page.text("")
            page.onRefresh={
                val state=guide.state.value.firstOrNull {it.sourceId==source.id}
                status.text=state?.let {"${it.matched} canali associati · ${it.programmes} programmi salvati"+
                    (if(it.refreshing)"\nAggiornamento in corso…" else if(it.updated>0)"\nUltimo aggiornamento: "+DateFormat.getDateTimeInstance().format(Date(it.updated)) else "")+
                    (if(it.error.isNotBlank())"\n\n${it.error}" else "")}
                    ?: "La guida usa gli indirizzi XMLTV della lista. Puoi aggiungerli o sostituirli nelle impostazioni."
            };page.onRefresh?.invoke()
            page.row("Impostazioni guida","Indirizzi XMLTV, lingua, orari e consenso HTTP"){load(page,source){config->settings(source,config)}}
            page.row("Archivio","Disponibilità, dall’inizio e margini"){load(page,source){settings->archive(source,settings)}}
            page.row("Associa canali","Associazione automatica, manuale o esclusione"){mappings(source)}
            page.row("Accesso alla guida","Header HTTP e altri server autorizzati"){load(page,source){settings->
                PlaylistAdvancedEditor(pages).rule(PlaylistResource.EPG,settings.rule(PlaylistResource.EPG),source.name){value->
                    val current=repository.settings(source.id);repository.setSettings(source.id,current.copy(access=current.access+(PlaylistResource.EPG to value)))
                }
            }}
            page.row("Svuota guida scaricata","Conserva indirizzi e associazioni dei canali") {
                pages.push("clear-guide","Svuota guida scaricata",source.name){confirm->
                    confirm.text("Rimuovi i programmi scaricati di questa lista. Indirizzi, associazioni e avanzamenti dei video vengono conservati.")
                    confirm.action("Annulla"){pages.pop()}
                    confirm.action("Svuota guida",true){pages.run(confirm,"Svuoto la guida…"){guide.clear(source.id);pages.saved(confirm,"Guida scaricata rimossa.")}}
                }
            }
            page.action("Aggiorna guida",true){refresh(page,source.id)}
        }
    }
    private fun refresh(page:PlaylistPages.Page,id:String) {
        if(!refreshing.add(id))return
        page.message("Aggiorno la guida…")
        pages.scope.launch {try {
            guide.refresh(true,id);page.onRefresh?.invoke()
            val result=guide.state.value.firstOrNull {it.sourceId==id}
            page.message(result?.error?.takeIf(String::isNotBlank) ?: if(result==null)"Configura un indirizzo XMLTV nelle impostazioni della guida." else "Guida aggiornata.",result?.error?.isNotBlank()==true)
        }catch(cancelled:CancellationException){throw cancelled}catch(error:Exception){page.message(PlaylistPages.safeMessage(error),true)}finally {refreshing-=id}}
    }
    private fun load(page:PlaylistPages.Page,source:PersonalPlaylistSource,show:(PlaylistSettings)->Unit) {
        pages.run(page,"Carico le impostazioni…") {val value=repository.settings(source.id);if(pages.isCurrent(page))show(value)}
    }
    private fun settings(source:PersonalPlaylistSource,initial:PlaylistSettings) {
        pages.push("guide-settings","Impostazioni guida",source.name){page->
            val config=initial.guide
            val enabled=page.toggle("Attiva la guida personale",config.enabled)
            val urls=page.field("Indirizzi XMLTV · uno per riga",config.urls.joinToString("\n"),16*8193,true)
            page.text("Vuoto: usa gli indirizzi indicati nella lista. Sono supportati XML, gzip e xz.")
            val http=page.toggle("Consenti HTTP per questa guida",config.allowHttp)
            page.text("Con HTTP, indirizzi e dati di accesso viaggiano senza cifratura. Il consenso riguarda solo questa guida.")
            val language=page.field("Lingua dei programmi",config.language)
            val history=page.field("Giorni precedenti da conservare · 1–7",config.historyDays.toString())
            val future=page.field("Ore successive da conservare · 1–48",config.futureHours.toString())
            val zone=page.field("Fuso XMLTV quando assente · es. Europe/Rome",config.xmlZone)
            val shift=page.field("Correzione EPG in ore · vuoto: usa la lista",config.shiftHours?.toString().orEmpty())
            page.protect {listOf(enabled.isChecked,http.isChecked,urls.text,language.text,history.text,future.text,zone.text,shift.text).joinToString("\u0000")}
            page.action("Annulla"){pages.pop()}
            page.action("Salva modifiche",true){
                try {
                    val next=config.copy(enabled=enabled.isChecked,allowHttp=http.isChecked,urls=urls.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList(),
                        language=language.text.toString().trim(),historyDays=history.text.toString().trim().toInt(),futureHours=future.text.toString().trim().toInt(),xmlZone=zone.text.toString().trim(),
                        shiftHours=shift.text.toString().trim().takeIf(String::isNotBlank)?.replace(',','.')?.toDouble()).validated()
                    pages.run(page,"Salvo la guida…"){val current=repository.settings(source.id);repository.setSettings(source.id,current.copy(guide=next));pages.saved(page);pages.current?.let {refresh(it,source.id)}}
                }catch(error:Exception){page.message(if(error is PlaylistFormatException)error.message.orEmpty() else "Verifica i valori numerici inseriti.",true)}
            }
        }
    }
    private fun archive(source:PersonalPlaylistSource,initial:PlaylistSettings) {
        pages.push("archive-settings","Archivio",source.name){page->
            val config=initial.guide
            page.text("Le funzioni dell’archivio richiedono il supporto del servizio della lista.")
            val zone=page.field("Fuso dell’archivio · vuoto: dispositivo",config.archiveZone)
            val days=page.field("Giorni di archivio · vuoto: usa la lista",config.archiveDays?.toString().orEmpty())
            val ongoing=page.toggle("Consenti dall’inizio per programmi ancora in onda",config.ongoing)
            val before=page.field("Margine prima del programma · secondi, 0–300",config.startBufferSeconds.toString())
            val after=page.field("Margine dopo il programma · secondi, 0–300",config.endBufferSeconds.toString())
            page.protect {listOf(zone.text,days.text,ongoing.isChecked,before.text,after.text).joinToString("\u0000")}
            page.action("Annulla"){pages.pop()}
            page.action("Salva modifiche",true){
                try {
                    val next=config.copy(archiveZone=zone.text.toString().trim(),archiveDays=days.text.toString().trim().takeIf(String::isNotBlank)?.toInt(),ongoing=ongoing.isChecked,
                        startBufferSeconds=before.text.toString().trim().toInt(),endBufferSeconds=after.text.toString().trim().toInt()).validated()
                    pages.run(page,"Salvo l’archivio…"){val current=repository.settings(source.id);repository.setSettings(source.id,current.copy(guide=next));pages.saved(page)}
                }catch(error:Exception){page.message(if(error is PlaylistFormatException)error.message.orEmpty() else "Verifica i valori numerici inseriti.",true)}
            }
        }
    }
    private fun mappings(source:PersonalPlaylistSource) {
        pages.paged("Associa canali",source.name,"mapping-channels",load={q,o->
            val rows=repository.local().filter {it.sourceId==source.id && !it.isVod && it.name.contains(q,true)}
            rows.drop(o).take(50) to rows.size
        },label={it.name}){channel->mapping(source,channel)}
    }
    private fun mapping(source:PersonalPlaylistSource,channel:Channel) {
        val key="mapping-${channel.id}"
        pages.push(key,channel.name,source.name,navigation=true){page->
            page.text("Scegli come associare questo canale alla guida programmi.")
            fun apply(feed:Int?,xml:String="") {pages.run(page,"Salvo l’associazione…"){guide.map(source.id,channel.id,feed,xml);pages.saved(page,"Associazione salvata.");pages.current?.let {refresh(it,source.id)}}}
            page.row("Associazione automatica","Prima l’ID esatto, poi un nome univoco"){apply(null)}
            page.row("Non associare","Non mostrare la guida di questo canale"){apply(-1)}
            page.row("Scegli canale XMLTV","Seleziona la guida e il suo canale") {
                pages.scope.launch {try {
                    val feeds=repository.guideInputs(source.id).firstOrNull()?.candidates?.flatMap {it.feeds}?.distinct()?.take(16).orEmpty()
                    if(!pages.isCurrent(page))return@launch
                    if(feeds.isEmpty()){page.message("Configura un indirizzo XMLTV e aggiorna la guida prima di associare i canali.",true);return@launch}
                    pages.push("mapping-feeds","Scegli la guida",channel.name,navigation=true){feedPage->
                        feeds.indices.forEach {feed->feedPage.row("Guida ${feed+1}") {
                            pages.paged("Canale XMLTV",channel.name,"mapping-xml",load={q,o->val list=guide.choices(source.id,feed,q,o);list to (o+list.size+if(list.size==50)1 else 0)},label={it.name+" · "+it.id}) {xml->
                                val picker=pages.current ?: return@paged
                                pages.run(picker,"Salvo l’associazione…"){guide.map(source.id,channel.id,feed,xml.id);pages.backTo("mapping-channels");pages.current?.let {refresh(it,source.id)}}
                            }
                        }}
                    }
                }catch(cancelled:CancellationException){throw cancelled}catch(error:Exception){page.message(PlaylistPages.safeMessage(error),true)}}
            }
        }
    }
}
