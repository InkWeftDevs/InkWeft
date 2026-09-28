// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Test
import org.junit.Assert.*

class ExcerptBoundsTest {
    private val page=CanvasBounds(0.0,0.0,1000.0,1414.0)
    private val b=CanvasBounds(100.0,200.0,400.0,500.0)
    @Test fun eightHandlesMoveOnlyTheirOwnEdges(){
        ExcerptHandle.entries.filter{it!=ExcerptHandle.MOVE}.forEach{h->
            val n=ExcerptBounds.drag(b,h,20.0,30.0,page)
            assertEquals(if(h.x<0)120.0 else 100.0,n.left,0.0)
            assertEquals(if(h.x>0)420.0 else 400.0,n.right,0.0)
            assertEquals(if(h.y<0)230.0 else 200.0,n.top,0.0)
            assertEquals(if(h.y>0)530.0 else 500.0,n.bottom,0.0)
        }
    }
    @Test fun crossingEdgesRetainsMinimumSizeAndMovingClampsToPaper(){
        assertEquals(CanvasBounds(392.0,492.0,400.0,500.0),ExcerptBounds.drag(b,ExcerptHandle.TOP_LEFT,900.0,900.0,page))
        assertEquals(CanvasBounds(700.0,1114.0,1000.0,1414.0),ExcerptBounds.drag(b,ExcerptHandle.MOVE,5000.0,5000.0,page))
        assertEquals(CanvasBounds(0.0,0.0,300.0,300.0),ExcerptBounds.drag(b,ExcerptHandle.MOVE,-5000.0,-5000.0,page))
    }
    @Test fun tinySourceDoesNotGrowWhenDraggedAgainstOppositeEdge(){
        val tiny=CanvasBounds(4.0,5.0,6.0,7.0)
        assertEquals(tiny,ExcerptBounds.drag(tiny,ExcerptHandle.TOP_LEFT,20.0,20.0,page))
    }
}
