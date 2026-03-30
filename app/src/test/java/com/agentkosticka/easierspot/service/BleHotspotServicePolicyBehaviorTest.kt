package com.agentkosticka.easierspot.service

import com.agentkosticka.easierspot.data.model.RememberedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleHotspotServicePolicyBehaviorTest {

    private val service = BleHotspotService()

    @Test
    fun `approved policy with approved flag auto approves`() {
        val remembered = rememberedServer(
            approvalPolicy = RememberedServer.APPROVAL_POLICY_APPROVED,
            isApproved = true
        )

        val decision = service.decideApprovalDecision(remembered)

        assertEquals(BleHotspotService.ApprovalDecision.AUTO_APPROVE, decision)
    }

    @Test
    fun `ask policy always requests manual approval`() {
        val remembered = rememberedServer(
            approvalPolicy = RememberedServer.APPROVAL_POLICY_ASK,
            isApproved = true
        )

        val decision = service.decideApprovalDecision(remembered)

        assertEquals(BleHotspotService.ApprovalDecision.REQUEST_APPROVAL, decision)
    }

    @Test
    fun `denied policy auto denies with no prompt`() {
        val remembered = rememberedServer(
            approvalPolicy = RememberedServer.APPROVAL_POLICY_DENIED,
            isApproved = true
        )

        val decision = service.decideApprovalDecision(remembered)

        assertEquals(BleHotspotService.ApprovalDecision.AUTO_DENY, decision)
    }

    @Test
    fun `approving ask device does not overwrite nickname or policy`() {
        val existing = rememberedServer(
            deviceName = "Known Device",
            approvalPolicy = RememberedServer.APPROVAL_POLICY_ASK,
            isApproved = false,
            nickname = "Desk Tablet"
        )
        val merged = service.mergeApprovalMetadata(
            existing = existing,
            clientAddress = "AA:BB:CC:DD:EE:FF",
            clientName = "Incoming Name",
            approvedAt = 1234L
        )

        assertEquals(existing.approvalPolicy, merged.approvalPolicy)
        assertEquals(existing.nickname, merged.nickname)
        assertEquals(existing.deviceName, merged.deviceName)
        assertTrue(merged.isApproved)
        assertEquals(1234L, merged.lastApprovedAt)
        assertEquals(1234L, merged.lastSeen)
    }

    @Test
    fun `approved policy without approved flag still requests approval`() {
        val remembered = rememberedServer(
            approvalPolicy = RememberedServer.APPROVAL_POLICY_APPROVED,
            isApproved = false
        )

        val decision = service.decideApprovalDecision(remembered)

        assertEquals(BleHotspotService.ApprovalDecision.REQUEST_APPROVAL, decision)
    }

    @Test
    fun `normalize identity strips client prefix repeatedly`() {
        assertEquals("abc123", BleHotspotService.normalizeIdentityForDisplay("client-client_abc123"))
        assertEquals("xy", BleHotspotService.normalizeIdentityForDisplay(" Client-xy "))
    }

    private fun rememberedServer(
        deviceId: String = "dev-1",
        deviceName: String = "Device Name",
        approvalPolicy: String,
        isApproved: Boolean,
        nickname: String? = null
    ): RememberedServer {
        return RememberedServer(
            deviceId = deviceId,
            deviceName = deviceName,
            deviceAddress = "11:22:33:44:55:66",
            lastSeen = 10L,
            isApproved = isApproved,
            nickname = nickname,
            approvalPolicy = approvalPolicy,
            lastApprovedAt = 20L
        )
    }
}
