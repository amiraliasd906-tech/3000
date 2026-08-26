package com.divarsmartsearch.app.data.filters

import com.divarsmartsearch.app.data.local.entity.ListingEntity

/**
 * Applies the permanent phone-number blocklist. Checks both the
 * official contact field and any numbers typed directly into the
 * title/description (common with agencies) — ported from the backend's
 * phone_filter.py.
 */
object PhoneFilter {

    /** Delegates to [PhoneExtraction.normalizeMobileNumber] — see its bug-fix note for why there must only ever be one normalization implementation. */
    fun normalizePhone(raw: String): String = PhoneExtraction.normalizeMobileNumber(raw)

    /** All phone numbers associated with a listing: contact field + anything in the text. */
    fun listingPhoneNumbers(listing: ListingEntity): List<String> {
        val numbers = PhoneExtraction.extractPhoneNumbers(listing.title, listing.description).toMutableList()
        listing.contactPhone?.let {
            val normalized = normalizePhone(it)
            if (normalized.isNotBlank() && normalized !in numbers) numbers.add(0, normalized)
        }
        return numbers
    }

    fun isBlocked(listing: ListingEntity, blockedNumbers: Set<String>): Boolean {
        if (blockedNumbers.isEmpty()) return false
        return listingPhoneNumbers(listing).any { it in blockedNumbers }
    }
}
