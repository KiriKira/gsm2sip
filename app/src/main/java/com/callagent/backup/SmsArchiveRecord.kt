package com.callagent.backup

/** A single historical SMS. This type deliberately contains no execution or credential fields. */
data class SmsArchiveRecord(
    val id: String,
    val source: String,
    val ownerId: String?,
    val gatewayId: String?,
    val messageId: String?,
    val simId: String?,
    val simLabel: String?,
    val direction: String,
    val from: String?,
    val to: String?,
    val body: String,
    val createdAt: Long,
    val status: String,
    val observedAt: Long,
    val slotIndex: Int? = null,
    val subscriptionId: Int? = null,
    val partCount: Int? = null,
)
