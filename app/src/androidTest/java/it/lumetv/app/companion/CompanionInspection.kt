package it.lumetv.app.companion

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import it.lumetv.app.MobileActivity
import it.lumetv.app.TvActivity
import it.lumetv.app.data.*
import it.lumetv.app.playback.TvPlayer
import it.lumetv.app.ui.CompanionPhoneScreen
import it.lumetv.app.ui.CompanionTvScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/** Test-APK-only orchestration. All network and app targets are disposable emulator fixtures. */
object CompanionInspection {
    fun run(runner:Instrumentation,args:Bundle):JSONObject {
        check(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val context=runner.targetContext;val phone=args.getString("mode")=="phone"
        val root=requireNotNull(args.getString("fixtureRoot"));require(Uri.parse(root).host=="10.0.2.2")
        val checks=mutableListOf<String>();val folder=File(context.cacheDir,"companion-inspection").apply {mkdirs()}
        fun record(label:String){checks+=label;File(folder,"progress.json").writeText(JSONArray(checks).toString())}
        fun <T> main(block:()->T):T {val result=AtomicReference<Result<T>>();runner.runOnMainSync {result.set(runCatching(block))};return result.get().getOrThrow()}
        fun capture(name:String){runner.waitForIdleSync();Thread.sleep(if(name.contains("landscape"))1100 else 200);runner.uiAutomation.takeScreenshot()?.let {bitmap->File(folder,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}}
        fun await(label:String,condition:()->Boolean){val end=SystemClock.elapsedRealtime()+30_000;while(SystemClock.elapsedRealtime()<end){if(main(condition)){record(label);return};Thread.sleep(100)};capture("failure");error(label)}
        fun verify(label:String,condition:()->Boolean){if(!main(condition)){capture("failure");error(label)};record(label)}
        fun network(path:String,body:JSONObject?=null):JSONObject {
            val connection=URL(root+path).openConnection() as HttpURLConnection
            connection.connectTimeout=5000;connection.readTimeout=30_000
            try {if(body!=null){connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use {it.write(body.toString().toByteArray())}}
                return JSONObject(connection.inputStream.bufferedReader().use {it.readText()})
            }finally {connection.disconnect()}
        }
        fun control(action:String)=network("/control",JSONObject().put("action",action))
        val prefs=context.getSharedPreferences("lume",0)
        context.getSharedPreferences("lume-companion",0).edit().clear().commit()
        prefs.edit().putBoolean("data_saver",false).putBoolean("zap_favorites_only",false).commit()
        val repo=PersonalPlaylistRepository.get(context)
        val rows=if(phone)emptyList() else runBlocking(Dispatchers.IO) {
            repo.local();repo.state.value.toList().forEach {repo.delete(it.id)}
            val input=("#EXTM3U\n#EXTINF:-1 type=\"live\",Prima diretta\n$root/video.mp4?private-provider-token=one\n"+
                "#EXTINF:-1 type=\"live\",Seconda diretta\n$root/video.mp4?private-provider-token=two\n"+
                "#EXTINF:-1 type=\"movie\",Film di prova\n$root/video.mp4?private-provider-token=movie\n"+
                "#EXTINF:-1 type=\"episode\",Orbita S01E01\n$root/video.mp4?private-provider-token=episode1\n"+
                "#EXTINF:-1 type=\"episode\",Orbita S01E02\n$root/video.mp4?private-provider-token=episode2\n").toByteArray()
            val source=repo.prepareFile(input,allowHttp=true,settings=PlaylistSettings(guide=PersonalGuideSettings(enabled=false))).use {repo.save("Rete locale test",it)}
            repo.catalog(PersonalCatalogQuery(source=source,content=null)).rows
        }
        val activity=runner.startActivitySync(Intent(context,if(phone)MobileActivity::class.java else TvActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse("lumetv://catalog")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        fun field(name:String)=activity.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(activity)
        fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap {all(view.getChildAt(it))} else emptyList()
        fun content()=activity.findViewById<View>(android.R.id.content)
        fun text(label:String)=all(content()).filter {it.isShown}.filterIsInstance<TextView>().any {it.text.toString().contains(label)}
        fun clickTag(tag:String){await("action: $tag"){content().findViewWithTag<View>(tag)?.takeIf {it.isShown && it.isEnabled}?.let {
            it.requestRectangleOnScreen(Rect(0,0,it.width,it.height),true);if(!phone)it.requestFocusFromTouch();it.performClick();true
        }==true}}
        fun click(label:String){await("action: $label"){
            all(content()).lastOrNull {it.isShown && it.isClickable && it.isEnabled &&
                (it is TextView && it.text.toString()==label || it.contentDescription?.toString()==label || it.contentDescription?.toString()?.startsWith(label+":")==true)}?.let {
                it.requestRectangleOnScreen(Rect(0,0,it.width,it.height),true);if(!phone)it.requestFocusFromTouch();it.performClick();true
            }==true
        }}
        fun enter(tag:String,value:String){main {(content().findViewWithTag<EditText>(tag) ?: error(tag)).setText(value)}}
        fun <T> io(block:suspend ()->T)=runBlocking(Dispatchers.IO){block()}
        try {
            await("real app and player are ready"){(field(if(phone)"player" else "tvPlayer") as TvPlayer).isConnected && field("catalogLoaded")==true}
            if(!phone) {
                val screen=field("companion") as CompanionTvScreen
                val controller=screen.javaClass.getDeclaredField("controller").apply {isAccessible=true}.get(screen) as CompanionTvController
                val player=field("tvPlayer") as TvPlayer
                main {activity.javaClass.getDeclaredMethod("selectChannel",it.lumetv.core.Channel::class.java).apply {isAccessible=true}.invoke(activity,rows.first {it.name=="Prima diretta"})}
                runner.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                capture("tv-entry")
                verify("TV catalog has no Collega telefono shortcut") {
                    content().findViewWithTag<View>("companion_tv_entry")==null &&
                        all(content()).filterIsInstance<TextView>().none {it.isShown && it.text.toString()=="Collega telefono"}
                }
                click("Opzioni")
                main {(field("options") as it.lumetv.app.ui.TvOptionsView).apply {
                    select(it.lumetv.app.ui.TvOption.COMPANION);focusSelection()
                }}
                await("Collega telefono stays available and focused in TV options") {
                    field("showingOptions")==true && activity.currentFocus?.contentDescription?.toString()?.startsWith("Collega telefono")==true
                }
                capture("tv-options-entry")
                click("Collega telefono")
                await("TV listener binds to its local network"){controller.endpoint!=null}
                await("opening Collega telefono from TV options automatically displays a QR"){controller.invite()!=null}
                await("TV QR fills the right column at more than twice its previous width") {
                    val image=screen.findViewWithTag<View>("companion_qr") ?: return@await false
                    val column=screen.findViewWithTag<View>("companion_pair_text") ?: return@await false
                    val imagePosition=IntArray(2);val columnPosition=IntArray(2)
                    image.getLocationOnScreen(imagePosition);column.getLocationOnScreen(columnPosition)
                    val visible=Rect();image.getGlobalVisibleRect(visible)
                    image.width>=context.resources.displayMetrics.density*400 && image.width==image.height &&
                        visible.width()==image.width && visible.height()==image.height &&
                        imagePosition[0]>=screen.width/2 && columnPosition[0]+column.width<imagePosition[0]
                }
                capture("tv-pairing")
                verify("the rendered TV QR is readable") {
                    val image=all(screen).filterIsInstance<android.widget.ImageView>().single()
                    val point=IntArray(2);image.getLocationOnScreen(point)
                    val screenshot=runner.uiAutomation.takeScreenshot()!!
                    val crop=Bitmap.createBitmap(screenshot,point[0],point[1],image.width,image.height)
                    val pixels=IntArray(crop.width*crop.height);crop.getPixels(pixels,0,crop.width,0,0,crop.width,crop.height)
                    val source=com.google.zxing.RGBLuminanceSource(crop.width,crop.height,pixels)
                    val result=com.google.zxing.MultiFormatReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)))
                    crop.recycle();screenshot.recycle();result.text==controller.invite()!!.encode()
                }
                verify("QR setup keeps manual network fields behind alternative methods") {
                    content().findViewWithTag<View>("companion_pair_code")==null && content().findViewWithTag<View>("companion_address")==null
                }
                val firstCode=main {controller.invite()!!.code}
                main {
                    val pairing=controller.javaClass.getDeclaredField("pairing").apply {isAccessible=true}.get(controller) as CompanionPairing
                    pairing.javaClass.getDeclaredField("until").apply {isAccessible=true}.setLong(pairing,SystemClock.elapsedRealtime()-1)
                }
                await("expired QR is renewed automatically without another user action"){controller.invite()?.code?.let {it!=firstCode}==true}
                capture("tv-pairing-renewed")
                repeat(12){runner.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)}
                verify("D-pad focus cannot escape behind the connection screen"){activity.currentFocus in all(screen)}
                verify("QR stays fully visible while scrolling the left-side actions") {
                    val image=screen.findViewWithTag<View>("companion_qr");val visible=Rect()
                    image.getGlobalVisibleRect(visible) && visible.width()==image.width && visible.height()==image.height &&
                        activity.currentFocus in all(screen.findViewWithTag("companion_pair_text"))
                }
                capture("tv-pairing-actions")
                fun invite()=main {controller.invite()!!.let {JSONObject().put("host",it.endpoint.host).put("port",it.endpoint.port).put("id",it.endpoint.id).put("pin",it.pin).put("code",it.code)}}
                network("/ready",invite())
                var focusChanges=0
                val focusObserver=android.view.ViewTreeObserver.OnGlobalFocusChangeListener {_,_->focusChanges++}
                var finished=false
                while(!finished) {
                    val command=network("/actions")
                    if(!command.has("action")){Thread.sleep(100);continue}
                    val action=command.getString("action")
                    val answer=runCatching {
                        val details=JSONObject()
                        when(action) {
                            "nav-state"->main {
                                details.put("catalog",field("showingCatalog")).put("options",field("showingOptions"))
                                    .put("pairing",screen.isShowing)
                                    .put("focus",System.identityHashCode(activity.currentFocus)).put("focusChanges",focusChanges)
                                    .put("dialogs",(field("catalogDialogs") as Set<*>).filterIsInstance<android.app.AlertDialog>().count {it.isShowing})
                                    .put("favoriteZapping",prefs.getBoolean("zap_favorites_only",false))
                            }
                            "nav-focus-catalog"->main {
                                val grid=field("grid") as androidx.recyclerview.widget.RecyclerView
                                grid.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus()
                                focusChanges=0;content().viewTreeObserver.removeOnGlobalFocusChangeListener(focusObserver)
                                content().viewTreeObserver.addOnGlobalFocusChangeListener(focusObserver)
                            }
                            "nav-focus-zapping"->main {
                                all(field("options") as View).first {it.isShown && it.isClickable && it.contentDescription?.toString()?.startsWith("CH+/CH−")==true}.requestFocus()
                            }
                            "nav-capture"->capture("tv-phone-navigation")
                            "nav-capture-options"->capture("tv-phone-options")
                            "paired"->{
                                verify("QR scan authorizes exactly one phone without a TV approval request"){controller.store.peers().size==1 && controller.pending()==null}
                                verify("QR scan never presents a Consenti button"){content().findViewWithTag<View>("companion_approve")==null}
                                await("TV returns automatically after the phone has received access"){!screen.isShowing}
                                verify("automatic return preserves TV options and playback"){field("showingOptions")==true && player.snapshot.channel?.id==rows.first {it.name=="Prima diretta"}.id}
                                capture("tv-paired")
                            }
                            "deny"->{await("manual pairing still requires TV approval"){controller.pending()!=null};capture("tv-manual-approval");clickTag("companion_deny");verify("denial grants no authorization"){controller.store.peers().isEmpty()};await("denial prepares a new QR without restarting the procedure"){controller.invite()!=null}}
                            "new-pair"->{main {screen.hide();screen.show()}}
                            "manual-pair"->{clickTag("companion_tv_methods");capture("tv-manual-pairing")}
                            "live"->{main {player.select(rows.first {it.name=="Prima diretta"},false)};await("TV starts live playback"){player.snapshot.playing}}
                            "video"->{main {player.select(rows.first {it.name=="Film di prova"},false)};await("TV opens a seekable video"){player.snapshot.seekable && player.snapshot.duration>0}}
                            "episode"->{main {player.select(rows.first {it.name=="Orbita S01E01"},false)};await("TV resolves the next episode"){player.snapshot.nextEpisode!=null}}
                            "pause"->{main {if(player.snapshot.wantsToPlay)player.togglePause()};verify("local TV controls pause the shared player"){!player.snapshot.wantsToPlay}}
                            "background"->{main {context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))};await("leaving TV closes the LAN listener"){controller.endpoint==null}}
                            "foreground"->{main {context.startActivity(Intent(context,TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))};await("opening TV restores its listener"){controller.endpoint!=null}}
                            "disable"->{main {controller.enabled(false)};verify("manual disable closes the TV listener"){controller.endpoint==null}}
                            "enable"->{main {controller.enabled(true)};await("manual enable restores the TV listener"){controller.endpoint!=null}}
                            "revoke"->{main {screen.show()};clickTag("companion_manage");await("authorized phone is listed"){controller.store.peers().isNotEmpty()};main {
                                val row=all(screen).firstOrNull {it.isClickable && it.contentDescription?.toString()?.startsWith("Rimuovi Telefono")==true} ?: error("missing revoke row")
                                row.performClick()
                            };verify("TV revocation removes authorization"){controller.store.peers().isEmpty()};capture("tv-revoked")}
                            "no-pending"->verify("wrong code cannot create an approval request"){controller.pending()==null && controller.store.peers().isEmpty()}
                            "exit"->finished=true
                            else->error("Unknown test action")
                        }
                        details.put("ok",true).apply {if(action in setOf("new-pair","manual-pair"))put("invite",invite())}
                    }.getOrElse {JSONObject().put("ok",false).put("error",it.toString())}
                    network("/done",answer.put("request",command.getInt("request")))
                }
                main {content().viewTreeObserver.removeOnGlobalFocusChangeListener(focusObserver);controller.enabled(false);screen.hide()}
            } else {
                val screen=field("companion") as CompanionPhoneScreen
                val invite=network("/invite")
                val endpoint=CompanionEndpoint("10.0.2.2",invite.getInt("port"),invite.getString("id"))
                val wrong=io {runCatching {CompanionClient(endpoint).use {it.pair("A".repeat(16),CompanionStore(context).id,"Test") {}}}.exceptionOrNull()}
                verify("wrong code rejects the provisional TLS identity (${wrong?.javaClass?.simpleName}: ${wrong?.message})"){wrong is CompanionFailure && wrong.code=="pairing_failed"}
                check(control("no-pending").getBoolean("ok"))
                val anonymous=io {runCatching {CompanionClient(endpoint,invite.getString("pin"),"00".repeat(32)).use {it.getState()}}.exceptionOrNull()}
                verify("unpaired phone cannot read playback state"){anonymous is CompanionFailure && anonymous.status==401}
                val unauthenticatedNavigation=io {runCatching {CompanionClient(endpoint,invite.getString("pin"),"00".repeat(32)).use {
                    it.command("untrusted:0","navigate",JSONObject().put("key","home"))
                }}.exceptionOrNull()}
                verify("unpaired phone cannot navigate the TV"){unauthenticatedNavigation is CompanionFailure && unauthenticatedNavigation.status==401}
                capture("phone-entry")
                val cancelledScan=runner.addMonitor(it.lumetv.app.ui.CompanionCaptureActivity::class.java.name,
                    Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null),true)
                try {
                    click("Telecomando")
                    await("first remote entry opens the camera without a settings detour"){cancelledScan.hits==1 && screen.isShowing}
                    Thread.sleep(200)
                    verify("cancelling the scanner leaves alternative methods available"){content().findViewWithTag<View>("companion_methods")?.isShown==true}
                }finally {runner.removeMonitor(cancelledScan)}
                capture("phone-discovery")
                verify("first phone setup keeps IP port and code out of its primary path") {
                    content().findViewWithTag<View>("companion_host")==null && content().findViewWithTag<View>("companion_port")==null && content().findViewWithTag<View>("companion_code")==null
                }
                verify("TV menu instruction stays readable without ellipsis at this font scale") {
                    val instruction=all(screen).filterIsInstance<TextView>().single {it.text.toString()=="Sulla TV: Opzioni → Collega telefono. Inquadra il QR."}
                    instruction.layout?.let {layout->(0 until layout.lineCount).all {layout.getEllipsisCount(it)==0}}==true
                }
                clickTag("companion_methods");capture("phone-other-methods");clickTag("companion_manual")
                val manual=control("manual-pair").getJSONObject("invite")
                verify("manual method rotates the QR secret"){manual.getString("code")!=invite.getString("code")}
                val staleQr=io {runCatching {CompanionClient(endpoint,invite.getString("pin")).use {it.pair(invite.getString("code"),CompanionStore(context).id,"Test") {}}}.exceptionOrNull()}
                verify("QR secret cannot be used after switching TV to manual mode"){staleQr is CompanionFailure && staleQr.code=="pairing_failed"}
                enter("companion_host","10.0.2.2");enter("companion_port",endpoint.port.toString());enter("companion_code",manual.getString("code"))
                clickTag("companion_pair");await("phone asks for confirmation on the TV for manual pairing"){text("Conferma Consenti")}
                capture("phone-manual-approval")
                check(control("deny").getBoolean("ok"));await("TV denial is shown inline"){text("Collegamento rifiutato")};capture("phone-denied")
                main {screen.handleBack();screen.handleBack()}
                val next=control("new-pair").getJSONObject("invite")
                val qr=CompanionInvite(endpoint,next.getString("pin"),next.getString("code"))
                val scanResult=Intent().putExtra("SCAN_RESULT",qr.encode()).putExtra("SCAN_RESULT_FORMAT","QR_CODE")
                val monitor=runner.addMonitor(it.lumetv.app.ui.CompanionCaptureActivity::class.java.name,
                    Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,scanResult),true)
                try {
                    clickTag("companion_scan")
                    await("QR scan opens the real remote without TV approval or a phone connect button") {
                        content().findViewWithTag<View>("companion_remote")?.isShown==true && text("Collegato") &&
                            content().findViewWithTag<View>("companion_pair")==null
                    }
                    verify("scan result passed through the Android activity contract"){monitor.hits>0}
                    verify("QR connection does not ask the user for TV confirmation"){!text("Conferma Consenti") && !text("Conferma sulla TV")}
                }finally {runner.removeMonitor(monitor)}
                check(control("paired").getBoolean("ok"))
                capture("phone-remote-live")
                val saved=io {CompanionStore(context).saved().single()}
                verify("saved TV is pinned and credential is not plaintext"){saved.pin==invite.getString("pin") && !File(context.applicationInfo.dataDir,"shared_prefs/lume-companion.xml").readText().contains(saved.token)}
                clickTag("companion_change_tv")
                await("TV picker remains open when the user chooses to change TV"){text("Le tue TV") && content().findViewWithTag<View>("companion_saved_tv")?.isShown==true}
                capture("phone-tv-picker")
                clickTag("companion_saved_tv");await("selecting a saved TV directly reopens its remote"){text("Collegato") && content().findViewWithTag<View>("companion_remote")?.isShown==true}
                main {screen.handleBack()};await("phone Back closes the remote in one step"){!screen.isShowing}
                click("Telecomando");await("reopening the remote reconnects the last TV without QR or confirmation") {
                    content().findViewWithTag<View>("companion_remote")?.isShown==true && text("Collegato")
                }
                verify("last TV selection is retained"){CompanionStore(context).lastTelevision==saved.endpoint.id}
                capture("phone-remote-reconnect")
                val tv=io {CompanionClient(saved.endpoint,saved.pin,saved.token)}
                try {
                    val state=io {tv.getState()}
                    Thread.sleep(4500)
                    verify("an idle pooled connection recovers without an unsolicited bad request"){io {tv.getState()}.optInt("version")==1}
                    verify("remote has an accessible primary OK button with a large touch target") {
                        val toggle=content().findViewWithTag<View>("companion_nav_ok")
                        val node=toggle.createAccessibilityNodeInfo()
                        val valid=toggle.isClickable && toggle.width>=context.resources.displayMetrics.density*48 &&
                            toggle.height>=context.resources.displayMetrics.density*48 && node.className==android.widget.Button::class.java.name
                        node.recycle();valid
                    }
                    val phonePlayback=(field("player") as TvPlayer).snapshot.playbackRevision
                    fun navState()=io {control("nav-state")}
                    clickTag("companion_nav_home")
                    await("phone Home opens the Lume catalog on TV"){navState().let {it.getBoolean("catalog") && !it.getBoolean("options")}}
                    check(control("nav-focus-catalog").getBoolean("ok"))
                    val initialFocus=navState().getInt("focus")
                    clickTag("companion_nav_right");await("phone Right moves TV catalog focus"){navState().getInt("focus")!=initialFocus}
                    clickTag("companion_nav_left");await("phone Left restores TV catalog focus"){navState().getInt("focus")==initialFocus}
                    clickTag("companion_nav_up");await("phone Up moves TV focus to the catalog controls"){navState().getInt("focus")!=initialFocus}
                    clickTag("companion_nav_down");await("phone Down returns focus to the TV catalog"){navState().getInt("focus")==initialFocus}
                    check(control("nav-focus-catalog").getBoolean("ok"))
                    main {repeat(3) {
                        content().findViewWithTag<View>("companion_nav_right").performClick()
                        content().findViewWithTag<View>("companion_nav_left").performClick()
                    }}
                    await("quick direction taps are delivered in order without losing the matching return") {
                        !(screen.javaClass.getDeclaredField("commanding").apply {isAccessible=true}.getBoolean(screen)) &&
                            navState().let {it.getInt("focusChanges")>=6 && it.getInt("focus")==initialFocus}
                    }
                    val nav=JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("session",state.getString("session"))
                        .put("action","navigate").put("args",JSONObject().put("key","right"))
                    val firstNav=io {tv.exchange("/v1/command",nav,true)}
                    val afterFirstNav=navState().getInt("focus")
                    val replayNav=io {tv.exchange("/v1/command",nav,true)}
                    verify("retrying a navigation command does not move focus twice") {firstNav.toString()==replayNav.toString() && navState().getInt("focus")==afterFirstNav}
                    check(control("nav-capture").getBoolean("ok"))
                    clickTag("companion_nav_ok");await("phone OK opens the focused TV channel"){!navState().getBoolean("catalog")}
                    clickTag("companion_nav_back");await("remote Back returns to the TV catalog without leaving the phone remote") {
                        navState().getBoolean("catalog") && content().findViewWithTag<View>("companion_remote")?.isShown==true
                    }
                    clickTag("companion_nav_options");await("phone Options opens the TV options page"){navState().getBoolean("options")}
                    check(control("nav-focus-zapping").getBoolean("ok"))
                    clickTag("companion_nav_ok");await("phone OK can open a native TV option chooser"){navState().getInt("dialogs")==1}
                    check(control("nav-capture-options").getBoolean("ok"))
                    clickTag("companion_nav_down");clickTag("companion_nav_ok")
                    await("phone directions and OK apply a choice in the native TV settings") {
                        navState().let {it.getInt("dialogs")==0 && it.getBoolean("favoriteZapping")}
                    }
                    clickTag("companion_nav_ok");await("remote can reopen the focused TV setting"){navState().getInt("dialogs")==1}
                    clickTag("companion_nav_up");clickTag("companion_nav_ok")
                    await("TV setting can be restored through the same remote navigation") {
                        navState().let {it.getInt("dialogs")==0 && !it.getBoolean("favoriteZapping")}
                    }
                    clickTag("companion_nav_ok");await("TV option chooser remains available after changing its value"){navState().getInt("dialogs")==1}
                    clickTag("companion_nav_back");await("remote Back closes the native TV dialog"){navState().getInt("dialogs")==0}
                    clickTag("companion_nav_home");await("Home leaves TV options for its catalog"){navState().let {it.getBoolean("catalog") && !it.getBoolean("options")}}
                    io {tv.command(io {tv.getState()}.getString("session"),"stop")}
                    await("navigation stays available without an active TV playback") {
                        !io {tv.getState()}.getBoolean("active") && text("Nessuna riproduzione") &&
                            content().findViewWithTag<View>("companion_nav_ok")?.isEnabled==true
                    }
                    main {content().findViewWithTag<View>("companion_control_toggle").let {
                        it.requestRectangleOnScreen(Rect(0,0,it.width,it.height),true)
                    }}
                    capture("phone-remote-idle")
                    clickTag("companion_nav_home");check(control("nav-focus-catalog").getBoolean("ok"))
                    clickTag("companion_nav_ok");await("remote OK starts TV playback from an idle catalog"){io {tv.getState()}.getBoolean("active")}
                    verify("TV navigation never starts playback on the phone"){(field("player") as TvPlayer).snapshot.playbackRevision==phonePlayback}
                    val forbidden=io {runCatching {tv.command(state.getString("session"),"navigate",JSONObject().put("key","systemHome"))}.exceptionOrNull()}
                    verify("remote navigation cannot inject arbitrary system keys"){forbidden is CompanionFailure && forbidden.status==400}
                    check(control("new-pair").getBoolean("ok"))
                    val approval=io {runCatching {tv.command(state.getString("session"),"navigate",JSONObject().put("key","ok"))}.exceptionOrNull()}
                    verify("phone cannot authorize or revoke phones through remote navigation"){approval is CompanionFailure && approval.code=="physical_approval_required"}
                    clickTag("companion_nav_home");check(control("live").getBoolean("ok"))
                    await("remote returns to live controls after navigation"){content().findViewWithTag<View>("companion_control_next")?.isEnabled==true}
                    main {val scroll=all(content()).filterIsInstance<android.widget.ScrollView>().first {it.isShown};scroll.scrollTo(0,0)}
                    capture("phone-remote-navigation")
                    verify("secondary playback options start collapsed") {content().findViewWithTag<View>("companion_options")?.isShown==false}
                    clickTag("companion_options_toggle")
                    await("playback options expand inside the remote"){content().findViewWithTag<View>("companion_control_audio")?.isShown==true}
                    capture("phone-remote-options")
                    clickTag("companion_control_audio")
                    fun trackDialog()=screen.javaClass.getDeclaredField("dialog").apply {isAccessible=true}.get(screen) as? android.app.AlertDialog
                    await("audio choices remain accessible from compact options"){trackDialog()?.isShowing==true}
                    capture("phone-audio-options")
                    main {val menu=trackDialog()!!.listView;menu.performItemClick(menu.getChildAt(0),0,menu.adapter.getItemId(0))}
                    await("selecting an audio option closes its native chooser"){trackDialog()?.isShowing==false}
                    clickTag("companion_control_sleep")
                    await("timer presets remain accessible from compact options"){trackDialog()?.isShowing==true}
                    main {val menu=trackDialog()!!.listView;menu.performItemClick(menu.getChildAt(1),1,menu.adapter.getItemId(1))}
                    await("timer preset is applied on the TV"){io {tv.getState()}.getJSONObject("options").optLong("sleepRemaining")>0}
                    clickTag("companion_options_toggle")
                    await("options collapse without leaving the remote"){content().findViewWithTag<View>("companion_options")?.isShown==false}
                    verify("remote metadata contains no stream URLs or credentials"){!state.toString().contains("http") && !state.toString().contains("private-provider-token")}
                    val untrusted=io {runCatching {CompanionClient(saved.endpoint,"00".repeat(32),saved.token).use {it.getState()}}.exceptionOrNull()}
                    verify("changed certificate blocks saved credentials"){untrusted!=null}
                    clickTag("companion_control_toggle");await("phone pause changes TV state"){io {tv.getState()}.optBoolean("wantsToPlay")==false}
                    await("the same primary button now offers resume"){content().findViewWithTag<View>("companion_control_toggle")?.contentDescription=="Riprendi sulla TV"}
                    capture("phone-remote-paused")
                    clickTag("companion_control_toggle");await("phone resume changes TV state"){io {tv.getState()}.optBoolean("wantsToPlay")}
                    check(control("pause").getBoolean("ok"));await("TV-side pause is reflected on the phone"){text("In pausa sulla TV")}
                    clickTag("companion_control_toggle")
                    clickTag("companion_control_next");await("phone zaps to the next TV channel"){io {tv.getState()}.optString("channel")=="Seconda diretta"}
                    clickTag("companion_control_previous");await("phone zaps back"){io {tv.getState()}.optString("channel")=="Prima diretta"}
                    check(control("video").getBoolean("ok"));await("phone reflects the video selected on TV"){text("Film di prova") && content().findViewWithTag<View>("companion_control_forward")?.isEnabled==true}
                    val old=state.getString("session")
                    val stale=io {runCatching {tv.command(old,"stop")}.exceptionOrNull()}
                    verify("stale commands cannot stop a different title"){stale is CompanionFailure && stale.code=="playback_changed"}
                    main {
                        val bar=content().findViewWithTag<View>("companion_seek")
                        bar.requestRectangleOnScreen(Rect(0,0,bar.width,bar.height),true)
                        check(bar.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                            Bundle().apply {putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,250f)}))
                    }
                    await("accessible slider adjustment seeks the TV video"){io {tv.getState()}.optLong("position") in 40_000..60_000}
                    val beforeSeek=io {tv.getState()}.optLong("position")
                    clickTag("companion_control_forward");await("phone advances the TV video by thirty seconds"){io {tv.getState()}.optLong("position")>=beforeSeek+25_000}
                    val beforeBack=io {tv.getState()}.optLong("position")
                    clickTag("companion_control_back");await("phone rewinds the TV video by ten seconds"){io {tv.getState()}.optLong("position")<=beforeBack-7_000}
                    val current=io {tv.getState()};val cmd=JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("session",current.getString("session")).put("action","seekBy").put("args",JSONObject().put("delta",30_000))
                    val first=io {tv.exchange("/v1/command",cmd,true)};val replay=io {tv.exchange("/v1/command",cmd,true)}
                    verify("retried command returns its original result without repeating the seek"){first.toString()==replay.toString()}
                    for((type,field) in listOf(1 to "audio",2 to "video")) {
                        val trackState=io {tv.getState()};val options=trackState.getJSONObject("options");val tracks=options.getJSONArray(field)
                        if(tracks.length()>0) {
                            val selected=io {tv.command(trackState.getString("session"),"track",JSONObject().put("type",type).put("revision",options.getLong("revision")).put("key",tracks.getJSONObject(0).getString("key")))}
                            verify("remote $field track selection reaches the TV player"){selected.getJSONObject("options").getJSONArray(field).getJSONObject(0).getBoolean("selected")}
                        }
                    }
                    val tracksNow=io {tv.getState()}
                    val oldTrack=io {runCatching {tv.command(tracksNow.getString("session"),"track",JSONObject().put("type",1).put("revision",-1).put("key","0:0"))}.exceptionOrNull()}
                    verify("stale track selection is rejected"){oldTrack is CompanionFailure && oldTrack.code=="tracks_changed"}
                    io {tv.command(io {tv.getState()}.getString("session"),"sleep",JSONObject().put("minutes",15))}
                    await("phone shows the TV session timer"){text("Timer:")}
                    await("video capture has settled on playing controls"){io {tv.getState()}.optBoolean("playing") && !text("Caricamento sulla TV…")}
                    capture("phone-remote-video")
                    val touch=main {
                        val bar=content().findViewWithTag<View>("companion_seek");bar.requestRectangleOnScreen(Rect(0,0,bar.width,bar.height),true)
                        val xy=IntArray(2);bar.getLocationOnScreen(xy)
                        floatArrayOf(xy[0]+bar.width*.2f,xy[0]+bar.width*.55f,xy[1]+bar.height/2f)
                    }
                    val down=SystemClock.uptimeMillis()
                    fun pointer(action:Int,x:Float){runner.sendPointerSync(android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,touch[2],0).apply {source=android.view.InputDevice.SOURCE_TOUCHSCREEN})}
                    pointer(android.view.MotionEvent.ACTION_DOWN,touch[0]);pointer(android.view.MotionEvent.ACTION_MOVE,touch[1])
                    try {
                        check(control("episode").getBoolean("ok"));await("phone offers the actual next episode"){text("Orbita S01E01") && content().findViewWithTag<View>("companion_control_nextEpisode")?.isEnabled==true}
                    }finally {pointer(android.view.MotionEvent.ACTION_UP,touch[1])}
                    Thread.sleep(500)
                    verify("ending an old seek gesture cannot move the newly selected episode"){io {tv.getState()}.optLong("position")<10_000}
                    clickTag("companion_control_nextEpisode");await("phone opens the next episode on TV"){io {tv.getState()}.optString("channel")=="Orbita S01E02"}
                    main {activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
                    await("remote survives phone rotation"){activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE && text("Orbita S01E02")};capture("phone-landscape")
                    main {activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
                    check(control("background").getBoolean("ok"));await("phone disables commands while TV is unavailable"){content().findViewWithTag<View>("companion_control_toggle")?.isEnabled==false}
                    check(control("foreground").getBoolean("ok"));await("phone reconnects to the saved TV without pairing again"){text("Collegato")}
                    check(control("live").getBoolean("ok"));await("reconnected phone controls playback again"){content().findViewWithTag<View>("companion_control_toggle")?.isEnabled==true}
                    check(control("disable").getBoolean("ok"));await("disabling LAN makes controls unavailable"){content().findViewWithTag<View>("companion_control_toggle")?.isEnabled==false && !text("Autorizzazione revocata")}
                    check(control("enable").getBoolean("ok"));await("reenabling LAN preserves trust and reconnects"){text("Collegato") && content().findViewWithTag<View>("companion_control_toggle")?.isEnabled==true}
                    check(control("revoke").getBoolean("ok"));await("revocation disables the phone immediately"){text("Autorizzazione revocata") && content().findViewWithTag<View>("companion_control_toggle")?.isEnabled==false && content().findViewWithTag<View>("companion_repair")?.isShown==true}
                    val revoked=io {runCatching {CompanionClient(saved.endpoint,saved.pin,saved.token).use {it.getState()}}.exceptionOrNull()}
                    verify("revoked token is rejected by a fresh TV connection"){revoked is CompanionFailure && revoked.status==401};capture("phone-revoked")
                }finally {tv.close()}
                clickTag("companion_change_tv");await("revoked TV remains listed without losing the saved target") {text("Le tue TV") && content().findViewWithTag<View>("companion_saved_tv")?.isShown==true}
                main {screen.handleBack()};verify("closing companion returns to the original phone app"){!screen.isShowing}
                check(control("exit").getBoolean("ok"))
            }
            return JSONObject().put("passed",true).put("mode",if(phone)"phone" else "tv").put("checks",JSONArray(checks))
        }catch(error:Throwable){capture("failure");throw error}
        finally {main {activity.finish()}}
    }
}
