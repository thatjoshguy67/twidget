package com.tjg.twidget.providers

import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class FxTwitterIdentityTest {
    @Test fun knownAccountsUseTheirPermanentIdEvenAfterAHandleChange() {
        val url = URI(FxTwitterClient.profileUrl("old_handle", "123456"))
        assertEquals("api.fxtwitter.com", url.host)
        assertEquals("/2/profile/id:123456", URLDecoder.decode(url.rawPath, "UTF-8"))
        assertEquals("id:123456", FxTwitterClient.identity("renamed", "123456"))
    }
    @Test fun newAccountsUseTheCurrentProfileEndpoint() {
        assertEquals("https://api.fxtwitter.com/2/profile/example", FxTwitterClient.profileUrl("@example"))
        assertEquals("example", FxTwitterClient.identity("@example", "invalid"))
    }
    @Test fun timelinesUseTheSameIdWithoutTheFailingSinceFilter() {
        val url = URI(FxTwitterClient.timelineUrl("old_handle", "123", 100, "cursor|value", true))
        assertEquals("/2/profile/id:123/statuses", URLDecoder.decode(url.rawPath, "UTF-8"))
        assertEquals("count=100&with_replies=true&cursor=cursor%7Cvalue", url.rawQuery)
        assertFalse(url.rawQuery.contains("since"))
    }
}
