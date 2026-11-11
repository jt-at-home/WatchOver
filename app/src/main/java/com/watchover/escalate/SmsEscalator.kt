package com.watchover.escalate

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.watchover.data.Event
import com.watchover.data.EventLogStore
import com.watchover.data.EventTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SMS is the only escalation channel in v1 (design §11).
 *
 * Sending is fire-and-forget at the ROM level: [send] reports whether the
 * message was handed to the radio, and [SmsDeliveryReceiver] logs the actual
 * result afterwards.
 */
class SmsEscalator(
    private val context: Context,
    private val events: EventLogStore,
) {

    fun canSend(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * @return true when the message was accepted for sending. A false return
     *         means the message definitely did not leave the phone.
     */
    suspend fun send(phoneNumber: String, message: String): Boolean = withContext(Dispatchers.IO) {
        if (!canSend()) return@withContext false
        if (phoneNumber.isBlank()) return@withContext false

        val manager = context.getSystemService(SmsManager::class.java) ?: return@withContext false
        val sentIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_SENT,
            Intent(context, SmsDeliveryReceiver::class.java)
                .setAction(SmsDeliveryReceiver.ACTION_SENT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val deliveryIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_DELIVERED,
            Intent(context, SmsDeliveryReceiver::class.java)
                .setAction(SmsDeliveryReceiver.ACTION_DELIVERED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return@withContext try {
            // Do NOT use SmsManager.divideMessage(): on Android 17 it calls
            // TelephonyManager.getGroupIdLevel1(), which throws SecurityException
            // for apps without READ_PHONE_STATE. Splitting ourselves needs no
            // telephony permission at all.
            val parts = SmsText.split(message)
            if (parts.size > 1) {
                val sentIntents = ArrayList<PendingIntent>(parts.size).apply {
                    repeat(parts.size) { add(sentIntent) }
                }
                val deliveryIntents = ArrayList<PendingIntent>(parts.size).apply {
                    repeat(parts.size) { add(deliveryIntent) }
                }
                manager.sendMultipartTextMessage(
                    phoneNumber,
                    null,
                    ArrayList(parts),
                    sentIntents,
                    deliveryIntents,
                )
            } else {
                manager.sendTextMessage(phoneNumber, null, message, sentIntent, deliveryIntent)
            }
            events.append(Event(System.currentTimeMillis(), EventTypes.ESCALATION_STARTED, "to …${lastFour(phoneNumber)}"), 30)
            true
        } catch (t: Throwable) {
            events.append(
                Event(
                    System.currentTimeMillis(),
                    EventTypes.ESCALATION_FAILED,
                    buildString {
                        append("send threw ${t.javaClass.simpleName}: ${t.message}")
                        t.stackTrace.take(6).forEach { frame ->
                            append(" | ")
                            append(frame.className.substringAfterLast('.'))
                            append('.')
                            append(frame.methodName)
                        }
                    },
                ),
                30,
            )
            false
        }
    }

    private fun lastFour(number: String): String =
        number.filter { it.isDigit() }.takeLast(4)

    companion object {
        private const val REQUEST_SENT = 600
        private const val REQUEST_DELIVERED = 601
    }
}
