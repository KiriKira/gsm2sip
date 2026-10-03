package com.callagent.gateway.sms

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsDeliveryClassifierTest {
    @Test fun statusBoundariesFollowTpStatusRanges() {
        assertEquals(SmsDeliveryClassifier.Disposition.UNKNOWN, SmsDeliveryClassifier.classify(null))
        assertEquals(SmsDeliveryClassifier.Disposition.UNKNOWN, SmsDeliveryClassifier.classify(-1))
        assertEquals(SmsDeliveryClassifier.Disposition.DELIVERED, SmsDeliveryClassifier.classify(0))
        assertEquals(SmsDeliveryClassifier.Disposition.DELIVERED, SmsDeliveryClassifier.classify(31))
        assertEquals(SmsDeliveryClassifier.Disposition.TEMPORARY_FAILURE, SmsDeliveryClassifier.classify(32))
        assertEquals(SmsDeliveryClassifier.Disposition.TEMPORARY_FAILURE, SmsDeliveryClassifier.classify(63))
        assertEquals(SmsDeliveryClassifier.Disposition.PERMANENT_FAILURE, SmsDeliveryClassifier.classify(64))
        assertEquals(SmsDeliveryClassifier.Disposition.PERMANENT_FAILURE, SmsDeliveryClassifier.classify(127))
        assertEquals(SmsDeliveryClassifier.Disposition.UNKNOWN, SmsDeliveryClassifier.classify(128))
        assertEquals(SmsDeliveryClassifier.Disposition.UNKNOWN, SmsDeliveryClassifier.classify(255))
    }
}
