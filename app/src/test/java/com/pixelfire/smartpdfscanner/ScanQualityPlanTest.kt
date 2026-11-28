package com.pixelfire.smartpdfscanner

import org.junit.Assert.*
import org.junit.Test

class ScanQualityPlanTest {
    @Test fun preservesExistingFilterIds(){
        assertEquals("Original color",ScanQualityPlan.filters[0])
        assertEquals("Enhanced color",ScanQualityPlan.filters[3])
        assertEquals(6,ScanQualityPlan.mode(999))
    }
    @Test fun recognizesLowDetailAndCrispEdges(){
        val flat=IntArray(64*64){230}
        val sharp=IntArray(64*64){if((it%64)/4%2==0)20 else 240}
        assertTrue(ScanQualityPlan.inspect(flat,64,64).lowDetail)
        assertFalse(ScanQualityPlan.inspect(sharp,64,64).lowDetail)
    }
    @Test fun detectsUnevenPaperAndBrightensShadow(){
        val gray=IntArray(128*64){if(it%128<64)120 else 235}
        assertTrue(ScanQualityPlan.inspect(gray,128,64).unevenLight)
        val corrected=ScanQualityPlan.correct(0xff787878.toInt(),120f,4)
        assertTrue(ScanQualityPlan.luminance(corrected)>200)
        val dark=ScanQualityPlan.correct(0xff101010.toInt(),120f,6)
        assertEquals(0,ScanQualityPlan.luminance(dark))
    }
    @Test fun interpolatesPaperMapWithinBounds(){
        val gray=IntArray(96*64){if(it%96<48)120 else 240}
        val paper=ScanQualityPlan.background(gray,96,64)
        assertTrue(paper.at(0,0,96,64) < paper.at(95,63,96,64))
    }
}
