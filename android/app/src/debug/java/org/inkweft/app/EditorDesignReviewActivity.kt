// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.inkweft.core.UserLayer
import org.inkweft.core.UserLayers
import java.util.UUID

/** Native, debug-only review surface. Synthetic content; no repositories or authoring writes.
 * Uses the existing layer rules and Material Symbols. It does not replace the real editor.
 */
class EditorDesignReviewActivity : ComponentActivity() {
    internal lateinit var model: EditorReviewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[EditorReviewModel::class.java]
        setContent { InkWeftTheme { EditorReview(model) } }
    }
}

internal data class ReviewStroke(val points: List<Offset>, val color: Color, val width: Float, val layer: String)

class EditorReviewModel : ViewModel() {
    internal var panel by mutableStateOf("")
    internal var comparison by mutableStateOf("对照")
    internal var tool by mutableStateOf("pen")
    internal var color by mutableStateOf(Color(0xff245ca8))
    internal var width by mutableFloatStateOf(2f)
    internal var selectedNode by mutableIntStateOf(1)
    internal var sourceFocused by mutableStateOf(false)
    internal var mapView by mutableStateOf("导图")
    internal val nodeTitles = mutableStateListOf("平均变化率", "瞬时变化率", "几何意义")
    internal var layers by mutableStateOf(UserLayers(listOf(
        UserLayer(UserLayers.DEFAULT_ID, "课堂笔记"),
        UserLayer("00000000-0000-0000-0000-000000000002", "重点批注"),
        UserLayer("00000000-0000-0000-0000-000000000003", "辅助草稿", visible = false),
        UserLayer("00000000-0000-0000-0000-000000000004", "讲义底稿", locked = true)
    ), UserLayers.DEFAULT_ID))
    internal var inspected by mutableStateOf(UserLayers.DEFAULT_ID)
    internal val strokes = mutableStateListOf<ReviewStroke>()
    internal val redo = mutableStateListOf<ReviewStroke>()
    internal val layerHistory = mutableStateListOf<UserLayers>()
    internal fun changeLayers(next: UserLayers) { layerHistory.add(layers); layers = next }
    internal fun togglePanel(next: String) { panel = if (panel == next) "" else next; comparison = "对照" }
}

private val ReviewBlue = Color(0xff245ca8)
private val ReviewMuted = Color(0xff687383)
private val ReviewLine = Color(0xffdfe4ea)
private val ReviewDesk = Color(0xffeef1f5)

