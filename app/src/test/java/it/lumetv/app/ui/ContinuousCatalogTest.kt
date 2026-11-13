package it.lumetv.app.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContinuousCatalogTest {
    @Test fun `long forward and reverse navigation keeps at most three blocks and the same positions`()=runTest {
        val reads=mutableListOf<Int>()
        val catalog=ContinuousCatalog<Int>(this,changed={})
        catalog.bind("million"){offset->reads+=offset;(offset until minOf(offset+200,1_000_000)).toList() to 1_000_000}
        advanceUntilIdle()
        assertEquals(1_000_000,catalog.size)
        for(position in listOf(190,200,400,600,900,200_000,999_990,400,0)) {
            catalog.viewport(position,position+9);advanceUntilIdle()
            assertEquals(position,catalog.peek(position));assertTrue(catalog.cachedRows<=600)
        }
        assertTrue(reads.count {it==0}>1)
        catalog.close()
    }
    @Test fun `prefetch crosses a block boundary before the next row is visible`()=runTest {
        val catalog=ContinuousCatalog<Int>(this,changed={})
        catalog.bind("source"){o->(o until minOf(o+200,1000)).toList() to 1000};advanceUntilIdle()
        assertEquals(200,catalog.peek(200))
        catalog.viewport(200,214);advanceUntilIdle();assertEquals(400,catalog.peek(400))
    }
    @Test fun `late result from a replaced search is ignored even if its IO ignores cancellation`()=runTest {
        val gate=CompletableDeferred<Unit>()
        val catalog=ContinuousCatalog<String>(this,changed={})
        catalog.bind("old"){withContext(NonCancellable){gate.await()};listOf("old") to 1}
        advanceTimeBy(120);runCurrent()
        catalog.bind("new"){listOf("new") to 1};advanceTimeBy(120);runCurrent()
        gate.complete(Unit);advanceUntilIdle()
        assertEquals("new",catalog.peek(0));assertEquals(1,catalog.size)
    }
    @Test fun `failed reads preserve existing rows and retry only missing blocks`()=runTest {
        var failure=true
        val catalog=ContinuousCatalog<Int>(this,changed={})
        catalog.bind("source"){o->if(o==200 && failure)error("IO");(o until minOf(o+200,450)).toList() to 450}
        advanceUntilIdle();assertEquals(1,catalog.peek(1));assertTrue(catalog.failedAt(200))
        failure=false;catalog.retry(200);advanceUntilIdle()
        assertEquals(201,catalog.peek(201));assertFalse(catalog.hasError)
    }
    @Test fun `initial failure can be retried and closing cancels pending work`()=runTest {
        var failure=true;var changes=0
        val catalog=ContinuousCatalog<Int>(this,changed={changes++})
        catalog.bind("source"){if(failure)error("IO");listOf(1) to 1};advanceUntilIdle()
        assertTrue(catalog.hasError);assertEquals(0,catalog.size)
        failure=false;catalog.retry();advanceUntilIdle();assertEquals(1,catalog.peek(0))
        catalog.bind("pending"){error("Must be cancelled")};catalog.close();advanceUntilIdle()
        assertEquals(0,catalog.size);assertFalse(catalog.hasError);assertEquals(2,changes)
    }
    @Test fun `restored distant position loads directly and clamps after source shrinks`()=runTest {
        val reads=mutableListOf<Int>()
        val catalog=ContinuousCatalog<Int>(this,changed={})
        catalog.bind("source",900_000){o->reads+=o;(o until minOf(o+200,430)).toList() to 430};advanceUntilIdle()
        assertEquals(900_000,reads.first());assertEquals(429,catalog.anchor)
        assertEquals(429,catalog.peek(429));assertEquals(430,catalog.size)
    }
    @Test fun `incremental totals used by programme search extend naturally and stop at the last partial block`()=runTest {
        val catalog=ContinuousCatalog<Int>(this,50,changed={})
        catalog.bind("search"){o->val rows=(o until minOf(o+50,123)).toList();rows to (o+rows.size+if(rows.size==50)1 else 0)}
        advanceUntilIdle();assertEquals(101,catalog.size)
        catalog.viewport(95,100);advanceUntilIdle()
        assertEquals(123,catalog.size);assertEquals(122,catalog.peek(122));assertTrue(catalog.cachedRows<=150)
    }
}
