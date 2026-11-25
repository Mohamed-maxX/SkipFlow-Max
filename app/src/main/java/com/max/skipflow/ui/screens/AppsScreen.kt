package com.max.skipflow.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.EnergySavingsLeaf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.max.skipflow.data.InstalledAppInfo
import com.max.skipflow.ui.AppFilterTab
import com.max.skipflow.ui.MainUiState
import com.max.skipflow.ui.theme.SkipFlowColors

@Composable
fun AppsScreen(
    uiState: MainUiState,
    onSearchQueryChange: (String) -> Unit,
    onFilterTabSelected: (AppFilterTab) -> Unit,
    onToggleApp: (String, Boolean) -> Unit,
    loadAppIcon: suspend (String) -> ImageBitmap?,
    modifier: Modifier = Modifier
) {
    val enabledCount = uiState.installedApps.count { it.packageName in uiState.enabledPackages }
    val disabledCount = (uiState.installedApps.size - enabledCount).coerceAtLeast(0)
    val displayedApps = uiState.filteredApps

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SkipFlowColors.ObsidianBlack)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "إدارة التطبيقات والألعاب",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "قائمة السماح والحظر — تفعيل التخطي الذكي للتطبيقات المحددة فقط",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = SkipFlowColors.DarkNavyCard,
            border = BorderStroke(1.dp, SkipFlowColors.ElectricBlue.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.EnergySavingsLeaf,
                    contentDescription = null,
                    tint = SkipFlowColors.ElectricCyan,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "وضع الخمول التام (صفر استهلاك معالج) عند فتح تطبيقات النظام أو التطبيقات المعطّلة.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = SkipFlowColors.TextPrimary
                )
            }
        }

        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            placeholder = {
                Text(
                    text = "ابحث عن تطبيق أو لعبة...",
                    color = SkipFlowColors.TextSecondary
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = SkipFlowColors.ElectricCyan
                )
            },
            trailingIcon = {
                if (uiState.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Rounded.Clear,
                            contentDescription = "مسح البحث",
                            tint = SkipFlowColors.TextSecondary
                        )
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = SkipFlowColors.DarkNavyCard,
                unfocusedContainerColor = SkipFlowColors.DarkNavyCard,
                focusedBorderColor = SkipFlowColors.ElectricBlue,
                unfocusedBorderColor = SkipFlowColors.NavyBorder,
                focusedTextColor = SkipFlowColors.TextPrimary,
                unfocusedTextColor = SkipFlowColors.TextPrimary,
                cursorColor = SkipFlowColors.ElectricCyan
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppFilterChipItem(
                label = "الكل (${uiState.installedApps.size})",
                selected = uiState.appFilterTab == AppFilterTab.ALL,
                onClick = { onFilterTabSelected(AppFilterTab.ALL) }
            )
            AppFilterChipItem(
                label = "المفعّلة ($enabledCount)",
                selected = uiState.appFilterTab == AppFilterTab.ENABLED,
                onClick = { onFilterTabSelected(AppFilterTab.ENABLED) }
            )
            AppFilterChipItem(
                label = "المعطّلة ($disabledCount)",
                selected = uiState.appFilterTab == AppFilterTab.DISABLED,
                onClick = { onFilterTabSelected(AppFilterTab.DISABLED) }
            )
        }

        when {
            uiState.isLoadingApps -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = SkipFlowColors.ElectricCyan)
                        Text(
                            text = "جاري تحميل التطبيقات والألعاب المثبتة...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            displayedApps.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "لا توجد تطبيقات مطابقة لبحثك",
                        style = MaterialTheme.typography.bodyLarge,
                        color = SkipFlowColors.TextSecondary
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    items(
                        items = displayedApps,
                        key = { it.packageName }
                    ) { app ->
                        val isEnabled = app.packageName in uiState.enabledPackages
                        AppListItemRow(
                            app = app,
                            isEnabled = isEnabled,
                            onToggle = { checked -> onToggleApp(app.packageName, checked) },
                            loadAppIcon = loadAppIcon
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppFilterChipItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        },
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = SkipFlowColors.DarkNavyCard,
            labelColor = SkipFlowColors.TextSecondary,
            selectedContainerColor = SkipFlowColors.ElectricBlue.copy(alpha = 0.22f),
            selectedLabelColor = SkipFlowColors.ElectricCyan
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = SkipFlowColors.NavyBorder,
            selectedBorderColor = SkipFlowColors.ElectricBlue
        )
    )
}

@Composable
private fun AppListItemRow(
    app: InstalledAppInfo,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    loadAppIcon: suspend (String) -> ImageBitmap?
) {
    var iconBitmap by remember(app.packageName) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(app.packageName) {
        iconBitmap = loadAppIcon(app.packageName)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SkipFlowColors.DarkNavyCard),
        border = BorderStroke(
            width = 1.dp,
            color = if (isEnabled) SkipFlowColors.ElectricBlue.copy(alpha = 0.45f) else SkipFlowColors.NavyBorder
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SkipFlowColors.ElevatedNavy)
            ) {
                val bitmap = iconBitmap
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = app.appName,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Text(
                        text = app.appName.take(1).uppercase(),
                        fontWeight = FontWeight.Bold,
                        color = SkipFlowColors.ElectricCyan,
                        fontSize = 16.sp
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = app.appName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SkipFlowColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (app.isYouTube) {
                        AppBadgeChip(
                            text = "افتراضي",
                            color = SkipFlowColors.ElectricCyan
                        )
                    } else if (app.isGame) {
                        AppBadgeChip(
                            text = "لعبة",
                            color = SkipFlowColors.ElectricBlue,
                            leadingIcon = Icons.Rounded.SportsEsports
                        )
                    }
                }
                Text(
                    text = app.packageName,
                    fontSize = 11.sp,
                    color = SkipFlowColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = SkipFlowColors.ElectricBlue,
                    uncheckedThumbColor = SkipFlowColors.TextSecondary,
                    uncheckedTrackColor = SkipFlowColors.ElevatedNavy,
                    uncheckedBorderColor = SkipFlowColors.NavyBorder
                )
            )
        }
    }
}

@Composable
private fun AppBadgeChip(
    text: String,
    color: Color,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.16f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(11.dp)
                )
            }
            Text(
                text = text,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}
