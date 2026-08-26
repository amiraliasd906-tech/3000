package com.divarsmartsearch.app.data.filters

import com.divarsmartsearch.app.data.local.dao.BlockedPhoneDao
import com.divarsmartsearch.app.data.local.dao.KeywordFilterDao
import com.divarsmartsearch.app.data.local.dao.ListingInteractionDao
import com.divarsmartsearch.app.data.local.entity.ListingEntity
import com.divarsmartsearch.app.data.local.entity.ListingInteractionEntity
import com.divarsmartsearch.app.data.local.entity.SavedSearchEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the full filter pipeline on a batch of listings, in order,
 * mirroring the original backend's apply_filters.py:
 *   1. Structured range filters (price/area/price-per-meter) from the SavedSearch.
 *   2. Permanent phone-number blocklist (official field + text-embedded numbers).
 *   3. Hard keyword exclusion — every ENABLED [com.divarsmartsearch.app.data.local.entity.KeywordFilterEntity]
 *      row (entirely user-added — the app ships with none of its own) is
 *      checked independently against the title and description; the
 *      listing is rejected the moment it matches ANY one of them (see
 *      [KeywordFilterEngine]). This is now the ONLY thing that can reject a
 *      listing on content grounds — the previous automatic AI/heuristic
 *      "owner probability" stage has been removed per explicit user
 *      request, since it was silently rejecting genuine owner listings
 *      whose text didn't happen to contain one of its fixed phrases, even
 *      when the person hadn't added any keyword filter of their own. A
 *      listing the person never told the app to reject is never rejected.
 *
 * Never-auto-hide guarantee (range filters + blocked-phone list only): any
 * listing that is already visible (part of the live results) when a
 * pipeline run starts is protected from stages 1 and 2 above — a later
 * price/area edit or a number getting added to the blocklist afterwards
 * will never retroactively hide it.
 *
 * This guarantee does NOT extend to keyword exclusion (stage 3): per
 * explicit user request, a listing that turns out to match an exclude
 * keyword is hidden the moment that's found, even if it was already
 * visible from an earlier pass that only had incomplete (list-preview)
 * text to go on. Otherwise a listing wrongly shown before its real
 * detail-page description arrived would stay visible forever. Outside of
 * that, a listing only ever leaves the results because of stages 1–3
 * above or because the person explicitly saves or rejects it themselves
 * from the Results screen (see ListingRepositoryImpl.saveListing/rejectListing
 * and ListingEntity.userDecided).
 *
 * Mutates each ListingEntity's isVisible/isLikelyAgency/ownerProbability
 * fields in place and returns the list that survived every stage.
 */
