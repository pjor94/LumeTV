package it.lumetv.app.companion

import org.json.JSONObject
import java.util.UUID

/** 80-bit out-of-band secret + certificate-bound HMAC proofs authorize a QR scan directly.
 * Manual invitations require TV approval; this policy belongs to the server-issued secret.
 * Unlike a short PIN, the random secret resists offline guessing. Neither peer transmits it.
 * Every proof is bound to this TLS identity and fresh nonces, including the client's display name. */
internal class CompanionPairing(private val id:String,private val name:String,private val pin:String,
    private val remember:(CompanionPeer)->Unit,private val changed:()->Unit={},private val now:()->Long=android.os.SystemClock::elapsedRealtime) {
    private data class Challenge(val client:String,val server:String,val until:Long)
    data class Pending(val ticket:String,val clientId:String,val name:String,val until:Long,var result:String="waiting",var token:String?=null)
    private val challenges=linkedMapOf<String,Challenge>()
    private var secret:String?=null
    private var confirmationRequired=false
    private var until=0L
    private var rateStart=0L
    private var attempts=0
    private var request:Pending?=null
    private val denied=linkedMapOf<String,Pending>()
    @Synchronized fun begin(confirmationRequired:Boolean=false):String {
        expire()
        if(request?.result=="approved")throw CompanionFailure(409,"handover_pending")
        if(request?.result=="waiting")throw CompanionFailure(409,"pairing_busy")
        request?.takeIf {it.result=="denied"}?.let {denied[it.ticket]=it}
        while(denied.size>8)denied.remove(denied.keys.first())
        request=null;challenges.clear();this.confirmationRequired=confirmationRequired
        secret=CompanionProtocol.code();until=now()+120_000;changed();return secret!!
    }
    @Synchronized fun code():String? {expire();return secret}
    @Synchronized fun remaining():Long {expire();return (until-now()).coerceAtLeast(0)}
    @Synchronized fun pending():Pending? {expire();return request?.takeIf {it.result=="waiting"}?.copy(token=null)}
    @Synchronized fun handingOver():Boolean {expire();return request?.result=="approved"}
    @Synchronized fun close(keepResult:Boolean=false) {
        secret=null;until=0;challenges.clear()
        if(!keepResult || request?.result=="waiting")request=null
        if(!keepResult)denied.clear()
        changed()
    }
    @Synchronized fun acknowledge(token:String):Boolean {
        expire()
        val pending=request ?: return false
        if(pending.result=="approved" && pending.token?.let {CompanionProtocol.same(it,token)}==true){request=null;changed();return true}
        return false
    }
    private fun expire() {
        if(secret!=null && now()>=until){secret=null;challenges.clear()}
        if(request?.let {now()>=it.until}==true)request=null
        denied.entries.removeAll {now()>=it.value.until}
        challenges.entries.removeAll {now()>=it.value.until}
    }
    @Synchronized fun challenge(body:JSONObject):JSONObject {
        expire()
        val key=secret ?: throw CompanionFailure(403,"pairing_closed")
        if(request!=null)throw CompanionFailure(409,"pairing_busy")
        if(now()-rateStart>=60_000){rateStart=now();attempts=0}
        if(++attempts>8 || challenges.size>=4)throw CompanionFailure(429,"slow_down")
        val client=body.getString("nonce");require(CompanionProtocol.hex(client))
        val server=CompanionProtocol.randomHex();val challenge=CompanionProtocol.randomHex(16)
        challenges[challenge]=Challenge(client,server,minOf(until,now()+30_000))
        return JSONObject().put("challenge",challenge).put("nonce",server).put("id",id).put("name",name)
            .put("proof",CompanionProtocol.proof(key,"server",pin,client,server,id)).put("version",1)
    }
    @Synchronized fun request(body:JSONObject):JSONObject {
        expire()
        val key=secret ?: throw CompanionFailure(403,"pairing_closed")
        if(request!=null)throw CompanionFailure(409,"pairing_busy")
        val challenge=challenges.remove(body.getString("challenge")) ?: throw CompanionFailure(403,"pairing_expired")
        val clientId=body.getString("clientId");require(CompanionProtocol.uuid(clientId))
        val rawName=body.getString("name");val clientName=CompanionProtocol.display(rawName,80)
        require(clientName.isNotEmpty() && rawName==clientName)
        val expected=CompanionProtocol.proof(key,"client",pin,challenge.client,challenge.server,clientId,clientName)
        if(!CompanionProtocol.same(expected,body.getString("proof")))throw CompanionFailure(403,"pairing_failed")
        // The QR itself authorizes enrollment. A client cannot downgrade a manual invitation's policy.
        val pending=Pending(CompanionProtocol.randomHex(),clientId,clientName,now()+90_000)
        request=if(confirmationRequired)pending else approve(pending)
        if(!confirmationRequired){secret=null;until=0}
        challenges.clear();changed()
        return JSONObject().put("ticket",pending.ticket).put("status",request!!.result)
    }
    @Synchronized fun decide(ticket:String,allow:Boolean) {
        expire();val pending=request?.takeIf {CompanionProtocol.same(it.ticket,ticket) && it.result=="waiting"} ?: return
        request=if(allow)approve(pending) else pending.copy(result="denied",until=now()+90_000)
        secret=null;challenges.clear();changed()
    }
    private fun approve(pending:Pending):Pending {
        val token=CompanionProtocol.randomHex()
        remember(CompanionPeer(pending.clientId,pending.name,CompanionProtocol.digest(token.toByteArray()),System.currentTimeMillis()))
        return pending.copy(token=token,result="approved",until=now()+90_000)
    }
    @Synchronized fun status(ticket:String):JSONObject {
        expire();val pending=request?.takeIf {CompanionProtocol.same(it.ticket,ticket)} ?: denied[ticket] ?: throw CompanionFailure(403,"pairing_expired")
        return JSONObject().put("status",pending.result).apply {pending.token?.let {put("token",it)}}
    }
}

internal class CompanionFailure(val status:Int,val code:String):Exception(code)
