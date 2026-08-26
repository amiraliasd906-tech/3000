package com.divarsmartsearch.app.domain.model

/**
 * Domain representation of a saved search, independent of any network/DB
 * schema. This is what the UI layer works with.
 */
data class SavedSearch(
    val id: Int,
    val name: String,
    val searchUrl: String,
    val status: SearchStatus,
    val minPrice: Double?,
    val maxPrice: Double?,
    val minArea: Double?,
    val maxArea: Double?,
    val maxPricePerMeter: Double?,
    val city: String?,
    val neighborhood: String?,
    val propertyType: PropertyType?,
    val maxListingAgeHours: Int?,
)

enum class SearchStatus {
    ACTIVE,
    PAUSED,
}

enum class PropertyType {
    APARTMENT,
    VILLA,
    LAND,
    OFFICE,
    SHOP,
    OTHER,
}

/**
 * Draft used while the user is filling out the "new search" form — every
 * field starts nullable/empty and is validated before being sent to the
 * repository as a real SavedSearch creation/update request.
 */
data class SavedSearchDraft(
    val name: String = "",
    val searchUrl: String = "",
    val minPrice: String = "",
    val maxPrice: String = "",
    val minArea: String = "",
    val maxArea: String = "",
    val maxPricePerMeter: String = "",
    val city: String = "",
    val neighborhood: String = "",
    val propertyType: PropertyType? = null,
    val maxListingAgeHours: String = "",
) {
    /**
     * True when this draft is carrying a value in one of the old
     * price/area/location/property-type/listing-age range fields that the
     * "new search" form no longer has any input for (removed per explicit
     * user request — see SearchFormScreen). This only happens when editing
     * a search that was created before that change; a brand-new draft is
     * never in this state. Used to show a one-time "پاک کردن محدوده‌های
     * قدیمی" button so the person isn't stuck with an invisible filter
     * (e.g. an old price cap) they have no way to see or remove from the UI.
     */
    val hasLegacyRangeFilters: Boolean
        get() = minPrice.isNotBlank() || maxPrice.isNotBlank() ||
            minArea.isNotBlank() || maxArea.isNotBlank() ||
            maxPricePerMeter.isNotBlank() || city.isNotBlank() ||
            neighborhood.isNotBlank() || propertyType != null ||
            maxListingAgeHours.isNotBlank()

    /** Wipes every legacy range field, leaving only name + search URL. */
    fun clearLegacyRangeFilters(): SavedSearchDraft = copy(
        minPrice = "", maxPrice = "", minArea = "", maxArea = "",
        maxPricePerMeter = "", city = "", neighborhood = "",
        propertyType = null, maxListingAgeHours = "",
    )
}

/**
 * Used when opening the edit flow — pre-fills the form with the values
 * of a search that already exists on the backend.
 */
fun SavedSearch.toDraft(): SavedSearchDraft = SavedSearchDraft(
    name = name,
    searchUrl = searchUrl,
    minPrice = minPrice?.toPlainString() ?: "",
    maxPrice = maxPrice?.toPlainString() ?: "",
    minArea = minArea?.toPlainString() ?: "",
    maxArea = maxArea?.toPlainString() ?: "",
    maxPricePerMeter = maxPricePerMeter?.toPlainString() ?: "",
    city = city.orEmpty(),
    neighborhood = neighborhood.orEmpty(),
    propertyType = propertyType,
    maxListingAgeHours = maxListingAgeHours?.toString() ?: "",
)

/** Renders a Double without a trailing ".0" for whole numbers, for cleaner form fields. */
private fun Double.toPlainString(): String =
    if (this == this.toLong().toDouble()) this.toLong().toString() else this.toString()
