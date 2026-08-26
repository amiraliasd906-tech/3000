package com.divarsmartsearch.app.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.divarsmartsearch.app.R
import com.divarsmartsearch.app.data.local.entity.ListingEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shows a local notification when a listing passes every filter.
 * There is no server or push service involved — everything runs on
 * this device, so this simply calls Android's own NotificationManager
 * directly from wherever the filter pipeline finishes running.
 */
@Singleton
class LocalNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Bug fix: `soundEnabled` used to be ignored entirely — every
     * notification always used [R.string.new_listing_notification_channel_id],
     * a channel created once at app startup with the system default sound.
     * On Android 8+ a channel's sound is fixed at creation time and a
     * posted notification's own sound settings are ignored in favor of its
     * channel's, so the person's "صدای اعلان" toggle in Settings had no way
     * to actually take effect — it just updated a stored preference no
     * code ever read. Picking between this channel and the pre-created
     * silent twin (see DivarApplication) based on the setting is the
     * correct way to honor it.
     */
    fun notifyNewListing(listing: ListingEntity, soundEnabled: Boolean = true) {
        val priceText = listing.price?.let {
            "${NumberFormat.getNumberInstance(Locale("fa", "IR")).format(it.toLong())} تومان"
        } ?: "قیمت نامشخص"

        val tapIntent = Intent(Intent.ACTION_VIEW, Uri.parse(listing.url))
        val pendingIntent = PendingIntent.getActivity(
            context,
            listing.id.toInt(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val channelId = if (soundEnabled) {
            context.getString(R.string.new_listing_notification_channel_id)
        } else {
            context.getString(R.string.new_listing_silent_channel_id)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("آگهی جدید مطابق فیلتر شما")
            .setContentText("${listing.title} — $priceText")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(listing.id.toInt(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission was denied; nothing more to do.
        }
    }
}
