package com.pixelfire.smartpdfscanner

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Lightweight on-device scan analysis. These are heuristics, not an AI model. */
internal object ScanQualityPlan {
    val filters = listOf("Original color", "Grayscale", "High contrast B&W", "Enhanced color",
        "Shadow cleanup • color", "Clean paper • grayscale", "Adaptive B&W • text")
    fun mode(value:Int) = value.coerceIn(0,filters.lastIndex)
    fun luminance(argb:Int):Int = (77*(argb ushr 16 and 255)+150*(argb ushr 8 and 255)+29*(argb and 255)) shr 8

    data class Review(val lowDetail:Boolean,val unevenLight:Boolean,val edgeScore:Double)

    fun inspect(gray:IntArray,width:Int,height:Int):Review {
        require(width>=1 && height>=1 && width.toLong()*height==gray.size.toLong())
        if(width<3||height<3)return Review(true,false,0.0)
        var sum=0.0;var sq=0.0;var gradient=0.0;var n=0
        for(y in 1 until height-1)for(x in 1 until width-1){
            val i=y*width+x
            val c=gray[i]
            val lap=(4*c-gray[i-1]-gray[i+1]-gray[i-width]-gray[i+width]).toDouble()
            sum+=lap;sq+=lap*lap
            gradient+=kotlin.math.abs(gray[i+1]-gray[i-1])+kotlin.math.abs(gray[i+width]-gray[i-width])
            n++
        }
        val score=sqrt((sq/n-(sum/n)*(sum/n)).coerceAtLeast(0.0))
        val paper=background(gray,width,height)
        return Review(score<7.0 || gradient/n<2.0,paper.levels.maxOrNull()!!-paper.levels.minOrNull()!!>45,score)
    }

    data class Paper(val columns:Int,val rows:Int,val levels:IntArray){
        fun at(x:Int,y:Int,width:Int,height:Int):Float{
            val gx=((x+0.5f)*columns/width-0.5f).coerceIn(0f,(columns-1).toFloat())
            val gy=((y+0.5f)*rows/height-0.5f).coerceIn(0f,(rows-1).toFloat())
            val ix=gx.toInt();val iy=gy.toInt()
            val nx=(ix+1).coerceAtMost(columns-1);val ny=(iy+1).coerceAtMost(rows-1)
            val fx=gx-ix;val fy=gy-iy
            val top=levels[iy*columns+ix]*(1-fx)+levels[iy*columns+nx]*fx
            val bottom=levels[ny*columns+ix]*(1-fx)+levels[ny*columns+nx]*fx
            return top*(1-fy)+bottom*fy
        }
    }

    fun background(gray:IntArray,width:Int,height:Int):Paper{
        require(width>=1 && height>=1 && width.toLong()*height==gray.size.toLong())
        val cols=(width/32).coerceIn(1,12)
        val rows=(height/32).coerceIn(1,12)
        val levels=IntArray(cols*rows)
        for(ty in 0 until rows)for(tx in 0 until cols){
            val histogram=IntArray(256)
            var count=0
            for(y in ty*height/rows until (ty+1)*height/rows)
                for(x in tx*width/cols until (tx+1)*width/cols){
                    histogram[gray[y*width+x].coerceIn(0,255)]++;count++
                }
            var seen=0;var p=255
            for(i in 0..255){seen+=histogram[i];if(seen>=((count*0.88).toInt()).coerceAtLeast(1)){p=i;break}}
            levels[ty*cols+tx]=p
        }
        return Paper(cols,rows,levels)
    }

    /** Capped local illumination correction; preserve colored text in mode 4. */
    fun correct(argb:Int,paper:Float,mode:Int):Int{
        val gain=(245f/paper.coerceAtLeast(110f)).coerceIn(1f,1.85f)
        fun channel(v:Int)=(v*gain).roundToInt().coerceIn(0,255)
        val r=channel(argb ushr 16 and 255);val g=channel(argb ushr 8 and 255);val b=channel(argb and 255)
        val l=(77*r+150*g+29*b) shr 8
        val clean=if(mode==6) {if(l>=170)255 else 0} else l
        return if(mode==4) (0xff shl 24) or (r shl 16) or (g shl 8) or b
        else (0xff shl 24) or (clean shl 16) or (clean shl 8) or clean
    }
}
