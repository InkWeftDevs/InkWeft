// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider

/** One landscape composition for visual review. Synthetic notes and in-memory ink only.
 * The parent reviewed the official Goodnotes reference; this executor received its written
 * design specification, not image pixels. Original layout, not a pixel reproduction.
 * The earlier multi-panel proposal remains in EditorDesignReviewActivity for comparison.
 */
class WritingStudyReviewActivity : ComponentActivity() {
    internal lateinit var model: EditorReviewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
        model = ViewModelProvider(this)[EditorReviewModel::class.java]
        setContent { InkWeftTheme { WritingStudyReview(model, ::finish) } }
    }
}

private val StudyInk = Color(0xff303840)
private val StudyBlue = Color(0xff326fba)
private val StudyQuiet = Color(0xff77818a)
private val StudyLine = Color(0xffe4e7e9)

@Composable private fun WritingStudyReview(s: EditorReviewModel, close: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Color(0xfff7f8fa)) {
        Column(Modifier.safeDrawingPadding().fillMaxSize().testTag("study-editor")) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp).testTag("study-document-bar"), verticalAlignment = Alignment.CenterVertically) {
                StudyButton("返回", "back", click = close)
                Text("高等数学", fontSize = 15.sp, color = StudyInk, fontWeight = FontWeight.Medium)
                Text(" / ", Modifier.padding(horizontal = 9.dp), color = Color(0xffaeb5bb), fontSize = 14.sp)
                Text("导数与切线", color = StudyQuiet, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text("12 / 36", color = StudyQuiet, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp))
                StudyButton("页面缩略图", "grid", enabled = false)
                StudyButton("搜索", "search", enabled = false)
                StudyButton("文档更多", "more", enabled = false)
            }
            HorizontalDivider(color = StudyLine, thickness = .5.dp)
            StudyToolbar(s)
            HorizontalDivider(color = StudyLine, thickness = .5.dp)
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xffe9ecef)).padding(horizontal = 24.dp, vertical = 12.dp)) {
                Box(Modifier.fillMaxSize().background(Color(0xfffffefa)).border(.5.dp, Color(0xffd9dde0)).testTag("study-paper")) {
                    StudyNotes(Modifier.fillMaxSize())
                    StudyLiveInk(s, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable private fun StudyButton(label: String, glyph: String, selected: Boolean = false, enabled: Boolean = true, click: () -> Unit = {}) {
    IconButton(click, Modifier.size(48.dp).testTag("study-$glyph").semantics { contentDescription = label; this.selected = selected }, enabled = enabled) {
        Box(Modifier.size(36.dp).background(if(selected) Color(0xffe7eff9) else Color.Transparent, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
            val tint = if(selected) StudyBlue else if(enabled) StudyInk else StudyQuiet
            if(glyph == "highlighter") Icon(painterResource(R.drawable.ic_review_highlighter), null, Modifier.size(24.dp), tint)
            else if(glyph == "panels") Icon(painterResource(R.drawable.ic_review_layers), null, Modifier.size(24.dp), tint)
            else Glyph(glyph, tint = tint)
        }
    }
}

@Composable private fun StudyDivider() {
    VerticalDivider(Modifier.padding(horizontal = 12.dp).height(22.dp), color = StudyLine)
}

@Composable private fun StudyToolbar(s: EditorReviewModel) {
    var properties by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(56.dp).background(Color.White).padding(horizontal = 12.dp).testTag("study-toolbar"), verticalAlignment = Alignment.CenterVertically) {
        StudyButton("撤销", "undo", enabled = s.strokes.isNotEmpty()) { s.redo.add(s.strokes.removeAt(s.strokes.lastIndex)) }
        StudyButton("重做", "redo", enabled = s.redo.isNotEmpty()) { s.strokes.add(s.redo.removeAt(s.redo.lastIndex)) }
        Spacer(Modifier.weight(1f))
        listOf("pen" to "钢笔", "highlighter" to "荧光笔", "eraser" to "橡皮", "select" to "套索").forEach { (tool, label) ->
            Box {
                StudyButton(label, tool, selected = s.tool == tool) {
                    if(tool == "pen" && s.tool == "pen") properties = !properties
                    s.tool = tool
                }
                if(tool == "pen") DropdownMenu(properties, { properties = false }) {
                    Text("笔画粗细", Modifier.padding(16.dp), fontSize = 14.sp)
                    Slider(s.width, { s.width = it }, valueRange = 1f..4f, modifier = Modifier.width(220.dp).padding(horizontal = 16.dp))
                }
            }
        }
        StudyDivider()
        listOf(1f, 2f, 4f).forEach { width ->
            Box(Modifier.size(48.dp).testTag("study-width-${width.toInt()}").semantics { contentDescription = "笔宽 ${width.toInt()}"; selected = s.width == width }
                .clickable { s.width = width }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp).background(if(s.width == width) Color(0xffeef3fa) else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(18.dp).height(width.dp).background(if(s.width == width) StudyBlue else StudyInk, CircleShape))
                }
            }
        }
        StudyDivider()
        listOf(Color(0xff303840), Color(0xff245ca8), Color(0xffb96d69), Color(0xff6f8a7c)).forEachIndexed { index, color ->
            Box(Modifier.size(48.dp).testTag("study-color-$index").semantics { contentDescription = listOf("墨黑", "蓝色", "朱红", "青绿")[index]; selected = s.color == color }
                .clickable { s.color = color }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(26.dp).then(if(s.color == color) Modifier.border(1.2.dp, color, CircleShape) else Modifier)
                    .padding(4.dp).background(color, CircleShape))
            }
        }
        Spacer(Modifier.weight(1f))
        StudyButton("面板", "panels", enabled = false)
        StudyButton("更多工具", "more", enabled = false)
    }
}

