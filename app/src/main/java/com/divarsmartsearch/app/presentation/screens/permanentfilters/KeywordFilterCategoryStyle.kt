package com.divarsmartsearch.app.presentation.screens.permanentfilters

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CorporateFare
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Every keyword filter has a [category] string (see KeywordFilterEntity)
 * that only ever drives *how it looks* in this screen — an icon and an
 * accent color pulled straight from the app's existing coffee/sage/gold
 * theme, so a new filter category never needs a new color to be invented.
 */
data class KeywordFilterCategoryStyle(
    val icon: ImageVector,
    val label: String,
    val color: Color,
)

@Composable
@ReadOnlyComposable
fun keywordFilterCategoryStyle(category: String): KeywordFilterCategoryStyle = when (category) {
    "real_estate" -> KeywordFilterCategoryStyle(
        icon = Icons.Outlined.Apartment,
        label = "املاک",
        color = MaterialTheme.colorScheme.primary,
    )
    "consultant" -> KeywordFilterCategoryStyle(
        icon = Icons.Outlined.SupportAgent,
        label = "مشاور",
        color = MaterialTheme.colorScheme.tertiary,
    )
    "office" -> KeywordFilterCategoryStyle(
        icon = Icons.Outlined.CorporateFare,
        label = "دفتر",
        color = MaterialTheme.colorScheme.secondary,
    )
    "key" -> KeywordFilterCategoryStyle(
        icon = Icons.Outlined.VpnKey,
        label = "کلید",
        color = MaterialTheme.colorScheme.error,
    )
    // Bug fix, per explicit user request: there used to be an "owner"
    // category here styled green with the label "تایید مالک" (owner
    // confirmed), left over from the old owner_signal concept where a
    // match SKIPPED rejection. That override was removed entirely — a
    // filter with this legacy category now rejects a listing on match
    // exactly like every other filter — so showing it with a positive,
    // "this confirms the owner" green badge was actively misleading for
    // anyone with such a filter saved from before. It now falls through
    // to the neutral "custom" style below like any other filter.
    else -> KeywordFilterCategoryStyle(
        icon = Icons.Outlined.Category,
        label = "سفارشی",
        color = MaterialTheme.colorScheme.outline,
    )
}
