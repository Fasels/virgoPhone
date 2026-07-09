package me.capcom.smsgateway.modules.webhooks

import me.capcom.smsgateway.modules.webhooks.domain.WebHookEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebHooksServiceTest {
    @Test
    fun supportedEventsExcludeMmsWebhooks() {
        assertTrue(WebHooksService.isSupportedEvent(WebHookEvent.SmsReceived))
        assertTrue(WebHooksService.isSupportedEvent(WebHookEvent.SmsDataReceived))
        assertFalse(WebHooksService.isSupportedEvent(WebHookEvent.MmsReceived))
        assertFalse(WebHooksService.isSupportedEvent(WebHookEvent.MmsDownloaded))
    }
}