/** Authored study fixture, using the already bundled WenKai font, vector math and graph.
 * The graph represents f(x)=x²: P=(1,1), Q=(1.8,3.24); the tangent at P is y=2x−1.
 */
@Composable private fun StudyNotes(modifier: Modifier) {
    val assets = LocalContext.current.assets
    val face = remember(assets) { Typeface.createFromAsset(assets, "fonts/LXGWWenKaiLite-Regular.ttf") }
    Canvas(modifier.semantics { contentDescription = "合成课堂笔记：导数定义、平方函数求导与割线趋近切线示意图" }) {
        val scale = size.width / 1152f
        val grid = 24f * scale
        var pos = grid
        while(pos < size.width) { drawLine(Color(0xffedf0ee), Offset(pos, 0f), Offset(pos, size.height), .6f); pos += grid }
        pos = grid
        while(pos < size.height) { drawLine(Color(0xffedf0ee), Offset(0f, pos), Offset(size.width, pos), .6f); pos += grid }
        drawIntoCanvas { composeCanvas ->
            val c = composeCanvas.nativeCanvas
            c.save(); c.scale(scale, scale)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face }
            val ink = 0xff303c49.toInt(); val blue = 0xff3269ab.toInt(); val quiet = 0xff6f7984.toInt()
            fun text(value: String, x: Float, y: Float, size: Float = 21f, color: Int = ink) {
                paint.color = color; paint.textSize = size; paint.style = Paint.Style.FILL
                c.drawText(value, x, y, paint)
            }
            fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int = blue, width: Float = 1.8f) {
                paint.color = color; paint.strokeWidth = width; paint.strokeCap = Paint.Cap.ROUND
                c.drawLine(x1,y1,x2,y2,paint)
            }
            text("03  导数与切线", 65f, 67f, 32f, blue)
            text("10 月 7 日   /   高等数学", 877f, 65f, 16f, quiet)
            text("从平均变化率，走近瞬时变化率。", 67f, 102f, 19f)
            line(65f,116f,307f,115f,blue,2f)

            text("01  把两点，慢慢靠近", 66f, 166f, 24f, blue)
            text("割线斜率 = 区间内的平均变化率", 69f, 205f)
            text("Δy", 102f, 246f, 25f); line(94f,254f,144f,254f,ink,1.5f); text("Δx", 101f, 283f, 25f)
            text("=", 162f, 267f, 25f)
            text("f(x + h) − f(x)", 206f, 246f, 25f); line(197f,254f,412f,254f,ink,1.5f); text("h", 299f, 283f, 25f)
            text("当 h → 0 时：", 68f, 327f, 21f)
            text("f′(x) = lim", 223f, 327f, 27f, blue)
            text("h → 0", 330f, 347f, 15f, blue)
            text("f(x + h) − f(x)", 391f, 312f, 23f, blue)
            line(385f,321f,584f,321f,blue,1.5f); text("h", 479f, 346f, 23f, blue)

            text("02  试一试：f(x) = x²", 66f, 408f, 24f, blue)
            text("f′(x) = lim (2xh + h²) / h", 88f, 451f, 26f)
            text("h → 0", 191f, 472f, 15f, quiet)
            text("= lim (2x + h) = 2x", 160f, 508f, 26f)
            text("h → 0", 191f, 529f, 15f, quiet)
            text("所以 x = 1 时，切线斜率是 2。", 68f, 569f, 21f)
            line(289f,577f,439f,576f,blue,1.7f)

            text("割线 → 切线", 730f, 163f, 23f, blue)
            // Diagram: x0=699, y0=403, x scale=140, y scale=57.
            val ox=699f; val oy=403f
            fun px(x: Float)=ox+x*140f
            fun py(y: Float)=oy-y*57f
            line(ox-12f,oy,1048f,oy,quiet,1.3f); line(ox,oy+9f,ox,201f,quiet,1.3f)
            line(1048f,oy,1041f,oy-4f,quiet,1.3f); line(1048f,oy,1041f,oy+4f,quiet,1.3f)
            line(ox,201f,ox-4f,208f,quiet,1.3f); line(ox,201f,ox+4f,208f,quiet,1.3f)
            text("x",1059f,410f,18f,quiet); text("y",686f,192f,18f,quiet); text("O",678f,424f,17f,quiet)
            paint.color=blue; paint.strokeWidth=2.3f; paint.style=Paint.Style.STROKE
            val curve=android.graphics.Path().apply {
                moveTo(px(0f),py(0f))
                for(i in 1..110) { val x=i/50f; lineTo(px(x),py(x*x)) }
            }
            c.drawPath(curve,paint)
            // Tangent at P; dashed secant through P and Q.
            line(px(.47f),py(-.06f),px(2.24f),py(3.48f),blue,1.7f)
            paint.color=quiet; paint.strokeWidth=1.3f; paint.pathEffect=android.graphics.DashPathEffect(floatArrayOf(5f,5f),0f)
            c.drawLine(px(.71f),py(.188f),px(2.06f),py(3.968f),paint)
            c.drawLine(px(1f),py(1f),px(1f),oy,paint)
            paint.pathEffect=null; paint.style=Paint.Style.FILL; paint.color=blue
            c.drawCircle(px(1f),py(1f),3.7f,paint); c.drawCircle(px(1.8f),py(3.24f),3.7f,paint)
            text("P",px(1f)-24f,py(1f)-8f,21f,blue)
            text("Q",px(1.8f)+10f,py(3.24f)+6f,21f,blue)
            text("x",px(1f)-5f,425f,18f,quiet)
            text("y = x²",994f,190f,22f,blue)
            text("Q 靠近 P，割线就逐渐趋近切线。",674f,460f,19f)
            text("记住",674f,519f,23f,blue)
            text("导数是局部的变化率，",674f,552f,21f)
            text("也是曲线上这一点的切线斜率。",674f,583f,21f)
            c.restore()
        }
    }
}

