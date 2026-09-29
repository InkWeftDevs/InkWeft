package org.inkweft.core
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

class LearningWidgetsTest {
    private fun id()=UUID.randomUUID().toString()
    @Test fun sharingDoesNotCarryPrivateTargetsOrUnknownProviders(){
        val target=StableTargetRef(LearningTargetKind.BRANCH,id(),id(),id())
        val local=LearningWidgets.defaults().map{it.copy(targets=listOf(target))}
        val unknown=WidgetInstance(id(),"external.provider/continue")
        val shared=LearningWidgets.shareLayout(local+unknown)
        assertEquals(4,shared.size);assertTrue(shared.all{it.targets.isEmpty()})
        assertTrue(shared.none{s->local.any{it.id==s.id}})
        assertEquals(listOf(target),local.first().targets)
        assertFalse(LearningWidgets.definitions.first().accepts(unknown))
    }
    @Test fun reorderKeepsInstanceConfigurationAndUnknownProvider(){
        val items=LearningWidgets.defaults()+WidgetInstance(id(),"external.provider/maps",3,false,WidgetSize.LARGE)
        val moved=LearningWidgets.move(items,items.last().id,-3)
        assertEquals(items.last(),moved[1]);assertEquals(items.toSet(),moved.toSet())
        assertFalse(LearningWidgets.definitions.last().accepts(items.last()))
    }
    @Test fun targetsUseIdentityAndValidateRequiredComponents(){
        val book=id();val map=StableTargetRef(LearningTargetKind.MAP,book,id())
        assertEquals(map,map.copy())
        assertThrows(IllegalArgumentException::class.java){StableTargetRef(LearningTargetKind.CARD,book)}
        assertThrows(IllegalArgumentException::class.java){StableTargetRef(LearningTargetKind.NOTE,"My title")}
    }
}
