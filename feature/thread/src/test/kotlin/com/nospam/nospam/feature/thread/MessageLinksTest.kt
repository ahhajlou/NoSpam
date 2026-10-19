// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageLinksTest {

    /** A link as the user sees it: the text it covers, what it is and where it goes. */
    private data class Found(val shown: String, val kind: LinkKind, val target: String)

    private fun url(shown: String, target: String) = Found(shown, LinkKind.URL, target)
    private fun email(address: String) = Found(address, LinkKind.EMAIL, address)
    private fun phone(shown: String, e164: String) = Found(shown, LinkKind.PHONE, e164)

    /** Asserts the exact links found, in order, and that nothing else is. */
    private fun assertLinks(text: String, vararg expected: Found, region: String = "IR") {
        val links = findMessageLinks(text, region)
        val found = links.map { Found(text.substring(it.start, it.end), it.kind, it.target) }
        assertEquals("links in \"$text\" ($region)", expected.toList(), found)
        links.zipWithNext { a, b -> assertTrue("sorted and apart: $a, $b", a.end <= b.start) }
    }

    private fun assertNoLinks(text: String, region: String = "IR") =
        assertLinks(text, region = region)

    // URLs

    @Test fun `a bare domain with a path is a link and opens over http`() {
        assertLinks(
            "Bare: example.com/abc and b2n.ir/xyz",
            url("example.com/abc", "http://example.com/abc"),
            url("b2n.ir/xyz", "http://b2n.ir/xyz"),
        )
    }

    @Test fun `www alone is enough`() {
        assertLinks("www.google.com", url("www.google.com", "http://www.google.com"))
    }

    @Test fun `an uppercase scheme is lowercased in the target but not in the text`() {
        assertLinks("HTTPS://Example.com/A", url("HTTPS://Example.com/A", "https://Example.com/A"))
    }

    @Test fun `sentence punctuation and brackets around a url are not part of it`() {
        assertLinks(
            "see https://example.com/page. Or (https://example.org/x),",
            url("https://example.com/page", "https://example.com/page"),
            url("https://example.org/x", "https://example.org/x"),
        )
    }

    @Test fun `a closing bracket that matches an opener inside the url stays`() {
        val text = "read https://en.wikipedia.org/wiki/Foo_(bar)."
        assertLinks(
            text,
            url(
                "https://en.wikipedia.org/wiki/Foo_(bar)",
                "https://en.wikipedia.org/wiki/Foo_(bar)",
            ),
        )
    }

    @Test fun `a full stop after a link in a persian message is not part of it`() {
        assertLinks("ببینید b2n.ir/xyz.", url("b2n.ir/xyz", "http://b2n.ir/xyz"))
    }

    @Test fun `a persian comma after a link is not part of it`() {
        assertLinks("ببینید example.com/abc، سپس", url("example.com/abc", "http://example.com/abc"))
    }

    @Test fun `persian text glued to a domain does not become part of the link`() {
        assertLinks(
            "لینک https://example.irلینک بدون فاصله",
            url("https://example.ir", "https://example.ir"),
        )
    }

    @Test fun `a persian path is followed to the next space`() {
        assertLinks(
            "آدرس https://example.com/مسیر/فارسی را باز کنید",
            url("https://example.com/مسیر/فارسی", "https://example.com/مسیر/فارسی"),
        )
    }

    @Test fun `a persian path ending in a persian comma loses the comma`() {
        assertLinks(
            "https://fa.wikipedia.org/wiki/ایران،",
            url("https://fa.wikipedia.org/wiki/ایران", "https://fa.wikipedia.org/wiki/ایران"),
        )
    }

    @Test fun `a match that starts after a cyrillic look-alike letter is not offered`() {
        // The pattern sees only "pple.com/login", which is not the site the text shows.
        assertNoLinks("Lookalike: аpple.com/login")
    }

    @Test fun `a host that mixes cyrillic and latin letters in one label is not offered`() {
        assertNoLinks("Pay at раypal.com now")
    }

    @Test fun `a host in persian letters is not linked`() {
        // The price of matching the same on the JVM and on the phone (asciiOnly).
        assertNoLinks("سایت بانک.ir/x")
        assertNoLinks("بانک.ir/x")
    }

    @Test fun `only http and https are offered`() {
        assertNoLinks("ftp://example.com/f and intent://scan#Intent;end and rtsp://example.com/s")
    }

    @Test fun `file names and version numbers are not domains`() {
        assertNoLinks("Not links: file.txt v2.3.1 1.5 a.b report.pdf photo.jpg app.apk")
    }

    // What Google Messages does with each of these, tested on 2026-10-19, is the expectation.

    @Test fun `a bare domain with a real ending is a link, in any case`() {
        assertLinks("خرید از digikala.com با تخفیف", url("digikala.com", "http://digikala.com"))
        assertLinks("snapp.ir", url("snapp.ir", "http://snapp.ir"))
        assertLinks("EXAMPLE.IR", url("EXAMPLE.IR", "http://EXAMPLE.IR"))
    }

    @Test fun `country endings that look like file extensions are still domains`() {
        assertLinks(
            "see README.md, main.py",
            url("README.md", "http://README.md"),
            url("main.py", "http://main.py"),
        )
    }

    @Test fun `zip, mov, docs and java endings are links only with a scheme`() {
        assertNoLinks("open invoice.zip, video.mov, notes.docs or Main.java")
        assertNoLinks("www.video.mov and invoice.zip/x")
        assertLinks(
            "https://invoice.zip and https://notes.docs",
            url("https://invoice.zip", "https://invoice.zip"),
            url("https://notes.docs", "https://notes.docs"),
        )
    }

    @Test fun `an ip address is a link only with a scheme`() {
        assertNoLinks("router at 192.168.1.1")
        assertLinks("http://192.168.1.1/admin", url("http://192.168.1.1/admin", "http://192.168.1.1/admin"))
    }

    @Test fun `the domain list is the full root zone`() {
        assertTrue(TOP_LEVEL_DOMAINS.size > 1000)
        assertTrue(TOP_LEVEL_DOMAINS.containsAll(listOf("com", "ir", "shop", "xn--mgba3a4f16a")))
        assertTrue("txt" !in TOP_LEVEL_DOMAINS && "pdf" !in TOP_LEVEL_DOMAINS)
    }

    // Email

    @Test fun `an email is one link and its domain is not also a url`() {
        assertLinks(
            "email support@example.com، و test@example.ir.",
            email("support@example.com"),
            email("test@example.ir"),
        )
    }

    @Test fun `an email needs a real ending and a whole local part`() {
        assertNoLinks("write to me@example.notreal")
        // A Cyrillic "а" first: the pattern would see only "lice@example.com".
        assertNoLinks("write to аlice@example.com")
    }

    // Phone numbers

    @Test fun `numbers valid in the region or written with a plus are phone numbers`() {
        assertLinks(
            "call +1 650-253-0000 or 09121234567 or 0912 123 4567",
            phone("+1 650-253-0000", "+16502530000"),
            phone("09121234567", "+989121234567"),
            phone("0912 123 4567", "+989121234567"),
        )
    }

    @Test fun `persian digits are a phone number too`() {
        assertLinks(
            "شماره ۰۹۱۲۱۲۳۴۵۶۷",
            phone("۰۹۱۲۱۲۳۴۵۶۷", "+989121234567"),
        )
    }

    @Test fun `a landline with an area code is a phone number`() {
        assertLinks("دفتر 021-88776655", phone("021-88776655", "+982188776655"))
    }

    @Test fun `a one-time code is not a phone number`() {
        assertNoLinks("Your verification code is 482913")
    }

    @Test fun `a persian one-time code is not a phone number`() {
        assertNoLinks("کد تایید: ۱۲۳۴۵۶")
    }

    @Test fun `an address with a zip code is not a phone number`() {
        assertNoLinks("Mountain View, CA 94043")
    }

    @Test fun `amounts are not phone numbers`() {
        assertNoLinks("مبلغ 2,500,000 ریال مانده: 12500000")
    }

    @Test fun `dates and times are not phone numbers`() {
        assertNoLinks("date 1403/07/28 time 10:30 and 2026-10-19")
    }

    @Test fun `a card number is not a phone number`() {
        assertNoLinks("card 6037-9911-2345-6789")
    }

    @Test fun `a flight number is not a phone number`() {
        assertNoLinks("flight UA 1234")
    }

    @Test fun `an iranian service number is not linked`() {
        // Accepted: it is not a valid number to libphonenumber, and POSSIBLE matching
        // would link codes and amounts too.
        assertNoLinks("service 5000301630")
    }

    // Region

    @Test fun `a national number is read in the given region only`() {
        assertNoLinks("09121234567", region = "US")
    }

    @Test fun `a number with a plus is read the same in any region`() {
        assertLinks(
            "+98 912 123 4567",
            phone("+98 912 123 4567", "+989121234567"),
            region = "US",
        )
    }

    @Test fun `the region may be lowercase`() {
        assertLinks("09121234567", phone("09121234567", "+989121234567"), region = "ir")
    }

    // Overlaps and order

    @Test fun `digits inside a url path are part of the url, not a phone number`() {
        assertLinks("b2n.ir/09121234567", url("b2n.ir/09121234567", "http://b2n.ir/09121234567"))
    }

    @Test fun `empty text has no links`() {
        assertEquals(emptyList<MessageLink>(), findMessageLinks("", "IR"))
    }

    @Test fun `all three kinds in one message come back in text order`() {
        val text = "ببینید b2n.ir/xyz یا support@example.com یا 09121234567 و www.google.com"
        assertLinks(
            text,
            url("b2n.ir/xyz", "http://b2n.ir/xyz"),
            email("support@example.com"),
            phone("09121234567", "+989121234567"),
            url("www.google.com", "http://www.google.com"),
        )
    }
}