@Composable private fun StudyLiveInk(s: EditorReviewModel, modifier: Modifier) {
    var live by remember { mutableStateOf(emptyList<Offset>()) }
    Canvas(modifier.testTag("study-ink").pointerInput(s.tool, s.color, s.width) {
        if(s.tool !in listOf("pen", "highlighter", "eraser")) return@pointerInput
        detectDragGestures(onDragStart = { live = listOf(it) }, onDragCancel = { live = emptyList() }, onDragEnd = {
            if(s.tool == "eraser") s.strokes.lastOrNull()?.let { s.strokes.remove(it); s.redo.add(it) }
            else if(live.size > 1) { s.strokes.add(ReviewStroke(live, s.color, if(s.tool == "highlighter") 12f else s.width, "synthetic")); s.redo.clear() }
            live = emptyList()
        }) { change, _ -> change.consume(); live = live + change.position }
    }) {
        fun stroke(points: List<Offset>, color: Color, width: Float) {
            points.zipWithNext().forEach { (a,b) -> drawLine(color.copy(alpha = if(width > 4f) .25f else 1f),a,b,width.dp.toPx(),cap=StrokeCap.Round) }
        }
        s.strokes.forEach { stroke(it.points,it.color,it.width) }
        if(s.tool != "eraser") stroke(live,s.color,if(s.tool == "highlighter") 12f else s.width)
    }
}
