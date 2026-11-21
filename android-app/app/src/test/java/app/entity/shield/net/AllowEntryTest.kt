package app.entity.shield.net

import app.entity.shield.core.SmsTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AllowEntryTest {
    @Test fun ownSiteAllowsWholeDomain() {
        assertEquals("kkjj.me", DnsPolicy.entryFor("kkjj.me"))
        assertEquals("flipkart.net", DnsPolicy.entryFor("connekt.flipkart.net"))
        assertEquals("example.co.in", DnsPolicy.entryFor("www.example.co.in"))
    }

    @Test fun sharedHostingAllowsOnlyThatSite() {
        assertEquals("sbi-kyc.web.app", DnsPolicy.entryFor("sbi-kyc.web.app"))
        assertEquals("x.blogspot.com", DnsPolicy.entryFor("x.blogspot.com"))
        assertEquals("myapp.app.link", DnsPolicy.entryFor("myapp.app.link"))
        assertNull(DnsPolicy.entryFor("blogspot.com"))
    }

    @Test fun shortenersNever() {
        assertNull(DnsPolicy.entryFor("bit.ly"))
        assertNull(DnsPolicy.entryFor("tinyurl.com"))
    }

    @Test fun shortMessagesFromPhonesMatchExactly() {
        assertEquals("PHONE", SmsTrust.kindFor("+919876543210", "your parcel is held pay link:x.com"))
        assertNotEquals("PHONE", SmsTrust.kindFor("+919876543210", "hi"))
        assertNotEquals(SmsTrust.kindFor("+919876543210", "hi"), SmsTrust.kindFor("+919812345678", "hi"))
        assertEquals("JIOPAY", SmsTrust.kindFor("JT-JIOPAY-S", "ok"))
    }
}
