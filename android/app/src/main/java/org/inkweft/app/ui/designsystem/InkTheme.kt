// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app.ui.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** App chrome only: never use theme colors to rewrite stored document colors. */
object InkTheme {
    val Surface=Color.White;val Navigation=Color(0xfff8f9fb);val Workspace=Color(0xfff5f6f8)
    val Text=Color(0xff20242d);val Secondary=Color(0xff626b79);val Accent=Color(0xff176bb5)
    val Selected=Color(0xffe9f2fb);val Divider=Color(0xffe0e4ec);val ControlBorder=Color(0xff7b8595);val Danger=Color(0xffb42318)
    // Editor pilot roles; other screens keep their existing surfaces until visual review.
    val FloatingShape=RoundedCornerShape(18.dp)
    val ToolShape=RoundedCornerShape(12.dp)
    val FloatingElevation=6.dp
    val ToolElevation=2.dp
    val PanelTitle=TextStyle(fontSize=18.sp,lineHeight=24.sp,fontWeight=FontWeight.SemiBold)
    val PanelInset=16.dp
    const val MotionMillis=160
    @Composable fun Content(content:@Composable ()->Unit){
        MaterialTheme(colorScheme=lightColorScheme(primary=Accent,onPrimary=Color.White,primaryContainer=Selected,
            onPrimaryContainer=Accent,secondary=Accent,onSecondary=Color.White,secondaryContainer=Selected,onSecondaryContainer=Accent,
            background=Surface,onBackground=Text,surface=Surface,onSurface=Text,surfaceVariant=Surface,onSurfaceVariant=Secondary,
            outline=ControlBorder,outlineVariant=Divider,error=Danger,surfaceTint=Color.Transparent,
            surfaceDim=Surface,surfaceBright=Surface,surfaceContainerLowest=Surface,
            surfaceContainer=Surface,surfaceContainerLow=Surface,surfaceContainerHigh=Surface,surfaceContainerHighest=Surface),
            typography=Typography(bodyLarge=TextStyle(fontSize=16.sp,lineHeight=24.sp),bodyMedium=TextStyle(fontSize=14.sp,lineHeight=22.sp),
                bodySmall=TextStyle(fontSize=12.sp,lineHeight=18.sp),labelLarge=TextStyle(fontSize=14.sp,fontWeight=FontWeight.Medium),
                titleLarge=TextStyle(fontSize=24.sp,fontWeight=FontWeight.SemiBold),titleMedium=TextStyle(fontSize=20.sp,fontWeight=FontWeight.Medium)),
            shapes=Shapes(extraSmall=RoundedCornerShape(10.dp),small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(14.dp),large=RoundedCornerShape(20.dp),extraLarge=RoundedCornerShape(24.dp)),content=content)
    }
}
