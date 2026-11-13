package it.lumetv.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A virtual list: positions are permanent, but only three nearby blocks own display data. */
class ContinuousCatalog<T>(private val scope:CoroutineScope,val blockSize:Int=200,private val initialDelay:Long=120,
    private val changed:(Change)->Unit) {
    data class Change(val start:Int,val count:Int,val sizeChanged:Boolean)
    private val blocks=mutableMapOf<Int,List<T>>()
    private val failed=mutableSetOf<Int>()
    private var wanted=emptyList<Int>()
    private var loader:(suspend (Int)->Pair<List<T>,Int>)?=null
    private var key:Any?=null
    private var task:Job?=null
    private var generation=0
    private var paused=false
    var size=0;private set
    var anchor=0;private set
    var loading=false;private set
    val hasError get()=failed.isNotEmpty()
    val cachedRows get()=blocks.values.sumOf {it.size}
    val cached get()=blocks.toSortedMap().values.flatten()

    fun bind(value:Any,position:Int=0,seed:Pair<List<T>,Int>?=null,load:suspend (Int)->Pair<List<T>,Int>):Boolean {
        if(key==value)return false
        close();key=value;loader=load;anchor=position.coerceAtLeast(0)
        wanted=listOf(anchor/blockSize);loading=true
        if(seed!=null){size=seed.second;blocks[anchor/blockSize]=seed.first.take(blockSize);changed(Change(anchor/blockSize*blockSize,seed.first.size,true));if(size>0)window(anchor,anchor)}
        pump(if(seed==null)initialDelay else 0)
        return true
    }
    fun peek(position:Int):T?=if(position in 0 until size)blocks[position/blockSize]?.getOrNull(position%blockSize) else null
    fun indexOf(predicate:(T)->Boolean):Int?=blocks.entries.firstNotNullOfOrNull {(block,rows)->
        rows.indexOfFirst(predicate).takeIf {it>=0}?.let {block*blockSize+it}
    }
    fun failedAt(position:Int)=position/blockSize in failed
    fun ensure(position:Int) {
        if(position in 0 until size && position/blockSize !in blocks && position/blockSize !in wanted)window(position,position)
        pump()
    }
    fun viewport(first:Int,last:Int) {
        if(size==0)return
        anchor=first.coerceIn(0,size-1)
        window(anchor,last.coerceIn(anchor,size-1));pump()
    }
    private fun window(first:Int,last:Int) {
        val center=first/blockSize
        // Visible blocks precede speculative reads. No queue grows with a long fling.
        wanted=((center..last/blockSize).toList()+listOf(center-1,center+1))
            .filter {it>=0 && it*blockSize<size}.distinct().take(3)
        blocks.keys.retainAll(wanted.toSet());failed.retainAll(wanted.toSet())
    }
    fun retry(position:Int?=null) {
        if(position==null)failed.clear() else failed.remove(position/blockSize)
        pump()
    }
    private fun pump(debounce:Long=0) {
        if(paused || task?.isActive==true || loader==null)return
        val epoch=generation
        val load=loader!!
        task=scope.launch {
            if(debounce>0)delay(debounce)
            while(epoch==generation) {
                val block=wanted.firstOrNull {it !in blocks && it !in failed} ?: break
                loading=true
                try {
                    val (rows,total)=load(block*blockSize)
                    if(epoch!=generation)return@launch
                    val oldSize=size;size=total.coerceAtLeast(0)
                    if(size>0 && block*blockSize>=size) {
                        anchor=(size-1).coerceAtLeast(0);window(anchor,anchor)
                    } else if(block in wanted)blocks[block]=rows.take(blockSize)
                    loading=false
                    changed(Change(block*blockSize,rows.size,oldSize!=size))
                    if(size>0 && wanted.size==1)window(anchor.coerceAtMost(size-1),anchor.coerceAtMost(size-1))
                }catch(cancelled:CancellationException){throw cancelled}
                catch(_:Exception) {
                    if(epoch!=generation)return@launch
                    failed+=block;loading=false;changed(Change(block*blockSize,blockSize,false))
                }
            }
            loading=false
        }
    }
    fun close() {
        generation++;task?.cancel();task=null;key=null;loader=null;paused=false
        blocks.clear();failed.clear();wanted=emptyList();size=0;loading=false
    }
    fun pause(){paused=true;generation++;task?.cancel();task=null;loading=false}
    fun resume(){paused=false;pump()}
    suspend fun refreshLoaded() {
        val epoch=generation;val load=loader ?: return
        for(block in blocks.keys.toList()) {
            val result=try {load(block*blockSize)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){continue}
            val (rows,total)=result
            if(epoch!=generation)return
            if(block in blocks && total==size){blocks[block]=rows.take(blockSize);changed(Change(block*blockSize,rows.size,false))}
        }
    }
}
