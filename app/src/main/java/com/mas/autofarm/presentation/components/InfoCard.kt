package com.mas.autofarm.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mas.autofarm.theme.MaaDesignTokens

@Composable
private fun BaseCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    containerColor: Color = Color.Unspecified, // 跟随主题（浅色=白卡片，深色=深卡片）
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content,
        )
    }
}

@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    title: String = "",
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    BaseCard(
        modifier = modifier,
        contentPadding = PaddingValues(MaaDesignTokens.Card.innerPadding),
        containerColor = containerColor,
    ) {
        if (title.isNotEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                modifier = Modifier.padding(bottom = MaaDesignTokens.Spacing.sm),
            )
        }
        content()
    }
}

@Composable
fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified, // 跟随主题
    content: @Composable ColumnScope.() -> Unit,
) {
    BaseCard(
        modifier = modifier,
        contentPadding = PaddingValues(
            horizontal = MaaDesignTokens.Card.innerPadding,
            vertical = MaaDesignTokens.Spacing.xs,
        ),
        containerColor = containerColor,
        content = content,
    )
}
