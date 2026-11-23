package it.lumetv.app.companion

import it.lumetv.app.data.WatchMatchException
import it.lumetv.app.data.WatchReference
import it.lumetv.app.playback.PlaybackSnapshot
import it.lumetv.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CompanionWatchReceiverTest {
    private val owner="ab".repeat(32)
    private val channel=Channel("11".repeat(12),"Un film",9999,"","","","","personal","",sourceId="user_"+"22".repeat(16),contentKind=PlaylistContentKind.VOD)
    private val ref=WatchReference(source="11".repeat(32),lookup="22".repeat(32),entry="33".repeat(32),vod=true)
    private fun body(id:String)=JSONObject().put("id",id)
    private suspend fun ready(receiver:CompanionWatchReceiver,id:String,status:String="ready"):JSONObject=withTimeout(5000) {
        var state=receiver.request(owner,"status",body(id))
        while(state.getString("status")!=status){if(state.getString("status")=="failed")error(state.toString());delay(5);state=receiver.request(owner,"status",body(id))}
        state
    }
    @Test fun `read only preview starts once and confirms only the requested video playing at the transferred position`()=runBlocking {
        val state=AtomicReference(PlaybackSnapshot(playbackRevision=7));val opens=AtomicInteger()
        val receiver=CompanionWatchReceiver(this,{channel},{it()},{state.get()},{},{selected,point->
            opens.incrementAndGet();state.set(PlaybackSnapshot(channel=selected,playbackRevision=8,mode=PlaybackMode.VOD,wantsToPlay=true,buffering=true,position=point,duration=180_000,seekable=true))
        },{1000})
        val id=UUID.randomUUID().toString();receiver.request(owner,"resolve",body(id).put("reference",ref.json()));ready(receiver,id)
        assertEquals(0,opens.get())
        val start=body(id).put("position",45_000)
        receiver.request(owner,"start",start)
        withTimeout(5000){while(opens.get()==0)delay(5)}
        assertEquals("starting",receiver.request(owner,"status",body(id)).getString("status"))
        state.set(state.get().copy(ready=true,buffering=false,playing=true,position=45_100))
        val started=receiver.request(owner,"status",body(id));assertEquals("started",started.getString("status"))
        receiver.request(owner,"start",start);assertEquals(1,opens.get())
        assertEquals("watch_conflict",assertThrows(CompanionFailure::class.java){receiver.request(owner,"start",body(id).put("position",90_000))}.code)
        assertFalse(started.toString().contains("https"))
        state.set(state.get().copy(playbackRevision=9))
        assertEquals("watch_tv_changed",receiver.request(owner,"status",body(id)).getString("error"))
    }
    @Test fun `a changed TV selection or a deleted source cannot be replaced by a late start`()=runBlocking {
        val state=AtomicReference(PlaybackSnapshot(playbackRevision=7));val opens=AtomicInteger();var exists=true
        val receiver=CompanionWatchReceiver(this,{if(exists)channel else throw WatchMatchException("watch_missing")},{it()},{state.get()},{},{_,_->opens.incrementAndGet()},{1000})
        val id=UUID.randomUUID().toString();receiver.request(owner,"resolve",body(id).put("reference",ref.json()));ready(receiver,id)
        state.set(state.get().copy(playbackRevision=8));receiver.request(owner,"start",body(id).put("position",0))
        ready(receiver,id,"failed");assertEquals("watch_tv_changed",receiver.request(owner,"status",body(id)).getString("error"));assertEquals(0,opens.get())
        val next=UUID.randomUUID().toString();receiver.request(owner,"resolve",body(next).put("reference",ref.json()));ready(receiver,next)
        exists=false;receiver.request(owner,"start",body(next).put("position",0));ready(receiver,next,"failed")
        assertEquals("watch_missing",receiver.request(owner,"status",body(next)).getString("error"));assertEquals(0,opens.get())
    }
    @Test fun `timeout cancellation and revocation never replay delayed openings`()=runBlocking {
        var elapsed=1000L;val state=AtomicReference(PlaybackSnapshot());val opens=AtomicInteger();val allowed=AtomicBoolean(true)
        var gate:CompletableDeferred<Unit>?=null
        val receiver=CompanionWatchReceiver(this,{gate?.await();channel},{it()},{state.get()},
            {if(!allowed.get())throw CompanionFailure(401,"not_authorized")},{_,_->opens.incrementAndGet()},{elapsed})
        val id=UUID.randomUUID().toString();receiver.request(owner,"resolve",body(id).put("reference",ref.json()));ready(receiver,id)
        assertEquals(404,assertThrows(CompanionFailure::class.java){receiver.request("other-owner","status",body(id))}.status)
        gate=CompletableDeferred();receiver.request(owner,"start",body(id).put("position",0))
        elapsed+=45_001;assertEquals("watch_timeout",receiver.request(owner,"status",body(id)).getString("error"))
        gate!!.complete(Unit);delay(20);assertEquals(0,opens.get());gate=null
        val next=UUID.randomUUID().toString();receiver.request(owner,"resolve",body(next).put("reference",ref.json()));ready(receiver,next)
        receiver.request(owner,"cancel",body(next));assertEquals("watch_not_ready",assertThrows(CompanionFailure::class.java){receiver.request(owner,"start",body(next).put("position",0))}.code)
        allowed.set(false);assertEquals(401,assertThrows(CompanionFailure::class.java){receiver.request(owner,"resolve",body(UUID.randomUUID().toString()).put("reference",ref.json()))}.status)
        assertEquals(0,opens.get())
    }
    @Test fun `start from zero cannot confirm the TV's old video position or a queued opening`()=runBlocking {
        var elapsed=1000L;val state=AtomicReference(PlaybackSnapshot());val opens=AtomicInteger()
        val receiver=CompanionWatchReceiver(this,{channel},{it()},{state.get()},{},{selected,_->
            opens.incrementAndGet();state.set(PlaybackSnapshot(channel=selected,playbackRevision=1,mode=PlaybackMode.VOD,ready=true,playing=true,seekable=true,position=90_000,duration=180_000))
        },{elapsed})
        val id=UUID.randomUUID().toString();receiver.request(owner,"resolve",body(id).put("reference",ref.json()));ready(receiver,id)
        receiver.request(owner,"start",body(id).put("position",0));withTimeout(5000){while(opens.get()==0)delay(5)}
        assertEquals("starting",receiver.request(owner,"status",body(id)).getString("status"))
        elapsed+=45_001;assertEquals("watch_timeout",receiver.request(owner,"status",body(id)).getString("error"))
        val queued=CompanionWatchReceiver(this,{channel},{it()},{PlaybackSnapshot()},{},{_,_->},{elapsed})
        val next=UUID.randomUUID().toString();queued.request(owner,"resolve",body(next).put("reference",ref.json()));ready(queued,next)
        queued.request(owner,"start",body(next).put("position",0));ready(queued,next,"failed")
        assertEquals("watch_failed",queued.request(owner,"status",body(next)).getString("error"))
    }
    @Test fun `a status response captured before opening cannot fail the later successful start`()=runBlocking {
        val state=AtomicReference(PlaybackSnapshot());val calls=AtomicInteger()
        val gate=CompletableDeferred<Unit>();val opened=CompletableDeferred<Unit>()
        val captured=CountDownLatch(1);val release=CountDownLatch(1)
        val executor=Executors.newSingleThreadExecutor {r->Thread(r,"watch-status-race")}
        val receiver=CompanionWatchReceiver(this,{if(calls.incrementAndGet()==2)gate.await();channel},{action->
            action()
            if(Thread.currentThread().name=="watch-status-race"){captured.countDown();check(release.await(5,TimeUnit.SECONDS))}
            else if(state.get().playbackRevision==1L)opened.complete(Unit)
        },{state.get()},{},{selected,point->
            state.set(PlaybackSnapshot(channel=selected,playbackRevision=1,mode=PlaybackMode.VOD,position=point,duration=180_000,ready=true,playing=true,seekable=true))
        },{1000})
        try {
            val id=UUID.randomUUID().toString();receiver.request(owner,"resolve",body(id).put("reference",ref.json()));ready(receiver,id)
            receiver.request(owner,"start",body(id).put("position",42_000))
            val stale=executor.submit<JSONObject>{receiver.request(owner,"status",body(id))}
            assertTrue(captured.await(5,TimeUnit.SECONDS))
            gate.complete(Unit);withTimeout(5000){opened.await()};release.countDown()
            assertNotEquals("failed",stale.get(5,TimeUnit.SECONDS).getString("status"))
            assertEquals("started",receiver.request(owner,"status",body(id)).getString("status"))
        }finally {gate.complete(Unit);release.countDown();executor.shutdownNow();receiver.reset()}
    }
    @Test fun `opening deadline is enforced even when the phone never polls`()=runBlocking {
        val elapsed=AtomicLong(1000);val calls=AtomicInteger();val opens=AtomicInteger()
        val gate=CompletableDeferred<Unit>();val attempted=CompletableDeferred<Unit>()
        val receiver=CompanionWatchReceiver(this,{if(calls.incrementAndGet()==2)gate.await();channel},{action->
            try {action()}finally {if(calls.get()>=2)attempted.complete(Unit)}
        },{PlaybackSnapshot()},{},{_,_->opens.incrementAndGet()},{elapsed.get()})
        val id=UUID.randomUUID().toString();receiver.request(owner,"resolve",body(id).put("reference",ref.json()));ready(receiver,id)
        receiver.request(owner,"start",body(id).put("position",0))
        elapsed.addAndGet(45_001);gate.complete(Unit);withTimeout(5000){attempted.await()}
        assertEquals(0,opens.get())
        assertEquals("watch_timeout",receiver.request(owner,"status",body(id)).getString("error"))
    }
}
