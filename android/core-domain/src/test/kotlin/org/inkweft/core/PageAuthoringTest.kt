// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PageAuthoringTest {
    private fun id()=UUID.randomUUID().toString()
    private fun ink()=LayerContent(LayerContentKind.INK,id())
    private fun reject(block:()->Unit){try{block();fail("unsafe author change accepted")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    private fun stroke()=InkStroke(id(),InkPen.PEN,0xff112233.toInt(),3f,InkTool.STYLUS,listOf(InkSample(12f,40f,0,world=true),InkSample(80f,90f,20,world=true)),true)

    @Test fun changingCurrentLayerStopsWritingWithoutChoosingAnother(){
        val second=UserLayer(id(),"批注");val base=UserLayers().add(second)
        val hidden=base.update(second.copy(visible=false))
        assertNull(hidden.currentId);reject{hidden.assignNew(listOf(ink()))}
        assertEquals(UserLayers.DEFAULT_ID,hidden.select(UserLayers.DEFAULT_ID).currentId)
        val locked=base.update(second.copy(locked=true));assertNull(locked.currentId)
        reject{UserLayers().update(UserLayers().layers.single().copy(locked=true))}
        reject{UserLayers().remove(UserLayers.DEFAULT_ID)}
    }
    @Test fun nonemptyDeletionRequiresChoiceTransferIsExplicitAndSnapshotUndoesEverything(){
        val a=ink();val b=ink();val next=UserLayer(id(),"推导")
        val before=UserLayers.legacy(listOf(a)).add(next).assignNew(listOf(b))
        reject{before.remove(next.id)}
        val deleted=before.remove(next.id,LayerDelete.DeleteContents)
        assertEquals(listOf(b),deleted.deleted);assertFalse(deleted.visible(b));assertNull(deleted.currentId)
        val moved=before.remove(next.id,LayerDelete.Transfer(UserLayers.DEFAULT_ID))
        assertTrue(moved.deleted.isEmpty());assertEquals(UserLayers.DEFAULT_ID,moved.layer(b)?.id);assertNull(moved.currentId)
        val restored=PageAuthoringCodec.decode(PageAuthoringCodec.encode(PageAuthoring(before))).layers
        assertEquals(next.id,restored.currentId);assertEquals(next.id,restored.layer(b)?.id);assertTrue(restored.deleted.isEmpty())
    }
    @Test fun lockedMixedSelectionIsExplicitlyAllOrNothingAndOrderingDoesNotChangeOwnership(){
        val a=ink();val b=ink();val second=UserLayer(id(),"保护")
        val before=UserLayers.legacy(listOf(a)).add(second).assignNew(listOf(b)).update(second.copy(locked=true))
        val selected=before.selected(listOf(a,b))
        assertEquals(listOf(a),selected.editable);assertEquals(listOf(b),selected.locked);assertFalse(selected.canEditAll)
        reject{before.requireEditable(listOf(a,b))};reject{before.transfer(listOf(a,b),UserLayers.DEFAULT_ID)}
        assertEquals(second.id,before.move(second.id,0).layer(b)?.id)
    }
    @Test fun twoWhitespacesSeparateOriginalLocalAndDisplayCoordinates(){
        val first=DocumentWhitespace(id(),250.0,300.0);val second=DocumentWhitespace(id(),800.0,450.0)
        val layout=DocumentWhitespaceLayout(listOf(second,first));val original=CanvasPoint(300.0,900.0)
        assertEquals(CanvasPoint(300.0,1650.0),layout.originalToDisplay(original))
        assertEquals(WhitespacePoint.Original(original),layout.displayToAuthor(layout.originalToDisplay(original)))
        assertEquals(WhitespacePoint.Blank(second.id,CanvasPoint(90.0,120.0),false),layout.displayToAuthor(layout.blankToDisplay(second.id,CanvasPoint(90.0,120.0))))
        assertEquals(3,layout.sourceSegments().size)
        val collapsed=DocumentWhitespaceLayout(listOf(first.copy(collapsed=true),second.copy(height=80.0)))
        assertEquals(CanvasPoint(300.0,1008.0),collapsed.originalToDisplay(original))
        assertEquals(CanvasPoint(90.0,1300.0),layout.blankToDisplay(second.id,CanvasPoint(90.0,200.0)))
        assertEquals(CanvasPoint(90.0,1028.0),collapsed.blankToDisplay(second.id,CanvasPoint(90.0,200.0)))
    }
    @Test fun boundInkMovesExactlyOnceAndDetachFreezesWorldPosition(){
        val source=stroke();val original=InkStrokeCodec.encode(source)
        val bound=BoundAnnotation(source,AnnotationTarget(AnnotationTargetKind.MAP_OCCURRENCE,id()))
        val frame=AnnotationFrame(100.0,200.0,2.0)
        val projected=bound.projected(frame)
        assertEquals(124f,projected.samples.first().x,0f);assertEquals(280f,projected.samples.first().y,0f)
        assertArrayEquals(original,InkStrokeCodec.encode(bound.stroke))
        assertEquals(224f,bound.projected(frame.copy(x=200.0)).samples.first().x,0f)
        val detached=bound.detached(frame,id())
        assertEquals(AnnotationTargetKind.PAGE,detached.target.kind)
        assertArrayEquals(InkStrokeCodec.encode(projected),InkStrokeCodec.encode(detached.projected(AnnotationFrame(0.0,0.0))))
    }
    @Test fun backupCodecPreservesHiddenLockedLayersBlanksAndOverflowInk(){
        val blank=DocumentWhitespace(id(),200.0,80.0,true);val extra=DocumentWhitespace(id(),900.0)
        val a=BoundAnnotation(stroke(),AnnotationTarget(AnnotationTargetKind.WHITESPACE,blank.id))
        val hidden=UserLayer(id(),"答案");val locked=UserLayer(id(),"锁定层")
        val state=PageAuthoring(UserLayers().add(hidden),listOf(blank,extra)).add(a)
            .let{it.withLayers(it.layers.update(hidden.copy(visible=false)).add(locked).update(locked.copy(locked=true)))}
        val restored=PageAuthoringCodec.decode(PageAuthoringCodec.encode(state))
        assertEquals(3,restored.layers.layers.size);assertEquals(2,restored.blanks.size);assertNull(restored.layers.currentId)
        assertTrue(restored.visibleAnnotations().isEmpty());assertEquals(90f,restored.annotations.single().stroke.samples.last().y,0f)
        assertEquals(PageAuthoringCodec.fingerprint(state),PageAuthoringCodec.fingerprint(restored))
        reject{PageAuthoringCodec.decode(PageAuthoringCodec.encode(state)+0)}
    }
    @Test fun writingRequiresTheLayerAndConfigurationCapturedAtPointerDown(){
        val base=UserLayers();base.checkWrite(null,0)
        val layer=UserLayer(id(),"第二层");val configured=base.add(layer)
        val captured=configured.writeScope(1)
        configured.checkWrite(captured,1)
        reject{configured.checkWrite(null,1)}
        reject{configured.checkWrite(captured,2)}
        reject{configured.select(UserLayers.DEFAULT_ID).checkWrite(captured,1)}
        reject{configured.update(layer.copy(locked=true)).checkWrite(captured,1)}
    }
    @Test fun twoQueuedPensKeepCapturedScopeWithoutSelfConflictingAndRetryKeepsDigest(){
        val scope=LayerWriteScope(id(),4);val session=InkSession(InkPage(id(),0,emptyList()))
        session.enqueue(InkMutation.Add(stroke()),layerScope=scope)
        session.enqueue(InkMutation.Add(stroke()),layerScope=scope)
        val first=session.nextCommand()!!;assertEquals(scope,first.layerScope)
        val digest=first.digest();session.complete(first,InkCommitResult.Unknown)
        assertSame(first,session.retry());assertEquals(digest,session.nextCommand()!!.digest())
        session.complete(first,InkCommitResult.Committed(1))
        val second=session.nextCommand()!!;assertEquals(1L,second.expectedRevision);assertEquals(scope,second.layerScope)
        session.complete(second,InkCommitResult.Committed(2));assertEquals(2,session.visibleDraft().size)
    }
    @Test fun pageAndBookCopiesCarryHiddenInkAndRemapEveryLocalIdentity(){
        val page=id();val raw=stroke();val blank=DocumentWhitespace(id(),300.0)
        val second=UserLayer(id(),"答案")
        val state=PageAuthoring(UserLayers().add(second),listOf(blank)).add(BoundAnnotation(raw,AnnotationTarget(AnnotationTargetKind.WHITESPACE,blank.id)))
            .let{it.withLayers(it.layers.update(second.copy(visible=false)))}
        val file=InkPageFile("合成资料","",emptyList(),authoring=state)
        val decoded=NotebookFile.decode(NotebookFile("合成资料","",listOf(file)).encode()).pages.single()
        assertEquals(PageAuthoringCodec.fingerprint(state),PageAuthoringCodec.fingerprint(decoded.authoring!!))
        val copied=decoded.authoring!!.copied(page,emptyMap(),emptyMap())
        assertNotEquals(raw.id,copied.annotations.single().stroke.id)
        assertNotEquals(blank.id,copied.blanks.single().id)
        assertEquals(copied.blanks.single().id,copied.annotations.single().target.id)
        assertTrue(copied.visibleAnnotations().isEmpty());assertNull(copied.layers.currentId)
        assertArrayEquals(raw.samples.map{it.x}.toTypedArray(),copied.annotations.single().stroke.samples.map{it.x}.toTypedArray())
    }
    @Test fun defaultPageCopyStillUsesLegacyBytesAndScopedCommandDigestIncludesTarget(){
        val file=InkPageFile("原格式","",emptyList())
        assertNull(InkPageFile.decode(file.encode()).authoring)
        val mutation=InkMutation.Add(stroke());val command=id();val page=id()
        val legacy=CommitInk(command,page,0,mutation)
        val scoped=CommitInk(command,page,0,mutation,LayerWriteScope(UserLayers.DEFAULT_ID,0))
        assertNotEquals(legacy.digest(),scoped.digest())
        assertNotEquals(scoped.digest(),CommitInk(command,page,0,mutation,LayerWriteScope(id(),0)).digest())
    }
    @Test fun extremeDisplayScaleAndDetachUsePureGeometryWithoutClampingAuthorWidths(){
        val original=stroke();val bound=BoundAnnotation(original,AnnotationTarget(AnnotationTargetKind.MAP_OCCURRENCE,id()),24.0)
        val base=AnnotationFrame(9000.0,8000.0,166.0)
        val bounds=bound.displayBounds(base)
        assertTrue(bounds.left>9000.0);assertTrue(bounds.bottom>20_000.0)
        val detached=bound.detached(base,id())
        assertEquals(bounds,detached.displayBounds(AnnotationFrame(0.0,0.0)))
        assertSame(original,detached.stroke)
        val rebound=detached.rebound(AnnotationFrame(0.0,0.0),bound.target,base,24.0)
        val roundTrip=rebound.displayBounds(base)
        assertEquals(bounds.left,roundTrip.left,1e-8);assertEquals(bounds.top,roundTrip.top,1e-8)
        assertEquals(bounds.right,roundTrip.right,1e-8);assertEquals(bounds.bottom,roundTrip.bottom,1e-8);assertSame(original,rebound.stroke)
    }
    @Test fun beautyAndRetainedAuthorInkCannotBeSeparatedByOneLayerTransfer(){
        val raw=ink();val text=PageObject(id(),PageObjectKind.TEXT,text="合成原迹",sourceStrokeIds=listOf(raw.id))
        val ref=LayerContent(LayerContentKind.OBJECT,text.id);val layer=UserLayer(id(),"注释")
        val state=UserLayers.legacy(listOf(raw,ref)).add(layer)
        state.requireBeautyOwnership(listOf(text))
        reject{state.transfer(listOf(ref),layer.id).requireBeautyOwnership(listOf(text))}
        state.transfer(listOf(raw,ref),layer.id).requireBeautyOwnership(listOf(text))
    }
    @Test fun fixedThousandStrokeMembershipUsesStableIndexedLookup(){
        val refs=List(1000){ink()};val layers=UserLayers.legacy(refs)
        val started=System.nanoTime()
        repeat(1_000_000){check(layers.editable(refs[it%refs.size]))}
        println("B4 JVM: 1000 memberships / 1000000 lookups = ${(System.nanoTime()-started)/1_000_000} ms; native frame/input performance NOT_RUN")
        assertEquals(1000,layers.memberships.size)
    }
    @Test fun occurrenceRegionResizeFoldAndCopyPreserveAllOverflowSamples(){
        val objectId=id();val target=AnnotationTarget(AnnotationTargetKind.PAGE_OBJECT,objectId)
        val annotation=BoundAnnotation(stroke(),target,280.0)
        val state=PageAuthoring().withRegion(AnnotationRegion(target,80.0,80.0,true,280.0)).add(annotation)
        val restored=PageAuthoringCodec.decode(PageAuthoringCodec.encode(state))
        assertEquals(state.regions,restored.regions);assertEquals(90f,restored.annotations.single().stroke.samples.last().y,0f)
        val nextObject=id();val copied=restored.copied(id(),emptyMap(),mapOf(objectId to nextObject))
        assertEquals(nextObject,copied.regions.single().target.id);assertEquals(nextObject,copied.annotations.single().target.id)
        assertEquals(80.0,copied.regions.single().height,0.0);assertTrue(copied.regions.single().collapsed)
        val expanded=copied.withRegion(copied.regions.single().copy(height=500.0,collapsed=false))
        assertEquals(copied.annotations.single().stroke.samples,expanded.annotations.single().stroke.samples)
    }
    @Test fun sourceAuthoringScopeChangesDigestWhileOldNullScopeBytesStayCompatible(){
        val page=id();val ink=stroke();val command=id();val card=id()
        fun request(revision:Long?)=StudyCommand(command,page,StudyAction.CREATE_EXCERPT,cardId=card,title="摘录",source=StudySourceDraft(page,3,ink.bounds(),listOf(ink.id),authoringRevision=revision))
        val legacy=StudyCommand(command,page,StudyAction.CREATE_EXCERPT,cardId=card,title="摘录",source=StudySourceDraft(page,3,ink.bounds(),listOf(ink.id)))
        assertEquals(legacy.digest(),request(null).digest())
        assertNotEquals(legacy.digest(),request(0).digest());assertNotEquals(request(0).digest(),request(1).digest())
    }
    @Test fun declaredCapacityRejectsWithoutDiscardingTheAcceptedState(){
        var layers=UserLayers();repeat(UserLayers.MAX_LAYERS-1){layers=layers.add(UserLayer(id(),"层 $it"))}
        val fingerprint=PageAuthoringCodec.fingerprint(PageAuthoring(layers))
        reject{layers.add(UserLayer(id(),"超限"))}
        assertEquals(fingerprint,PageAuthoringCodec.fingerprint(PageAuthoring(layers)))
        reject{DocumentWhitespaceLayout(List(DocumentWhitespaceLayout.MAX_BLANKS+1){DocumentWhitespace(id(),200.0)})}
    }
}
