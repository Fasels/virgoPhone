package me.capcom.smsgateway.modules.gateway

import com.google.gson.GsonBuilder
import me.capcom.smsgateway.extensions.configure
import me.capcom.smsgateway.modules.localserver.domain.SimCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimMetadataSyncRequestTest {
    private val gson = GsonBuilder().configure().create()
    private fun card(slot: Int, number: String? = "+12025550123", iccid: String? = "same-card") =
        SimCard(slot, slot + 1, number, "Example Carrier", iccid)

    @Test fun metadataUsesExistingDeviceAndExplicitlyOptsIn() {
        val request = buildSimMetadataSyncRequest("existing-device", listOf(card(0)))!!
        assertEquals("existing-device", request.id)
        assertTrue(request.syncPhoneNumbers)
        val json = gson.toJson(request)
        assertTrue(json.contains("\"syncPhoneNumbers\":true"))
        assertFalse(json.contains("pushToken"))
    }

    @Test fun olderPatchDefaultsToNoNumberReplacement() {
        assertFalse(GatewayApi.DevicePatchRequest("existing-device", null, null).syncPhoneNumbers)
    }

    @Test fun unreadableOrEmptySnapshotsNeverBecomeSimRemoval() {
        assertNull(buildSimMetadataSyncRequest("existing-device", null))
        assertNull(buildSimMetadataSyncRequest("existing-device", emptyList()))
        assertNull(buildSimMetadataSyncRequest("", listOf(card(0))))
    }

    @Test fun invalidOrDuplicateSlotsAreRejected() {
        assertNull(buildSimMetadataSyncRequest("device", listOf(card(-1))))
        assertNull(buildSimMetadataSyncRequest("device", listOf(card(0), card(0))))
        assertNull(buildSimMetadataSyncRequest("device", listOf(card(0).copy(simNumber = 2))))
    }

    @Test fun dualSimOrderUsesSlotInsteadOfListPosition() {
        val request = buildSimMetadataSyncRequest("device",
            listOf(card(1, "+12025550125"), card(0, "+12025550124")))!!
        val bySlot = request.simCards!!.associateBy { it.slotIndex }
        assertEquals("+12025550124", bySlot[0]!!.phoneNumber)
        assertEquals(1, bySlot[0]!!.simNumber)
        assertEquals("+12025550125", bySlot[1]!!.phoneNumber)
        assertEquals(2, bySlot[1]!!.simNumber)
    }

    @Test fun sameIccidNumberChangeIsIncludedOnEveryFreshSnapshot() {
        val old = buildSimMetadataSyncRequest("device", listOf(card(0, "+12025550124")))!!
        val current = buildSimMetadataSyncRequest("device", listOf(card(0, "+12025550125")))!!
        assertEquals(old.simCards!!.single().iccid, current.simCards!!.single().iccid)
        assertEquals("+12025550125", current.simCards!!.single().phoneNumber)
    }

    @Test fun unknownNumberOrIccidDoesNotDropTheExistingSlot() {
        val request = buildSimMetadataSyncRequest("device",
            listOf(card(0, number = null, iccid = null)))!!
        assertEquals(0, request.simCards!!.single().slotIndex)
        assertNull(request.simCards!!.single().phoneNumber)
        assertNull(request.simCards!!.single().iccid)
    }

    @Test fun requestContainsNoOwnershipOrHistoryFields() {
        val json = gson.toJsonTree(buildSimMetadataSyncRequest("device", listOf(card(0))))
        val keys = json.asJsonObject.entrySet().map { it.key }.toSet()
        assertEquals(setOf("id", "simCards", "syncPhoneNumbers"), keys)
        val simKeys = json.asJsonObject["simCards"].asJsonArray[0].asJsonObject.entrySet().map { it.key }.toSet()
        assertEquals(setOf("slotIndex", "simNumber", "phoneNumber", "carrierName", "iccid"), simKeys)
    }
}