@Singleton
class FilterPipeline @Inject constructor(
    private val blockedPhoneDao: BlockedPhoneDao,
    private val listingInteractionDao: ListingInteractionDao,
    private val listingEnricher: ListingEnricher,
    private val keywordFilterDao: KeywordFilterDao,
) {
    suspend fun apply(
        savedSearch: SavedSearchEntity,
        listings: List<ListingEntity>,
        anthropicApiKey: String?,
        anthropicModel: String,
        // Ids of listings that were ALREADY part of the live results (i.e.
        // isVisible == true in the database) before this run started — NOT
        // inferred from the in-memory isVisible field on [listings], since
        // a brand-new not-yet-decided listing also defaults to isVisible =
        // true and must never be mistaken for an already-shown one. The
        // caller (which just read these from the DB, or knows which rows
        // are brand-new inserts) is the only one who reliably knows which
        // is which. Ids in this set are protected for the whole run — see
        // the never-auto-hide guarantee above.
        alreadyVisibleIds: Set<Long> = emptySet(),
    ): List<ListingEntity> {
        if (listings.isEmpty()) return emptyList()

        populateDetectedPhoneNumbers(listings)

        // Cross-listing signal: how often this ad's phone number(s) show up
        // elsewhere — used by ListingEnricher's star rating and shown on
        // the Seller Report.
        for (listing in listings) {
            listing.phoneRepeatCount = listingEnricher.computePhoneRepeatCount(listing)
        }

        val protectedIds = alreadyVisibleIds

        applyRangeFilters(savedSearch, listings, protectedIds)
        for (listing in listings) {
            if (!listing.isVisible && listing.id !in protectedIds) recordRejection(listing, "out_of_filter_range")
        }

        val blockedNumbers = blockedPhoneDao.getAllNumbers().toSet()
        for (listing in listings) {
            if (!listing.isVisible) continue // already dropped by range filter above
            if (listing.id in protectedIds) continue // never auto-hidden
            if (PhoneFilter.isBlocked(listing, blockedNumbers)) {
                listing.isVisible = false
                recordRejection(listing, "blocked_phone")
            }
        }
        val phoneSurvivors = listings.filter { it.isVisible }

        // Per explicit user request: the only thing that decides
        // exclusion here now is the person's OWN enabled keyword filters
        // — every enabled KeywordFilterEntity is treated the same way
        // (the old "owner_signal" filter type no longer gets any special
        // override power), and the automatic AI/heuristic agency-probability
        // stage (OwnerDetector) has been removed entirely. A listing is
        // rejected only if it matches one of the person's own filters;
        // otherwise it's kept, full stop — nothing filters on its own
        // when the person hasn't added any keyword filter.
        val activeKeywordFilters = keywordFilterDao.getAllEnabled()

        val finalKept = mutableListOf<ListingEntity>()
        for (listing in phoneSurvivors) {
            // Checked against BOTH the title and the description: a huge share of
            // agency posts on Divar put the keyword in the TITLE only (e.g.
            // "مشاور املاک رضایی"، "فایل ویژه - املاک ..."), so checking the
            // description alone was letting most of them straight through.
            val matchedFilter = KeywordFilterEngine.findFirstMatch(
                listing.title, listing.description, activeKeywordFilters
            )
            if (matchedFilter != null) {
                listing.isLikelyAgency = true
                listing.ownerProbability = 0.0
                // Per explicit user request: a keyword-filter match overrides
                // the never-auto-hide guarantee. A listing shown earlier
                // based on incomplete (list-preview-only) text and later
                // confirmed by a real detail-page description to match an
                // exclude keyword must be removed from results, not
                // permanently protected. isProtected is intentionally
                // ignored here.
                listing.isVisible = false
                recordRejection(listing, "keyword_filter:${matchedFilter.label}")
                continue
            }

            listing.isLikelyAgency = false
            listing.ownerProbability = 1.0
            finalKept.add(listing)
        }

        // Enrichment that only makes sense for listings the person will
        // actually see: duplicate/republish detection and price-vs-area
        // comparison, then a combined star rating from everything above.
        for (listing in finalKept) {
            listingEnricher.detectDuplicate(listing)
            listingEnricher.computePriceComparison(listing)
            listing.starRating = listingEnricher.computeStarRating(listing)
        }

        return finalKept
    }

    private fun populateDetectedPhoneNumbers(listings: List<ListingEntity>) {
        for (listing in listings) {
            val numbers = PhoneExtraction.extractPhoneNumbers(listing.title, listing.description)
            listing.detectedPhoneNumbers = if (numbers.isNotEmpty()) numbers.joinToString(",") else null
        }
    }

    private fun applyRangeFilters(
        savedSearch: SavedSearchEntity,
        listings: List<ListingEntity>,
        protectedIds: Set<Long>,
    ) {
        for (listing in listings) {
            if (listing.id in protectedIds) continue // never auto-hidden
            val price = listing.price
            val area = listing.area
            val pricePerMeter = listing.pricePerMeter
            val outOfRange = when {
                savedSearch.minPrice != null && price != null && price < savedSearch.minPrice -> true
                savedSearch.maxPrice != null && price != null && price > savedSearch.maxPrice -> true
                savedSearch.minArea != null && area != null && area < savedSearch.minArea -> true
                savedSearch.maxArea != null && area != null && area > savedSearch.maxArea -> true
                savedSearch.maxPricePerMeter != null && pricePerMeter != null &&
                    pricePerMeter > savedSearch.maxPricePerMeter -> true
                else -> false
            }
            if (outOfRange) listing.isVisible = false
        }
    }

    private suspend fun recordRejection(listing: ListingEntity, reason: String) {
        // Listing may not have a DB id yet if this is its first pass before
        // insertion; the repository re-associates interactions after insert.
        if (listing.id != 0L) {
            listingInteractionDao.insert(
                ListingInteractionEntity(listingId = listing.id, status = "rejected", rejectionReason = reason)
            )
        }
    }
}
