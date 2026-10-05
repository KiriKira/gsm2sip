package com.callagent.gateway.backup

import android.content.Context
import com.callagent.backup.SmsArchiveRecord
import com.callagent.backup.SmsArchiveRecordProvider
import com.callagent.gateway.data.GatewayDatabase

/** Stateless adapter; the archive worker receives application context, never an Activity. */
object GatewayRecordProvider : SmsArchiveRecordProvider {
    override fun records(context: Context): Sequence<SmsArchiveRecord> =
        GatewayDatabase.get(context.applicationContext).smsArchiveSnapshot()
}
