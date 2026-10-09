package me.capcom.smsgateway.modules.gateway

import me.capcom.smsgateway.domain.ProcessingState
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class DeliveryReconciliationTest {
    private fun patch(state: ProcessingState = ProcessingState.Sent) = GatewayApi.MessagePatchRequest(
        "msg_test", state, listOf(GatewayApi.RecipientState("+16045550123", state, null)), mapOf(state to Date(0)))
    private fun remote(state: ProcessingState = ProcessingState.Sent, phone: String = "+16045550123") =
        GatewayApi.MessageStateResponse("msg_test", state, listOf(GatewayApi.RecipientState(phone, state, null)), null, true)

    @Test fun exactOrAdvancedStateConfirmsEveryRecipient() {
        assertTrue(remoteConfirmsPatch(remote(), patch()))
        assertTrue(remoteConfirmsPatch(remote(ProcessingState.Delivered), patch()))
        assertFalse(remoteConfirmsPatch(remote(ProcessingState.Processed), patch()))
        assertFalse(remoteConfirmsPatch(remote(phone = "+16045550124"), patch()))
        assertFalse(remoteConfirmsPatch(remote().copy(id = "other"), patch()))
        assertFalse(remoteConfirmsPatch(remote().copy(recipients = emptyList()), patch()))
    }
    @Test fun failureIsNotInterchangeableWithSuccess() {
        assertFalse(remoteConfirmsPatch(remote(ProcessingState.Failed), patch()))
        assertFalse(remoteConfirmsPatch(remote(), patch(ProcessingState.Failed)))
        assertTrue(remoteConfirmsPatch(remote(ProcessingState.Failed), patch(ProcessingState.Failed)))
    }
    @Test fun missingOrMalformedDeliveryNeverFallsBack() {
        assertFalse(validDeliveryMetadata(null, "a".repeat(64)))
        assertFalse(validDeliveryMetadata("token", null))
        assertFalse(validDeliveryMetadata("token", "A".repeat(64)))
        assertTrue(validDeliveryMetadata("token", "a".repeat(64)))
    }
    @Test fun errorsExposeOnlyBoundedCodes() {
        assertEquals("DELIVERY_EXPIRED", deliveryErrorCode("{\"detail\":{\"code\":\"DELIVERY_EXPIRED\"}}"))
        assertEquals("UNKNOWN", deliveryErrorCode("{\"detail\":\"private content\"}"))
        assertEquals("UNKNOWN", deliveryErrorCode("not-json"))
    }
}
