package com.divarsmartsearch.app.data.repository

import com.divarsmartsearch.app.data.filters.FilterPipeline
import com.divarsmartsearch.app.data.filters.PhoneFilter
import com.divarsmartsearch.app.data.local.dao.AppSettingsDao
import com.divarsmartsearch.app.data.local.dao.ListingDao
import com.divarsmartsearch.app.data.local.dao.ListingInteractionDao
import com.divarsmartsearch.app.data.local.dao.RemovedListingDao
import com.divarsmartsearch.app.data.local.dao.SavedSearchDao
import com.divarsmartsearch.app.data.local.entity.ListingInteractionEntity
import com.divarsmartsearch.app.data.local.entity.RemovedListingEntity
import com.divarsmartsearch.app.data.local.toDomain
import com.divarsmartsearch.app.data.webview.HeadlessDivarScanner
import com.divarsmartsearch.app.domain.model.HistoryTab
import com.divarsmartsearch.app.domain.model.Listing
import com.divarsmartsearch.app.domain.model.SellerReport
import com.divarsmartsearch.app.domain.repository.ListingRepository
import com.divarsmartsearch.app.util.AppResult
import com.divarsmartsearch.app.util.safeCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ListingRepositoryImpl @Inject constructor(
    private val listingDao: ListingDao,
    private val interactionDao: ListingInteractionDao,
    private val savedSearchDao: SavedSearchDao,
    private val appSettingsDao: AppSettingsDao,
    private val filterPipeline: FilterPipeline,
    private val removedListingDao: RemovedListingDao,
    private val headlessDivarScanner: HeadlessDivarScanner,
) : ListingRepository {

    override suspend fun getVisibleListings(searchId: Int?): AppResult<List<Listing>> = safeCall {
        listingDao.observeVisible(searchId?.toLong()).first().map { it.toDomain() }
    }

    override fun observeVisibleListings(searchId: Int?): Flow<List<Listing>> =
        listingDao.observeVisible(searchId?.toLong()).map { entities -> entities.map { it.toDomain() } }

    override fun observeVisibleListingsCount(searchId: Int?): Flow<Int> =
        listingDao.observeVisibleCount(searchId?.toLong())

    override suspend fun getHistory(tab: HistoryTab): AppResult<List<Listing>> = safeCall {
        val status = when (tab) {
            HistoryTab.SEEN -> "seen"
            HistoryTab.SAVED -> "saved"
            HistoryTab.REJECTED -> "rejected"
        }
        listingDao.observeByInteractionStatus(status).first().map { it.toDomain() }
    }

    override fun observeHistoryCount(tab: HistoryTab): Flow<Int> {
        val status = when (tab) {
            HistoryTab.SEEN -> "seen"
            HistoryTab.SAVED -> "saved"
            HistoryTab.REJECTED -> "rejected"
        }
        return listingDao.observeInteractionStatusCount(status)
    }

    override suspend fun markSeen(listingId: Int): AppResult<Unit> = safeCall {
        interactionDao.insert(ListingInteractionEntity(listingId = listingId.toLong(), status = "seen"))
    }

    override suspend fun saveListing(listingId: Int): AppResult<Unit> = safeCall {
        interactionDao.insert(ListingInteractionEntity(listingId = listingId.toLong(), status = "saved"))
        // Without this, the listing's `isVisible` flag never changes, so
        // ResultsViewModel's live observeVisibleListings() Flow (see
        // ListingDao.observeVisible) keeps re-emitting it on every
        // collection and silently undoes the screen's optimistic removal —
        // the button looked like it did nothing. `userDecided = true`
        // additionally makes this permanent: no later background scan is
        // allowed to touch isVisible on this listing again (see
        // ListingIngestionService.ingest()), so a saved listing can never
        // silently reappear in the live results.
        val listing = listingDao.getById(listingId.toLong())
        if (listing != null) {
            listingDao.update(listing.copy(isVisible = false, userDecided = true))
        }
    }

    override suspend fun rejectListing(listingId: Int): AppResult<Unit> = safeCall {
        val listing = listingDao.getById(listingId.toLong()) ?: return@safeCall

        // Bug fix, per explicit user request: the same real ad can be
        // discovered independently by more than one saved search (two
        // searches with overlapping results), each getting its own row.
        // This used to only touch the ONE row the person was looking at —
        // any other already-existing row for the same divarToken (under a
        // different saved search) stayed fully visible, so a "rejected
        // forever, never comes back" ad could still show up under another
        // search. removedListingDao only ever blocked FUTURE re-inserts,
        // never anything already sitting in the table. Now every row
        // sharing this ad's divarToken is rejected together.
        val allCopies = listingDao.getAllByToken(listing.divarToken)
        for (copy in allCopies) {
            interactionDao.insert(
                ListingInteractionEntity(
                    listingId = copy.id,
                    status = "rejected",
                    rejectionReason = if (copy.id == listing.id) "user_rejected" else "user_rejected_elsewhere",
                )
            )
            listingDao.update(copy.copy(isVisible = false, userDecided = true))
        }

        // Per explicit user request: a rejected ad must never come back,
        // no matter how many times it gets re-scraped — not on the next
        // cycle, not under a different/recreated saved search. Recording
        // its divarToken here (independent of any of the `listings` rows
        // above) is what ListingIngestionService.ingest() checks before it
        // will ever insert a "new" listing again — see RemovedListingEntity.
        removedListingDao.insert(RemovedListingEntity(divarToken = listing.divarToken))
    }

    override suspend fun restoreListing(listingId: Int): AppResult<Unit> = safeCall {
        val listing = listingDao.getById(listingId.toLong()) ?: return@safeCall

        // Symmetric with the reject-propagation fix above: undoing a
        // reject un-hides every row of this same ad too, not just the one
        // the person restored from.
        val allCopies = listingDao.getAllByToken(listing.divarToken)
        for (copy in allCopies) {
            interactionDao.insert(ListingInteractionEntity(listingId = copy.id, status = "seen"))
            listingDao.update(copy.copy(isVisible = true, userDecided = false))
        }
        removedListingDao.delete(listing.divarToken)
    }

    override suspend fun reapplyFilters(withDetailRefetch: Boolean): AppResult<Int> = safeCall {
        val visible = listingDao.observeVisible(null).first()
        if (visible.isEmpty()) return@safeCall 0

        // Bug fix (regression from the previous fix): this refetch pass
        // used to run unconditionally, sequentially, for EVERY visible
        // listing missing hasDetailDescription, with no cap — on an
        // account with dozens of such listings that's dozens of headless
        // WebView page loads back to back (each with its own up-to-25s
        // timeout), which made a single "add a keyword filter" or even a
        // plain pull-to-refresh (see ResultsViewModel.refresh(), which
        // calls this same function) take minutes and crash. Fixed by:
        //   1. Making the refetch entirely OPT-IN (withDetailRefetch) — a
        //      plain refresh (the common case, run every time the person
        //      opens Results) goes back to only re-checking already-stored
        //      text, exactly like before this feature existed.
        //   2. Capping how many listings get refetched in any single call,
        //      even when opted in, so this can never again take an
        //      unbounded amount of time. Anything past the cap keeps
        //      whatever text it already has for this pass; it'll get
        //      picked up by a later reapply or by the normal background
        //      scan's own retry loop (see ListingIngestionService.ingest()).
        //   3. A shorter per-item timeout than the ingestion-time fetch,
        //      since this runs synchronously in front of the person
        //      instead of quietly in the background.
        if (withDetailRefetch) {
            val needsRefetch = visible.filter { !it.hasDetailDescription }.take(MAX_REFETCH_PER_REAPPLY)
            for (listing in needsRefetch) {
                val detail = headlessDivarScanner.fetchDetail(listing.url, overallTimeoutMs = REAPPLY_DETAIL_TIMEOUT_MS)
                // Same reliability gate as ListingIngestionService.ingest() —
                // see the comment there for why a merely non-blank result
                // isn't enough on its own.
                if (!detail?.description.isNullOrBlank() &&
                    (detail?.description?.length ?: 0) >= MIN_RELIABLE_DESCRIPTION_LENGTH
                ) {
                    listing.description = detail?.description
                    listing.hasDetailDescription = true
                } else {
                    listing.detailFetchAttempts += 1
                }
                if (!detail?.contactPhone.isNullOrBlank()) {
                    PhoneFilter.normalizePhone(detail?.contactPhone ?: "").ifBlank { null }?.let {
                        listing.contactPhone = it
                    }
                }
                // Same pacing as the ingestion-time detail fetch loop —
                // avoid firing many detail-page requests back-to-back,
                // which looks like a scraping burst to Divar.
                if (listing !== needsRefetch.last()) delay(DETAIL_FETCH_SPACING_MS)
            }
        }

        val settings = appSettingsDao.get()
        // Per explicit user request, a manual refresh re-checks every
        // currently visible listing against the filters as they exist right
        // now with NO protection — pass no alreadyVisibleIds, so the
        // never-auto-hide guarantee (which exists for the background
        // scanner) does not apply here. Anything that no longer passes
        // (range, blocked phone, keyword exclude, or agency probability)
        // gets isVisible = false and a "rejected" interaction row (see
        // FilterPipeline.recordRejection), so it shows up in the Rejected
        // history tab exactly like a manual reject.

        // FilterPipeline.apply() works one saved-search at a time (range
        // filters like min/max price come from that search), so listings
        // are regrouped by savedSearchId before each pass.
        var hiddenCount = 0
        for ((savedSearchId, group) in visible.groupBy { it.savedSearchId }) {
            val savedSearch = savedSearchDao.getById(savedSearchId) ?: continue
            val kept = filterPipeline.apply(
                savedSearch = savedSearch,
                listings = group,
                anthropicApiKey = settings?.anthropicApiKey,
                anthropicModel = settings?.anthropicModel ?: "claude-haiku-4-5-20251001",
            )
            // Anything in `group` that didn't survive into `kept` just got
            // isVisible = false during this pass (see FilterPipeline.apply).
            hiddenCount += group.size - kept.size
            for (listing in group) listingDao.update(listing)
        }
        hiddenCount
    }

    override suspend fun getSellerReport(phoneNumber: String): AppResult<SellerReport> = safeCall {
        val normalized = PhoneFilter.normalizePhone(phoneNumber)
        val entities = listingDao.getListingsForPhone(normalized)
        val listings = entities.map { it.toDomain() }

        SellerReport(
            phoneNumber = normalized,
            totalListings = listings.size,
            cities = listings.mapNotNull { it.city }.distinct(),
            neighborhoods = listings.mapNotNull { it.neighborhood }.distinct(),
            listings = listings,
        )
    }

    private companion object {
        const val DETAIL_FETCH_SPACING_MS = 600L
        // See reapplyFilters(withDetailRefetch) for the full reasoning.
        const val MAX_REFETCH_PER_REAPPLY = 12
        const val REAPPLY_DETAIL_TIMEOUT_MS = 10_000L
        // See ListingIngestionService.MIN_RELIABLE_DESCRIPTION_LENGTH — kept
        // in sync with that one; both gate the same field.
        const val MIN_RELIABLE_DESCRIPTION_LENGTH = 60
    }
}