@Composable private fun EditorReview(s: EditorReviewModel) {
    Surface(Modifier.fillMaxSize(), color = Color.White) {
        BoxWithConstraints(Modifier.safeDrawingPadding()) {
            val wide = maxWidth >= 1000.dp
            val compact = maxWidth < 600.dp
            Column(Modifier.fillMaxSize().testTag("review-editor")) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ReviewIcon("返回笔记", "back", "review-back") { s.panel = "" }
                    Column(Modifier.weight(1f).padding(start = 6.dp)) {
                        Text("导数与变化率", fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!compact) Text("高等数学 · 第 12 页", fontSize = 11.sp, color = ReviewMuted)
                    }
                    ReviewNav("导图", "mindmap", "review-map", s.panel == "map", !compact) { s.togglePanel("map") }
                    ReviewNav("图层", "layers", "review-layers", s.panel == "layers", !compact) { s.togglePanel("layers") }
                    var more by remember { mutableStateOf(false) }
                    Box {
                        ReviewIcon("更多", "more", "review-more") { more = true }
                        DropdownMenu(more, { more = false }) {
                            DropdownMenuItem(text = { Text("返回书写") }, leadingIcon = { Glyph("pen") }, onClick = { s.panel = ""; more = false })
                            DropdownMenuItem(text = { Text("页面缩略图") }, leadingIcon = { Glyph("grid") }, enabled = false, onClick = {})
                            DropdownMenuItem(text = { Text("导出文档") }, leadingIcon = { Glyph("export") }, enabled = false, onClick = {})
                        }
                    }
                }
                HorizontalDivider(color = ReviewLine)
                WritingTools(s, compact)
                HorizontalDivider(color = ReviewLine)
                when (s.panel) {
                    "map" -> if (wide) {
                        Row(Modifier.weight(1f)) {
                            Paper(s, Modifier.weight(.55f))
                            VerticalDivider(color = ReviewLine)
                            MapPanel(s, Modifier.weight(.45f), false)
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("对照整理", Modifier.weight(1f), fontSize = 13.sp, color = ReviewMuted)
                            listOf("对照", "原文", "导图").forEach { mode ->
                                TextButton({ s.comparison = mode }, Modifier.testTag("review-view-$mode"),
                                    colors = ButtonDefaults.textButtonColors(containerColor = if(s.comparison == mode) Color(0xffe8f0fb) else Color.Transparent)) { Text(mode) }
                            }
                        }
                        if (s.comparison != "导图") Paper(s, Modifier.weight(if (s.comparison == "原文") 1f else .52f))
                        if (s.comparison == "对照") HorizontalDivider(color = ReviewLine, thickness = 4.dp)
                        if (s.comparison != "原文") MapPanel(s, Modifier.weight(if (s.comparison == "导图") 1f else .48f), compact)
                    }
                    "layers" -> if (!compact) {
                        Row(Modifier.weight(1f)) {
                            Paper(s, Modifier.weight(1f))
                            VerticalDivider(color = ReviewLine)
                            LayersPanel(s, Modifier.width(320.dp))
                        }
                    } else {
                        Paper(s, Modifier.weight(1f))
                        HorizontalDivider(color = ReviewLine)
                        LayersPanel(s, Modifier.heightIn(max = 350.dp))
                    }
                    else -> Paper(s, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable private fun ReviewNav(label: String, icon: String, tag: String, selected: Boolean, showLabel: Boolean, click: () -> Unit) {
    if (!showLabel) ReviewIcon(label, icon, tag, selected, click = click)
    else TextButton(click, Modifier.height(48.dp).testTag(tag).semantics { this.selected = selected },
        colors = ButtonDefaults.textButtonColors(contentColor = if(selected) ReviewBlue else ReviewMuted,
            containerColor = if(selected) Color(0xffe8f0fb) else Color.Transparent), shape = RoundedCornerShape(8.dp)) {
        ReviewGlyph(icon, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text(label)
    }
}

@Composable private fun ReviewGlyph(icon: String, modifier: Modifier = Modifier) {
    val resource = when(icon) { "tape" -> R.drawable.ic_review_highlighter; "layers" -> R.drawable.ic_review_layers; "finger" -> R.drawable.ic_review_hand; else -> null }
    if(resource == null) Glyph(icon, modifier = modifier) else Icon(painterResource(resource), null, modifier.size(24.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ReviewIcon(label: String, icon: String, tag: String, selected: Boolean = false, enabled: Boolean = true, click: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(click, Modifier.size(48.dp).testTag(tag).semantics { contentDescription = label; this.selected = selected }, enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(contentColor = if(selected) ReviewBlue else ReviewMuted, containerColor = if(selected) Color(0xffe8f0fb) else Color.Transparent)) { ReviewGlyph(icon) }
    }
}

@Composable private fun WritingTools(s: EditorReviewModel, compact: Boolean) {
    var properties by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Color(0xfffafbfc))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = if(compact) 4.dp else 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            listOf("pen" to "钢笔", "tape" to "荧光笔", "eraser" to "橡皮", "select" to "套索").forEach { (icon, label) ->
                Box {
                    ReviewIcon(label, icon, "review-tool-$icon", s.tool == icon) {
                        if(s.tool == icon && icon == "pen") properties = true
                        s.tool = icon
                    }
                    if(icon == "pen") DropdownMenu(properties, { properties = false }, Modifier.width(256.dp).testTag("review-pen-properties")) {
                        Text("钢笔", Modifier.padding(horizontal = 18.dp, vertical = 10.dp), fontWeight = FontWeight.Medium)
                        Text("笔画粗细", Modifier.padding(horizontal = 18.dp), fontSize = 12.sp, color = ReviewMuted)
                        Slider(s.width, { s.width = it }, valueRange = 1f..6f, modifier = Modifier.padding(horizontal = 18.dp).testTag("review-width-slider"))
                        Row(Modifier.padding(horizontal = 10.dp)) {
                            listOf(Color(0xff263346), ReviewBlue, Color(0xffbb655a), Color(0xff448577)).forEach { color ->
                                Box(Modifier.size(48.dp).clickable { s.color = color; properties = false }.padding(12.dp).background(color, CircleShape))
                            }
                        }
                    }
                }
            }
            if (!compact) {
                ToolDivider(); InkProperties(s); ToolDivider()
            } else Spacer(Modifier.weight(1f))
            ReviewIcon("撤销笔迹", "undo", "review-undo", enabled = s.strokes.isNotEmpty()) { s.redo.add(s.strokes.removeAt(s.strokes.lastIndex)) }
            ReviewIcon("重做笔迹", "redo", "review-redo", enabled = s.redo.isNotEmpty()) { s.strokes.add(s.redo.removeAt(s.redo.lastIndex)) }
            if (!compact) ToolDivider()
            ReviewIcon("移动页面", "finger", "review-tool-finger", s.tool == "finger") { s.tool = "finger" }
        }
        if (compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { InkProperties(s) }
    }
}

@Composable private fun ToolDivider() { VerticalDivider(Modifier.padding(horizontal = 12.dp).height(24.dp), color = ReviewLine) }

@Composable private fun InkProperties(s: EditorReviewModel) {
    listOf(Color(0xff263346) to "墨黑", ReviewBlue to "蓝色", Color(0xffbb655a) to "赭红").forEach { (color, label) ->
        Box(Modifier.size(48.dp).testTag("review-color-$label").semantics { contentDescription = label; selected = s.color == color }
            .clickable(role = Role.RadioButton) { s.color = color }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(28.dp).then(if(s.color == color) Modifier.border(1.5.dp, color, CircleShape) else Modifier).padding(4.dp).background(color, CircleShape))
        }
    }
    Spacer(Modifier.width(12.dp))
    listOf(1f, 2f, 4f).forEach { width ->
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(if(s.width == width) Color(0xffe8f0fb) else Color.Transparent)
            .testTag("review-width-${width.toInt()}").semantics { contentDescription = "笔宽 ${width.toInt()}"; selected = s.width == width }
            .clickable(role = Role.RadioButton) { s.width = width }, contentAlignment = Alignment.Center) {
            Box(Modifier.width(22.dp).height(width.dp).background(if(s.width == width) ReviewBlue else ReviewMuted, CircleShape))
        }
    }
}

@Composable private fun Paper(s: EditorReviewModel, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().background(ReviewDesk).testTag("review-paper")) {
        val small = maxWidth < 500.dp
        val margin = if(small) 12.dp else 24.dp
        val paperScroll = rememberScrollState()
        LaunchedEffect(s.sourceFocused) { if(s.sourceFocused) paperScroll.scrollTo(180) }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if(s.sourceFocused) "原文 · 导数的定义" else "第 12 / 36 页", fontSize = 11.sp, color = ReviewMuted)
                Spacer(Modifier.weight(1f))
                val current = s.layers.layers.firstOrNull { it.id == s.layers.currentId }
                Text(current?.let { "${it.name} · 书写中" } ?: "选择图层后书写", fontSize = 11.sp, color = if(current == null) Color(0xffad5a32) else ReviewMuted)
            }
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = margin).widthIn(max = 860.dp), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 820.dp).fillMaxSize().background(Color.White).border(1.dp, ReviewLine)) {
                    Column(Modifier.fillMaxSize().verticalScroll(paperScroll).padding(horizontal = if(small) 24.dp else 46.dp, vertical = 30.dp)) {
                        Text("03  /  微积分", color = ReviewMuted, fontSize = 12.sp)
                        Spacer(Modifier.height(14.dp))
                        Text("导数：描述瞬时变化", fontWeight = FontWeight.SemiBold, fontSize = if(small) 23.sp else 29.sp, color = Color(0xff253347))
                        Spacer(Modifier.height(20.dp))
                        Text("从平均变化率开始", fontWeight = FontWeight.Medium, fontSize = 18.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("一段路程的平均速度，是位移与时间的比值。\n把时间间隔缩短，就能观察某一时刻的变化。", fontSize = 15.sp, lineHeight = 26.sp, color = Color(0xff4a5668))
                        Spacer(Modifier.height(20.dp))
                        Surface(color = if(s.sourceFocused) Color(0xffffedb2) else Color(0xfff1f5fa), shape = RoundedCornerShape(6.dp)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                                Text("导数的定义", fontSize = 13.sp, color = ReviewBlue)
                                Spacer(Modifier.height(8.dp))
                                Text("f′(x) = lim   [ f(x + h) − f(x) ] / h", fontSize = if(small) 16.sp else 20.sp, fontWeight = FontWeight.Medium)
                                Text("            h → 0", fontSize = 12.sp, color = ReviewMuted)
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("例 1    当 f(x) = x² 时", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(12.dp))
                        Text("f′(x) = lim (2xh + h²) / h = 2x", fontSize = 18.sp, color = ReviewBlue)
                        Spacer(Modifier.height(12.dp))
                        CurveExample(Modifier.fillMaxWidth().height(140.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("在 x = 1 处，切线斜率为 2。", fontSize = 15.sp, color = ReviewBlue)
                        Spacer(Modifier.height(22.dp))
                        HorizontalDivider(color = ReviewLine)
                        Spacer(Modifier.height(22.dp))
                        Text("思考：平均变化率与瞬时变化率有什么关系？", fontSize = 14.sp, color = ReviewMuted)
                        Spacer(Modifier.height(120.dp))
                    }
                    InkOverlay(s, Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable private fun CurveExample(modifier: Modifier) {
    Canvas(modifier) {
        val x = size.width * .14f; val base = size.height * .87f
        drawLine(ReviewLine, Offset(x, 5f), Offset(x, base), 1.5f)
        drawLine(ReviewLine, Offset(x - 12f, base), Offset(size.width * .85f, base), 1.5f)
        val curve = Path().apply { moveTo(x + 8, base - 3); cubicTo(size.width * .42f, base - 2, size.width * .52f, size.height * .44f, size.width * .69f, 12f) }
        drawPath(curve, ReviewBlue, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
        drawLine(Color(0xffbb655a), Offset(size.width * .31f, base), Offset(size.width * .72f, 28f), 1.8.dp.toPx())
        drawCircle(ReviewBlue, 4.dp.toPx(), Offset(size.width * .50f, size.height * .48f))
    }
}

@Composable private fun InkOverlay(s: EditorReviewModel, modifier: Modifier) {
    var live by remember { mutableStateOf(emptyList<Offset>()) }
    Canvas(modifier.testTag("review-ink").pointerInput(s.tool, s.color, s.width, s.layers) {
        if (s.tool !in listOf("pen", "tape", "eraser") || s.layers.currentId == null) return@pointerInput
        detectDragGestures(onDragStart = { live = listOf(it) }, onDragCancel = { live = emptyList() }, onDragEnd = {
            if(s.tool == "eraser") s.strokes.lastOrNull { it.layer == s.layers.currentId }?.let { s.strokes.remove(it); s.redo.add(it) }
            else if(live.size > 1) { s.strokes.add(ReviewStroke(live, s.color, if(s.tool == "tape") 12f else s.width, s.layers.currentId!!)); s.redo.clear() }
            live = emptyList()
        }) { change, _ -> change.consume(); live = live + change.position }
    }) {
        fun draw(points: List<Offset>, color: Color, width: Float) {
            points.zipWithNext().forEach { (a,b) -> drawLine(color.copy(alpha = if(width > 4) .3f else 1f), a, b, width.dp.toPx(), cap = StrokeCap.Round) }
        }
        s.strokes.filter { line -> s.layers.layers.any { it.id == line.layer && it.visible } }.forEach { draw(it.points, it.color, it.width) }
        if(s.tool != "eraser") draw(live, s.color, if(s.tool == "tape") 12f else s.width)
    }
}

@Composable private fun MapPanel(s: EditorReviewModel, modifier: Modifier, compact: Boolean) {
    var editing by remember { mutableStateOf(false) }
    var titleDraft by remember { mutableStateOf("") }
    var viewMenu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(Color(0xfffafbfe)).testTag("review-map-panel")) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("导数", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Box {
                TextButton({ viewMenu = true }, Modifier.testTag("review-map-view")) { Text("${s.mapView}⌄") }
                DropdownMenu(viewMenu, { viewMenu = false }) {
                    listOf("导图", "大纲").forEach { mode -> DropdownMenuItem(text = { Text(mode) }, onClick = { s.mapView = mode; viewMenu = false }) }
                }
            }
            ReviewIcon("收起导图", "close", "review-map-close") { s.panel = "" }
        }
        HorizontalDivider(color = ReviewLine)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).testTag("review-map-body")) {
            val canvasWidth = maxWidth
            val nodeWidth = if(compact) 166.dp else 200.dp
            val nodeInset = if(compact) 12.dp else 20.dp
            val mapTall = !compact && maxHeight >= 360.dp
            val step = if(mapTall) 142.dp else 94.dp
            val vertical = rememberScrollState()
            val density = LocalDensity.current
            val viewportHeight = maxHeight
            LaunchedEffect(compact, s.selectedNode, viewportHeight) {
                if(compact) vertical.scrollTo(with(density) { (60.dp + step * s.selectedNode - viewportHeight / 2).roundToPx().coerceAtLeast(0) })
            }
            if(s.mapView == "大纲") Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                s.nodeTitles.forEachIndexed { i, title ->
                    TextButton({ s.selectedNode = i }, Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(containerColor = if(s.selectedNode == i) Color(0xffe8f0fb) else Color.Transparent)) { Text(title, Modifier.fillMaxWidth().padding(8.dp)) }
                }
            } else Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).verticalScroll(vertical)) {
                Box(Modifier.width(canvasWidth).height(30.dp + step * s.nodeTitles.size + 40.dp)) {
                    Canvas(Modifier.matchParentSize()) {
                        val rootY = if(mapTall) 224.dp.toPx() else 147.dp.toPx()
                        val rootX = (if(compact) 94.dp else 122.dp).toPx()
                        val endX = size.width - (nodeWidth + nodeInset).toPx()
                        repeat(s.nodeTitles.size) { i ->
                            val y = 52.dp.toPx() + i * step.toPx()
                            val p = Path().apply { moveTo(rootX, rootY); cubicTo(rootX + 44, rootY, endX - 36, y, endX, y) }
                            drawPath(p, Color(0xffa7b7cf), style = Stroke(1.5.dp.toPx()))
                        }
                    }
                    Surface(Modifier.offset(x = if(compact) 12.dp else 16.dp, y = if(mapTall) 201.dp else 124.dp).width(if(compact) 82.dp else 108.dp), color = ReviewBlue, shape = RoundedCornerShape(10.dp)) {
                        Text("导数", Modifier.padding(16.dp), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                    s.nodeTitles.forEachIndexed { i, title ->
                        val subtitle = listOf("一段区间内的变化", "让时间间隔趋近于零", "曲线在一点处的切线斜率").getOrElse(i) { "新的学习要点" }
                        Surface(Modifier.align(Alignment.TopEnd).offset(x = -nodeInset, y = 20.dp + step * i).width(nodeWidth)
                            .testTag("review-node-$i").semantics { selected = s.selectedNode == i }.clickable { s.selectedNode = i; s.sourceFocused = false },
                            color = if(s.selectedNode == i) Color(0xffeaf1fc) else Color.White,
                            border = BorderStroke(if(s.selectedNode == i) 1.5.dp else 1.dp, if(s.selectedNode == i) ReviewBlue else ReviewLine), shape = RoundedCornerShape(10.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(6.dp))
                                Text(subtitle, fontSize = 12.sp, lineHeight = 17.sp, color = ReviewMuted)
                                if(mapTall) { Spacer(Modifier.height(12.dp)); Text("↗  第 12 页", fontSize = 11.sp, color = ReviewBlue) }
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = ReviewLine)
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(Color.White).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ s.sourceFocused = true; if(s.comparison == "导图") s.comparison = "对照" }, Modifier.testTag("review-source")) { Glyph("link", modifier = Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("定位原文") }
            Spacer(Modifier.weight(1f))
            ReviewIcon("添加同级要点", "node-sibling", "review-node-add") { s.nodeTitles.add("新要点"); s.selectedNode = s.nodeTitles.lastIndex }
            ReviewIcon("编辑节点", "pen", "review-node-edit") { titleDraft = s.nodeTitles[s.selectedNode]; editing = true }
        }
    }
    if(editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text("编辑要点") },
        text = { OutlinedTextField(titleDraft, { titleDraft = it }, label = { Text("标题") }, singleLine = true, modifier = Modifier.testTag("review-node-title")) },
        confirmButton = { TextButton({ s.nodeTitles[s.selectedNode] = titleDraft.trim(); editing = false }, enabled = titleDraft.isNotBlank()) { Text("完成") } },
        dismissButton = { TextButton({ editing = false }) { Text("取消") } })
}

@Composable private fun LayersPanel(s: EditorReviewModel, modifier: Modifier) {
    Column(modifier.fillMaxSize().background(Color.White).testTag("review-layers-panel")) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("图层", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Medium)
            ReviewIcon("添加图层", "add", "review-layer-add") {
                val layer = UserLayer(UUID.randomUUID().toString(), "新图层 ${s.layers.layers.size}")
                s.changeLayers(s.layers.add(layer)); s.inspected = layer.id
            }
            ReviewIcon("收起图层", "close", "review-layer-close") { s.panel = "" }
        }
        HorizontalDivider(color = ReviewLine)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 10.dp, horizontal = 10.dp)) {
            s.layers.layers.reversed().forEach { layer ->
                val writing = layer.id == s.layers.currentId
                val inspected = layer.id == s.inspected
                Row(Modifier.fillMaxWidth().heightIn(min = 78.dp).padding(vertical = 3.dp)
                    .border(if(inspected) 1.dp else 0.dp, if(inspected) ReviewBlue else Color.Transparent, RoundedCornerShape(8.dp))
                    .background(if(writing) Color(0xffedf3fc) else Color.White, RoundedCornerShape(8.dp))
                    .testTag("review-layer-${layer.name}").clickable { s.inspected = layer.id }.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(Color(0xfff6f8fb), RoundedCornerShape(4.dp)).border(1.dp, ReviewLine, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                        Glyph(if(writing) "pen" else "note", tint = if(layer.visible) ReviewBlue else Color(0xff9ca6b2), modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(layer.name, fontSize = 14.sp, color = if(layer.visible) Color(0xff253347) else ReviewMuted)
                        val label = when { writing -> "正在书写"; inspected -> "查看中"; !layer.visible -> "已隐藏"; layer.locked -> "已锁定"; else -> "" }
                        if(label.isNotEmpty()) Text(label, fontSize = 11.sp, color = if(writing) ReviewBlue else ReviewMuted)
                    }
                    val canHide = !layer.visible || s.layers.layers.any { it.id != layer.id && it.writable }
                    val canLock = layer.locked || s.layers.layers.any { it.id != layer.id && it.writable }
                    ReviewIcon(if(layer.visible) "隐藏${layer.name}" else "显示${layer.name}", if(layer.visible) "eye" else "eye-off", "review-visible-${layer.name}", enabled = canHide) { s.changeLayers(s.layers.update(layer.copy(visible = !layer.visible))) }
                    ReviewIcon(if(layer.locked) "解锁${layer.name}" else "锁定${layer.name}", if(layer.locked) "lock" else "unlock", "review-lock-${layer.name}", enabled = canLock) { s.changeLayers(s.layers.update(layer.copy(locked = !layer.locked))) }
                }
            }
            val chosen = s.layers.layers.first { it.id == s.inspected }
            if(chosen.id != s.layers.currentId) {
                Spacer(Modifier.height(12.dp))
                if(chosen.writable) FilledTonalButton({ s.changeLayers(s.layers.select(chosen.id)) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("review-write-layer")) { Text("在“${chosen.name}”上书写") }
                else Text(if(chosen.locked) "${chosen.name}已锁定，可查看内容。" else "${chosen.name}已隐藏，可先显示内容。", Modifier.padding(10.dp), fontSize = 12.sp, color = ReviewMuted)
            }
        }
        if(s.layerHistory.isNotEmpty()) {
            HorizontalDivider(color = ReviewLine)
            TextButton({ s.layers = s.layerHistory.removeAt(s.layerHistory.lastIndex); if(s.layers.layers.none { it.id == s.inspected }) s.inspected = s.layers.layers.first().id }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("review-layer-undo")) {
                Glyph("undo", modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("撤销上次图层操作")
            }
        }
    }
}
