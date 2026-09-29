// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*

/** Read-only metadata shared by creation flows. Selecting a template never writes author data. */
@Composable internal fun rememberTemplateCatalog():List<CatalogTemplate>{
    val app=LocalContext.current.applicationContext as InkWeftApplication
    var entries by remember{mutableStateOf<List<CatalogTemplate>>(emptyList())}
    var error by remember{mutableStateOf(false)}
    LaunchedEffect(app){try{entries=app.resourcePacks.catalog()}catch(c:CancellationException){throw c}catch(_:Exception){error=true}}
    if(error)Text("模板目录无法校验，请到设置检查模板包；原笔记保留。",color=Quiet)
    return entries
}
@Composable internal fun InstalledPaperPreview(ref:TemplateRef){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    var bitmap by remember(ref){mutableStateOf<Bitmap?>(null)}
    var paper by remember(ref){mutableStateOf<org.inkweft.core.PaperStyle?>(null)}
    var failed by remember(ref){mutableStateOf(false)}
    LaunchedEffect(ref){try{
        val entry=app.resourcePacks.resource(ref);paper=entry.paper
        val bytes=app.resourcePacks.preview(ref)
        if(bytes!=null){
            var decoded:Bitmap?=null
            try{withContext(Dispatchers.IO){BackgroundBudget.memory(4L*1024*1024){
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                val sample=generateSequence(1){it*2}.first{maxOf(bounds.outWidth,bounds.outHeight)/it<=512}
                decoded=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample;inScaled=false}))
            }};bitmap=decoded;decoded=null}finally{decoded?.recycle()}
        }
    }catch(c:CancellationException){throw c}catch(_:Exception){failed=true}}
    DisposableEffect(bitmap){val current=bitmap;onDispose{current?.recycle()}}
    Box(Modifier.fillMaxSize().background(Color.White)){
        bitmap?.let{Image(it.asImageBitmap(),"模板预览：等比居中",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}
            ?:if(failed)Text("无法预览",color=Quiet)else paper?.let{PaperThumbnail(false,it)}
    }
}
