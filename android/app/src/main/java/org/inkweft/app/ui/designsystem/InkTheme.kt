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
    val Surface=Color(0xffffffff);val Navigation=Color(0xfff7f8fa);val Workspace=Color(0xfff1f3f5)
    val Text=Color(0xff22272e);val Secondary=Color(0xff5e6670);val Accent=Color(0xff236653)
    val Selected=Color(0xffeaf3ee);val Divider=Color(0xffdee2e6);val ControlBorder=Color(0xff7e8792);val Danger=Color(0xffb42318)
    @Composable fun Content(content:@Composable ()->Unit){
        MaterialTheme(colorScheme=lightColorScheme(primary=Accent,onPrimary=Color.White,primaryContainer=Selected,
            onPrimaryContainer=Accent,secondary=Accent,onSecondary=Color.White,secondaryContainer=Selected,onSecondaryContainer=Accent,
            background=Surface,onBackground=Text,surface=Surface,onSurface=Text,surfaceVariant=Navigation,onSurfaceVariant=Secondary,
            outline=ControlBorder,outlineVariant=Divider,error=Danger),
            typography=Typography(bodyLarge=TextStyle(fontSize=16.sp,lineHeight=24.sp),bodyMedium=TextStyle(fontSize=14.sp,lineHeight=22.sp),
                bodySmall=TextStyle(fontSize=12.sp,lineHeight=18.sp),labelLarge=TextStyle(fontSize=14.sp,fontWeight=FontWeight.Medium),
                titleLarge=TextStyle(fontSize=24.sp,fontWeight=FontWeight.SemiBold),titleMedium=TextStyle(fontSize=20.sp,fontWeight=FontWeight.Medium)),
            shapes=Shapes(small=RoundedCornerShape(8.dp),medium=RoundedCornerShape(12.dp),large=RoundedCornerShape(16.dp)),content=content)
    }
}
