package it.lumetv.app.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.UUID

class CompanionSecurityTest {
    private val id=UUID.randomUUID().toString()
    private val clientId=UUID.randomUUID().toString()
    private val pin="ab".repeat(32)
    private var elapsed=1000L
    private val peers=mutableListOf<CompanionPeer>()
    private fun pairing()=CompanionPairing(id,"TV",pin,{peers+=it},now={elapsed})
    private fun request(pair:CompanionPairing,code:String,nonce:String="11".repeat(32),name:String="Telefono",client:String=clientId):JSONObject {
        val challenge=pair.challenge(JSONObject().put("nonce",nonce))
        assertEquals(CompanionProtocol.proof(code,"server",pin,nonce,challenge.getString("nonce"),id),challenge.getString("proof"))
        assertFalse(challenge.toString().contains(code))
        return JSONObject().put("challenge",challenge.getString("challenge")).put("clientId",client).put("name",name)
            .put("proof",CompanionProtocol.proof(code,"client",pin,nonce,challenge.getString("nonce"),client,name))
    }
    @Test fun `QR proof authorizes directly and consumes the invitation once`() {
        val p=pairing();val code=p.begin()
        val first=request(p,code)
        val second=request(p,code,nonce="22".repeat(32),client=UUID.randomUUID().toString())
        val reply=p.request(first);val ticket=reply.getString("ticket")
        assertEquals("approved",reply.getString("status"));assertFalse(reply.has("token"))
        assertNull(p.pending());assertTrue(p.handingOver());assertNull(p.code())
        val token=p.status(ticket).getString("token")
        assertEquals(CompanionProtocol.digest(token.toByteArray()),peers.single().tokenHash)
        assertThrows(CompanionFailure::class.java){p.request(first)}
        assertThrows(CompanionFailure::class.java){p.request(second)}
        assertThrows(CompanionFailure::class.java){p.begin()}
        assertEquals(1,peers.size)
        assertTrue(p.acknowledge(token));assertFalse(p.acknowledge(token))
        assertThrows(CompanionFailure::class.java){p.status(ticket)}
        assertThrows(CompanionFailure::class.java){p.request(first)}
    }
    @Test fun `client flags cannot skip TV approval on a manual invitation`() {
        val p=pairing();val code=p.begin(confirmationRequired=true)
        val reply=p.request(request(p,code).put("method","qr").put("confirmationRequired",false).put("approved",true))
        assertEquals("waiting",reply.getString("status"));assertTrue(peers.isEmpty())
        assertFalse(p.status(reply.getString("ticket")).has("token"))
        p.decide(reply.getString("ticket"),true);assertEquals(1,peers.size)
    }
    @Test fun `switching invitation methods invalidates previous codes and challenges`() {
        val p=pairing();val qr=p.begin();val qrRequest=request(p,qr)
        val manual=p.begin(confirmationRequired=true)
        assertNotEquals(qr,manual)
        assertThrows(CompanionFailure::class.java){p.request(qrRequest)}
        val manualRequest=request(p,manual)
        val nextQr=p.begin()
        assertNotEquals(manual,nextQr)
        assertThrows(CompanionFailure::class.java){p.request(manualRequest)}
        assertTrue(peers.isEmpty())
        assertEquals("approved",p.request(request(p,nextQr)).getString("status"))
    }
    @Test fun `expired or closed QR proof cannot authorize a phone`() {
        val p=pairing();val expired=request(p,p.begin())
        elapsed+=120_001
        assertThrows(CompanionFailure::class.java){p.request(expired)}
        val closed=request(p,p.begin());p.close()
        assertThrows(CompanionFailure::class.java){p.request(closed)}
        assertTrue(peers.isEmpty());assertFalse(p.handingOver())
    }
    @Test fun `QR credential delivery gets a full window and survives leaving the page`() {
        val p=pairing();val code=p.begin();elapsed+=119_000
        val ticket=p.request(request(p,code)).getString("ticket")
        p.close(keepResult=true);elapsed+=80_000
        val token=p.status(ticket).getString("token")
        assertTrue(p.handingOver());assertFalse(p.acknowledge("00".repeat(32)))
        assertEquals(token,p.status(ticket).getString("token"))
        elapsed+=10_001
        assertThrows(CompanionFailure::class.java){p.status(ticket)}
        assertFalse(p.handingOver());assertNull(p.code())
        assertEquals(1,peers.size)
    }
    @Test fun `device limit failure never exposes a QR credential`() {
        val p=CompanionPairing(id,"TV",pin,{throw CompanionFailure(409,"too_many_devices")},now={elapsed})
        val failure=assertThrows(CompanionFailure::class.java){p.request(request(p,p.begin()))}
        assertEquals("too_many_devices",failure.code)
        assertNull(p.pending());assertFalse(p.handingOver());assertTrue(peers.isEmpty())
    }
    @Test fun `manual token is issued only after explicit TV approval`() {
        val p=pairing();val code=p.begin(confirmationRequired=true);val ticket=p.request(request(p,code)).getString("ticket")
        assertEquals("waiting",p.status(ticket).getString("status"));assertFalse(p.status(ticket).has("token"));assertTrue(peers.isEmpty())
        p.decide(ticket,true)
        val token=p.status(ticket).getString("token")
        assertTrue(CompanionProtocol.hex(token));assertEquals(CompanionProtocol.digest(token.toByteArray()),peers.single().tokenHash)
        assertFalse(peers.toString().contains(token));assertNull(p.code())
        assertEquals(token,p.status(ticket).getString("token"))
    }
    @Test fun `denial and closing cannot grant control`() {
        val p=pairing();val code=p.begin(confirmationRequired=true);val ticket=p.request(request(p,code)).getString("ticket")
        p.decide(ticket,false);assertEquals("denied",p.status(ticket).getString("status"));assertFalse(p.status(ticket).has("token"))
        p.close();assertThrows(CompanionFailure::class.java){p.status(ticket)};assertTrue(peers.isEmpty())
    }
    @Test fun `leaving pairing page preserves approved credential delivery until acknowledged`() {
        val p=pairing();val ticket=p.request(request(p,p.begin(confirmationRequired=true))).getString("ticket")
        p.decide(ticket,true);p.close(keepResult=true)
        val token=p.status(ticket).getString("token")
        assertThrows(CompanionFailure::class.java){p.begin(confirmationRequired=true)}
        p.acknowledge("00".repeat(32));assertEquals(token,p.status(ticket).getString("token"))
        p.acknowledge(token)
        assertThrows(CompanionFailure::class.java){p.status(ticket)}
        assertTrue(CompanionProtocol.validCode(p.begin(confirmationRequired=true)))
    }
    @Test fun `proof cannot be moved to another certificate or display name`() {
        val p=pairing();val code=p.begin(confirmationRequired=true)
        val challenge=p.challenge(JSONObject().put("nonce","11".repeat(32)))
        assertNotEquals(challenge.getString("proof"),CompanionProtocol.proof(code,"server","cd".repeat(32),"11".repeat(32),challenge.getString("nonce"),id))
        val data=request(p,code);data.put("name","Altro telefono")
        assertThrows(CompanionFailure::class.java){p.request(data)};assertNull(p.pending());assertTrue(peers.isEmpty())
    }
    @Test fun `one time challenges reject replays even after failed proof`() {
        val p=pairing();val code=p.begin(confirmationRequired=true);val data=request(p,code)
        val wrong=JSONObject(data.toString()).put("proof","00".repeat(32))
        assertThrows(CompanionFailure::class.java){p.request(wrong)}
        assertThrows(CompanionFailure::class.java){p.request(data)}
        assertTrue(peers.isEmpty())
    }
    @Test fun `expired invitation and pending approval cannot be reused`() {
        val p=pairing();val code=p.begin(confirmationRequired=true);val ticket=p.request(request(p,code)).getString("ticket")
        elapsed+=120_001;p.decide(ticket,true)
        assertTrue(peers.isEmpty());assertNull(p.code());assertNull(p.pending())
        assertThrows(CompanionFailure::class.java){p.status(ticket)}
        assertThrows(CompanionFailure::class.java){p.challenge(JSONObject().put("nonce","11".repeat(32)))}
    }
    @Test fun `challenge rate cannot be reset by reopening invitation`() {
        val p=pairing()
        repeat(8){val code=p.begin(confirmationRequired=true);val bad=request(p,code).put("proof","00".repeat(32));assertThrows(CompanionFailure::class.java){p.request(bad)}}
        p.begin(confirmationRequired=true);val failure=assertThrows(CompanionFailure::class.java){p.challenge(JSONObject().put("nonce","11".repeat(32)))}
        assertEquals(429,failure.status)
        elapsed+=60_001;p.begin(confirmationRequired=true);p.challenge(JSONObject().put("nonce","11".repeat(32)))
    }
    @Test fun `valid request near QR expiry has a full approval window`() {
        val p=pairing();val code=p.begin(confirmationRequired=true)
        elapsed+=119_000
        val ticket=p.request(request(p,code)).getString("ticket")
        elapsed+=80_000
        assertNull(p.code())
        assertEquals("waiting",p.status(ticket).getString("status"))
        assertThrows(CompanionFailure::class.java){p.begin(confirmationRequired=true)}
        p.decide(ticket,true)
        assertEquals(1,peers.size)
        assertTrue(CompanionProtocol.hex(p.status(ticket).getString("token")))
    }
    @Test fun `credential delivery has its own time window after approval`() {
        val p=pairing();val ticket=p.request(request(p,p.begin(confirmationRequired=true))).getString("ticket")
        elapsed+=89_000;p.decide(ticket,true)
        elapsed+=80_000
        val token=p.status(ticket).getString("token")
        assertTrue(p.handingOver())
        assertFalse(p.acknowledge("00".repeat(32)))
        assertTrue(p.acknowledge(token));assertFalse(p.handingOver());assertFalse(p.acknowledge(token))
    }
    @Test fun `refresh after denial preserves the denied response without granting control`() {
        val p=pairing();val ticket=p.request(request(p,p.begin(confirmationRequired=true))).getString("ticket")
        p.decide(ticket,false)
        val next=p.begin(confirmationRequired=true)
        assertEquals("denied",p.status(ticket).getString("status"));assertFalse(p.status(ticket).has("token"))
        val second=p.request(request(p,next)).getString("ticket")
        assertEquals("waiting",p.status(second).getString("status"));assertTrue(peers.isEmpty())
        elapsed+=90_001
        assertThrows(CompanionFailure::class.java){p.status(ticket)}
        assertThrows(CompanionFailure::class.java){p.status(second)}
    }
    @Test fun `manual codes have eighty random bits and normalize separators only`() {
        val codes=(1..100).map {CompanionProtocol.code()}
        assertEquals(100,codes.toSet().size);assertTrue(codes.all(CompanionProtocol::validCode))
        val code=codes.first();assertEquals(code,CompanionProtocol.normalizeCode(code.lowercase().chunked(4).joinToString("- ")))
        assertFalse(CompanionProtocol.validCode("123456"));assertFalse(CompanionProtocol.validCode("A".repeat(15)+"О"))
    }
    @Test fun `network endpoints reject public DNS loopback and malformed addresses`() {
        listOf("10.0.2.2","192.168.1.15","172.16.0.2","172.31.5.2","169.254.1.3").forEach {assertTrue(it,CompanionProtocol.localHost(it))}
        listOf("127.0.0.1","8.8.8.8","172.32.0.2","172.15.0.2","192.168.0.256","010.0.0.1","localhost","10.0.2.2@evil.test","10.0.2.2/path","10.0.2.2\n").forEach {assertFalse(it,CompanionProtocol.localHost(it))}
    }
    @Test fun `saved credentials can never be used without a certificate pin`() {
        assertThrows(IllegalArgumentException::class.java){CompanionClient(CompanionEndpoint("192.168.1.15",44321),null,"00".repeat(32))}
    }
    @Test fun `a TV becoming unavailable never masquerades as revoked trust`() {
        for(authorized in listOf(false,true)) {
            val offline=assertThrows(CompanionFailure::class.java){CompanionProtocol.authorizeControl(false,authorized)}
            assertEquals(503,offline.status);assertEquals("unavailable",offline.code)
        }
        val revoked=assertThrows(CompanionFailure::class.java){CompanionProtocol.authorizeControl(true,false)}
        assertEquals(401,revoked.status);assertEquals("not_authorized",revoked.code)
        CompanionProtocol.authorizeControl(true,true)
    }
    @Test fun `QR invitations round trip without accepting arbitrary web links`() {
        val invite=CompanionInvite(CompanionEndpoint("192.168.1.15",44321,id),pin,CompanionProtocol.code())
        assertEquals(invite,CompanionInvite.decode(invite.encode()))
        assertThrows(IllegalArgumentException::class.java){CompanionInvite.decode("https://example.com")}
        assertThrows(IllegalArgumentException::class.java){CompanionInvite.decode(invite.encode().replace("192.168.1.15","8.8.8.8"))}
    }
    private fun parse(request:String)=CompanionHttp.readRequest(ByteArrayInputStream(request.toByteArray(Charsets.US_ASCII)))
    @Test fun `HTTP parser accepts native requests and rejects request smuggling`() {
        assertEquals("/v1/state",parse("GET /v1/state HTTP/1.1\r\nHost: tv\r\nAuthorization: Bearer abc\r\n\r\n").path)
        listOf(
            "POST /v1/command HTTP/1.1\r\nHost: tv\r\nContent-Length: 2\r\nContent-Length: 0\r\n\r\n{}",
            "POST /v1/command HTTP/1.1\r\nHost: tv\r\nTransfer-Encoding: chunked\r\n\r\n",
            "GET /v1/state HTTP/1.1\r\nHost: tv\r\nOrigin: https://evil.test\r\n\r\n",
            "GET /v1/state?token=secret HTTP/1.1\r\nHost: tv\r\n\r\n",
            "GET https://evil.test/v1/state HTTP/1.1\r\nHost: tv\r\n\r\n",
            "GET /v1/state HTTP/1.1\r\nHost: tv\r\nContent-Length: -1\r\n\r\n",
            "POST /v1/command HTTP/1.1\r\nHost: tv\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\n{}",
        ).forEach {assertThrows(CompanionFailure::class.java){parse(it)}}
    }
    @Test fun `oversized bodies and headers are rejected before allocation`() {
        val body=assertThrows(CompanionFailure::class.java){parse("POST /v1/command HTTP/1.1\r\nHost: tv\r\nContent-Length: 2147483647\r\n\r\n")}
        assertEquals(413,body.status)
        assertEquals(431,assertThrows(CompanionFailure::class.java){parse("GET /v1/state HTTP/1.1\r\nHost: "+"A".repeat(9000)+"\r\n\r\n")}.status)
    }
}
