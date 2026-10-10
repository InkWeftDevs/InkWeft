// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import org.inkweft.core.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/** Keep Android's glyphs and geometry, but preserve author Unicode even when a font aliases glyphs. */
internal object MapDrawingPdf {
    internal fun visibleText(layout:StaticLayout):String = buildString {
        for(line in 0 until layout.lineCount){
            if(line>0)append('\n')
            val start=layout.getLineStart(line);val end=layout.getLineEnd(line)
            val removed=layout.getEllipsisCount(line)
            if(removed==0)append(layout.text.subSequence(start,end).trimEnd('\n','\r'))
            else {
                val at=start+layout.getEllipsisStart(line)
                append(layout.text.subSequence(start,at));append('…')
                append(layout.text.subSequence((at+removed).coerceAtMost(end),end).trimEnd('\n','\r'))
            }
        }
    }

    fun write(snapshot:MapDrawingSnapshot,layouts:Map<String,MapNodeLayout>,groups:List<MapSummaryVisual>,
        fit:MapExportGeometry,output:OutputStream,cacheDir:File){
        val texts=mutableListOf<String?>()
        val bytes=ByteArrayOutputStream()
        val drawing=PdfDocument()
        try {
            fun segment(text:String?=null,paint:(Canvas)->Unit){
                val page=drawing.startPage(PdfDocument.PageInfo.Builder(fit.width,fit.height,texts.size+1).create())
                texts+=text
                page.canvas.translate(fit.translateX.toFloat(),fit.translateY.toFloat())
                page.canvas.scale(fit.scale.toFloat(),fit.scale.toFloat())
                paint(page.canvas);drawing.finishPage(page)
            }
            segment{it.drawColor(Color.WHITE)}
            groups.forEach{group->segment(visibleText(group.text)){MapSummaryPainter.draw(it,listOf(group))}}
            segment{MapScenePainter.draw(it,snapshot.nodes,layout=snapshot.layout,paintNodes=emptyList())}
            val byId=snapshot.nodes.associateBy{it.id}
            snapshot.nodes.forEach{node->
                val metrics=layouts.getValue(node.id)
                val text=listOfNotNull(metrics.title,metrics.summary).joinToString("\n",transform=::visibleText)
                segment(text){MapScenePainter.draw(it,listOfNotNull(node,byId[node.parentId]),
                    presentations=snapshot.presentations,layout=snapshot.layout,connectors=false,paintNodes=listOf(node))}
            }
            drawing.writeTo(bytes)
        }finally{drawing.close()}

        val pdf=Document.openDocument(bytes.toByteArray(),"application/pdf") as PDFDocument
        val saved=File.createTempFile("inkweft-map-text-",".pdf",cacheDir)
        try {
            val forms=pdf.newDictionary()
            val content=StringBuilder()
            texts.forEachIndexed{index,text->
                val page=pdf.findPage(index)
                val attributes=pdf.newDictionary()
                val stream=Buffer()
                try {
                    attributes.put("Type",pdf.newName("XObject"));attributes.put("Subtype",pdf.newName("Form"))
                    attributes.put("BBox",Rect(0f,0f,fit.width.toFloat(),fit.height.toFloat()))
                    attributes.put("Resources",page.getInheritable("Resources"))
                    val contents=page.get("Contents")
                    if(contents.isArray){for(part in contents){stream.writeBytes(part.readStream());stream.writeByte(10)}}
                    else stream.writeBytes(contents.readStream())
                    forms.put("Segment$index",pdf.addStream(stream,attributes))
                    content.append("q\n")
                    if(text!=null){
                        // Each card/summary is a text block. Its terminal newline also prevents
                        // MuPDF from matching a suffix prematurely across Android's font runs.
                        val unicode=("\uFEFF"+text+"\n").toByteArray(Charsets.UTF_16BE).joinToString(""){"%02x".format(it.toInt() and 255)}
                        content.append("/Span << /ActualText <$unicode> >> BDC\n")
                    }
                    content.append("/Segment$index Do\n")
                    if(text!=null)content.append("EMC\n")
                    content.append("Q\n")
                }finally{stream.destroy();attributes.destroy();page.destroy()}
            }
            val resources=pdf.newDictionary().apply{put("XObject",forms)}
            val page=pdf.addPage(Rect(0f,0f,fit.width.toFloat(),fit.height.toFloat()),0,resources,content.toString())
            try{pdf.insertPage(texts.size,page);pdf.rearrangePages(intArrayOf(texts.size))}
            finally{page.destroy();resources.destroy();forms.destroy()}
            pdf.save(saved.absolutePath,"garbage=compact,compress")
            saved.inputStream().use{it.copyTo(output)}
        }finally{pdf.destroy();saved.delete()}
    }
}
