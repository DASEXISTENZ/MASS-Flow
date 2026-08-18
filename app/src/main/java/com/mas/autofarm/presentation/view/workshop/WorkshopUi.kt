package com.mas.autofarm.presentation.view.workshop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 工坊通用交互组件：
 * 可点击文本带淡色椭圆背景（与纯文本区分，不突兀）。
 */

/** 可点击文本：椭圆淡背景 */
@Composable
fun PillText(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    fontSize: Int = 14,
) {
    val shape = RoundedCornerShape(50)
    val bg = color.copy(alpha = 0.10f)
    Text(
        text = text,
        color = color,
        fontSize = fontSize.sp,
        modifier = modifier
            .clip(shape)
            .background(bg, shape)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** 可点击文本（危险色：删除/取消） */
@Composable
fun PillDangerText(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: Int = 14,
) {
    PillText(text = text, onClick = onClick, modifier = modifier, color = MaterialTheme.colorScheme.error, fontSize = fontSize)
}