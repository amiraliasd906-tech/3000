package com.divarsmartsearch.app.presentation.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.divarsmartsearch.app.presentation.components.AppTextField
import com.divarsmartsearch.app.presentation.components.SectionHeader
import com.divarsmartsearch.app.presentation.components.effects.AuroraBackdrop

/**
 * Step 1 of "new search". Per explicit user request, the old
 * price-range / area-range / location / property-type / listing-age
 * range filters have been removed from this screen entirely — only the
 * search name and the Divar search-page link remain. Telling agency
 * posts apart from real owners now happens entirely through the keyword
 * filters configured in the next step ("فیلترهای دائمی"); there is no
 * automatic AI/heuristic owner-detection stage anymore.
 *
 * The underlying [com.divarsmartsearch.app.domain.model.SavedSearchDraft]
 * still technically has the old range fields (so existing saved searches
 * from before this change keep working) — they're just never set from
 * this UI anymore. If editing a search that already has one of them set,
 * a "پاک کردن محدوده‌های قدیمی" card appears so the person can clear it
 * instead of being stuck with an invisible filter they can't see or edit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchFormScreen(
    viewModel: NewSearchViewModel,
    onContinue: () -> Unit,
    onOpenFilterPicker: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val draft = state.draft

    if (state.isLoadingForEdit) {
        Scaffold(topBar = { TopAppBar(title = { Text("در حال بارگذاری…") }) }) { padding ->
            com.divarsmartsearch.app.presentation.components.LoadingState(
                modifier = Modifier.padding(padding)
            )
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AuroraBackdrop(
            modifier = Modifier.fillMaxSize(),
            colorA = MaterialTheme.colorScheme.primary,
            colorB = MaterialTheme.colorScheme.secondary,
        )
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(if (state.isEditMode) "ویرایش جستجو" else "جستجوی جدید") },
                    colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    SectionHeader("اطلاعات پایه")
                    AppTextField(
                        value = draft.name,
                        onValueChange = viewModel::updateName,
                        label = "نام جستجو",
                    )
                }
                item {
                    AppTextField(
                        value = draft.searchUrl,
                        onValueChange = viewModel::updateSearchUrl,
                        label = "لینک جستجوی دیوار",
                        keyboardType = KeyboardType.Uri,
                        supportingText = "لینک صفحه نتایج جستجو را از اپ یا سایت دیوار کپی کنید",
                    )
                }
            item {
                Button(
                    onClick = onOpenFilterPicker,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("افزودن فیلتر جدید")
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "تشخیص آگهی شخصی",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            // Bug fix: this used to describe an automatic
                            // AI/heuristic "owner probability between 50%
                            // and 100%" rule. That whole stage was removed
                            // from FilterPipeline per explicit user request
                            // — it was silently rejecting genuine owner
                            // listings whose text didn't match its fixed
                            // phrases. The text now matches what actually
                            // happens: only the person's own keyword
                            // filters decide, nothing filters on its own.
                            text = "این جستجو هیچ آگهی‌ای را به‌طور خودکار رد نمی‌کند. فقط فیلترهای " +
                                "کلمه‌ای که خودتان از صفحه «فیلترهای دائمی» اضافه می‌کنید تصمیم " +
                                "می‌گیرند که یک آگهی رد شود یا نه.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            // Only ever shown when editing a search created before the old
            // price/area/location/property-type/listing-age range fields
            // were removed from this form — a brand-new search never has
            // any of them set. Without this, a person editing such a
            // search would be stuck with an invisible filter (e.g. an old
            // price cap) they have no way to see or clear.
            if (draft.hasLegacyRangeFilters) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "این جستجو محدودهٔ قدیمی (قیمت/متراژ/شهر/نوع ملک) دارد",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "این مقادیر از نسخهٔ قبلی برنامه مانده‌اند، دیگر جایی برای " +
                                    "دیدن یا ویرایششان نیست، ولی همچنان روی نتایج این جستجو اثر " +
                                    "می‌گذارند.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                            )
                            Button(
                                onClick = viewModel::clearLegacyRangeFilters,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("پاک کردن محدوده‌های قدیمی")
                            }
                        }
                    }
                }
            }

            state.formError?.let { error ->
                item {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item {
                Button(
                    onClick = {
                        if (viewModel.validateStepOne()) onContinue()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("ادامه: فیلترهای دائمی")
                }
            }
        }
        }
    }
}
