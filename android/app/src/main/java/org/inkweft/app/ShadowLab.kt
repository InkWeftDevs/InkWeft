// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.JSONObject
import java.util.UUID

internal object ShadowRelay {
    suspend fun sync(r:ShadowReplica,t:BackupTransport,library:String){
        r.recoverInbox()
        val pending=r.outgoing();pending.forEach{t.json("POST",library,"/shadow/events",JSONObject(it))}
        do{val batch=t.json("GET",library,"/shadow/events?cursor=${r.cursor()}");val a=batch.getJSONArray("envelopes")
            r.receive(batch.getLong("cursor"),List(a.length()){a.getString(it)})
        }while(batch.getLong("cursor")<batch.getLong("latest"))
        pending.forEach{r.acknowledged(JSONObject(it).getString("operation"))}
    }
}
internal suspend fun shadowSample(context:Context,r:ShadowReplica):String{
    TextStyles.initialize(context)
    var book="";fun id()=UUID.randomUUID().toString()
    r.author{db->
        val pdf=android.graphics.pdf.PdfDocument();val p=pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(1000,1414,1).create())
        p.canvas.drawColor(android.graphics.Color.WHITE)
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=0xff287bbe.toInt();textSize=36f}
        p.canvas.drawText("Probability / Reading notes",60f,100f,paint)
        paint.strokeWidth=2f;paint.color=0xffc6d9e9.toInt();for(y in 170..1350 step 64)p.canvas.drawLine(60f,y.toFloat(),940f,y.toFloat(),paint)
        pdf.finishPage(p);val bytes=java.io.ByteArrayOutputStream().also{pdf.writeTo(it)}.toByteArray();pdf.close()
        book=ResourceTemplates(db).instantiate("b".repeat(64),"合成课堂 · 条件概率",PaperStyle.BLANK,PdfPageSource(PdfDocumentSource(bytes,1),0),null).id
        val stroke=InkStroke(id(),InkPen.PENCIL,0xff3159b8.toInt(),5f,InkTool.STYLUS,List(32){InkSample(100f+it*10,200f+it%5*5,it*10L,.3f+it*.01f)})
        val ink=InkRepository(db);ink.save(CommitInk(id(),book,0,InkMutation.Add(stroke)))
        ink.save(CommitInk(id(),book,1,InkMutation.Cut(EraseSelection(InkCut(id(),8f,listOf(EraserPoint(220f,180f),EraserPoint(220f,260f))),listOf(stroke.id)))))
        StudyRepository(db).submit(CaptureDraft(book,StudySourceDraft(book,2,stroke.bounds(),listOf(stroke.id)),"条件概率\n先明确样本空间，再筛选事件 B。\nP(A|B)=P(A∩B)/P(B)").command(MapRef(book),null,MapGraphAccess(db).read(book).first().graphHash))
        val writing=InkStroke(id(),InkPen.PEN,0xff243243.toInt(),4f,stroke.tool,stroke.samples.map{it.copy(y=it.y+120)},appearance=StrokeAppearance(BrushRecipe.LEGACY))
        ink.save(CommitInk(id(),book,2,InkMutation.Add(writing)))
        val text=beautyObject(listOf(writing),RecognizedWriting("课堂复习：事件与概率",1f,1),BeautyOptions(),false,revision=3)
        val bitmap=android.graphics.Bitmap.createBitmap(120,80,android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xffddecf8.toInt());android.graphics.Canvas(bitmap).drawText("P(A)",10f,45f,paint.apply{textSize=24f;color=0xff287bbe.toInt()})
        val jpeg=java.io.ByteArrayOutputStream().also{bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,100,it)}.toByteArray();bitmap.recycle()
        PageObjectRepository(db).save(book,0,id(),listOf(text,PageObject(id(),PageObjectKind.IMAGE,70f,420f,280f,187f,image=java.util.Base64.getEncoder().encodeToString(jpeg)),PageObject(id(),PageObjectKind.MAP,70f,670f,850f,550f,mapEmbed=MapEmbed(MapRef(book)))))
    };return book
}
@Composable internal fun ShadowLabDialog(identity:BackupIdentity,dismiss:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope();var a by remember{mutableStateOf<ShadowAuthorSession?>(null)};var b by remember{mutableStateOf<ShadowAuthorSession?>(null)}
    var editing by remember{mutableStateOf(false)}
    var receiver by remember{mutableStateOf<BackupIdentity?>(null)}
    var library by remember{mutableStateOf("")};var book by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("使用两个一次性资料库，仅传输合成样本。服务器需启用隔离 Relay。")};var tick by remember{mutableIntStateOf(0)};var showB by remember{mutableStateOf(true)}
    val record=remember{android.util.AtomicFile(java.io.File(context.cacheDir,"shadow-ui-session.json"))}
    LaunchedEffect(Unit){if(record.baseFile.exists()){
        busy=true
        try{withContext(Dispatchers.IO){
            val j=JSONObject(record.openRead().bufferedReader().use{it.readText()});val binding=j.getJSONArray("scope").let{v->List(v.length()){v.getString(it)}}
            require(binding.take(3)==listOf(identity.server,identity.issuer,identity.user)&&j.getString("authorDevice")==identity.device){"SHADOW_SCOPE"}
            val keys=mapOf(1 to EncryptedBackupFile.unb64(j.getString("syntheticKey")))
            a=ShadowAuthorSession(context,ShadowReplica.open(context,binding,identity.device,keys,directory=java.io.File(j.getString("author"))))
            b=ShadowAuthorSession(context,ShadowReplica.open(context,binding,j.getString("receiverDevice"),keys,directory=java.io.File(j.getString("receiver"))))
            library=binding.last();book=j.getString("book")
        }}catch(c:CancellationException){throw c}catch(_:Exception){message="已有实验会话无法核对，原隔离目录保留；请核对原账号与服务器。"}finally{busy=false}
    }}
    fun run(action:suspend()->Unit){if(busy)return;busy=true;scope.launch{try{withContext(Dispatchers.IO){action()};tick++}catch(c:CancellationException){throw c}catch(_:Exception){message="实验未确认，隔离资料保留；请核对服务连接后重试。"}finally{busy=false}}}
    androidx.activity.compose.BackHandler(busy||editing){}
    Dialog({if(!busy&&!editing)dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize()) {Column{
            Row(Modifier.fillMaxWidth().padding(8.dp)){
                TextButton({if(!busy)dismiss()},enabled=!busy&&!editing){Text("关闭")}
                TextButton({showB=!showB},enabled=!busy&&!editing&&b!=null){Text(if(showB)"接收库 B"else"创作库 A")}
                TextButton({run{val transport=BackupTransport(identity);transport.verifyServer()
                    if(receiver==null){val device=if(record.baseFile.exists())JSONObject(record.openRead().bufferedReader().use{it.readText()}).getString("receiverDevice")else UUID.randomUUID().toString();receiver=BackupTransport.login(identity.url,"synthetic-alice","synthetic-alice-password-123",device)}
                    if(a==null){require(!record.baseFile.exists());require(identity.name=="synthetic-alice"&&java.net.URI(identity.url).host in setOf("127.0.0.1","localhost"));library=UUID.randomUUID().toString();transport.json("PUT",library);val binding=listOf(identity.server,identity.issuer,identity.user,library);val keys=mapOf(1 to EncryptedBackupFile.random(32))
                        a=ShadowAuthorSession(context,ShadowReplica.open(context,binding,identity.device,keys));b=ShadowAuthorSession(context,ShadowReplica.open(context,binding,receiver!!.device,keys));book=shadowSample(context,a!!.replica)
                        val value=JSONObject().put("scope",org.json.JSONArray(binding)).put("authorDevice",identity.device).put("receiverDevice",receiver!!.device).put("author",a!!.replica.directory.path).put("receiver",b!!.replica.directory.path).put("book",book).put("syntheticKey",EncryptedBackupFile.b64(keys.getValue(1)))
                        val stream=record.startWrite();try{stream.write(value.toString().toByteArray());record.finishWrite(stream)}catch(t:Throwable){record.failWrite(stream);throw t}
                    }
                    ShadowRelay.sync(a!!.replica,transport,library);ShadowRelay.sync(b!!.replica,BackupTransport(receiver!!),library);ShadowRelay.sync(a!!.replica,transport,library);message="合成资料已交换，可查看来源、编辑知识卡后再次交换。"
                }},enabled=!busy&&!editing){Text(if(a==null)"开始合成实验"else"交换修改")}
            }
            Text(message,Modifier.padding(horizontal=12.dp),style=MaterialTheme.typography.bodySmall)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            val active=if(showB)b else a
            if(active!=null&&book.isNotEmpty())Box(Modifier.weight(1f)){key(active){ShadowReplicaPanel(active,book,tick){editing=it}}}
        }}
    }
    DisposableEffect(Unit){onDispose{a?.replica?.close();b?.replica?.close()}}
}
