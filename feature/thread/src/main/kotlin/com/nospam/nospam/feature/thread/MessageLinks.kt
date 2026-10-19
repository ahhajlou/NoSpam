// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import androidx.core.util.PatternsCompat
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

enum class LinkKind { URL, EMAIL, PHONE }

/**
 * [start] inclusive, [end] exclusive, indices into the text given to [findMessageLinks].
 * [target]: URL → the URI to open; EMAIL → the address; PHONE → the number in E.164.
 */
data class MessageLink(val start: Int, val end: Int, val kind: LinkKind, val target: String)

private val PHONE_UTIL: PhoneNumberUtil = PhoneNumberUtil.getInstance()

/** `xxx://` at the start of a match. The pattern also accepts `rtsp`, which is not ours to open. */
private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*)://")

/** A match preceded by one of these is the tail of something else, not a link of its own. */
private const val NOT_A_LINK_START = ".-_@/"

/** Punctuation that ends a sentence around a link, in Latin and in Persian/Arabic script. */
private const val TRAILING_PUNCTUATION = ".,;:!?'\"،؛؟…»"

private const val CLOSING_BRACKETS = ")]}"

/**
 * Real top-level domains that a message far more often means as a file name: `invoice.zip`
 * is linked only as `https://invoice.zip`. No standard names these. They are the four that
 * Google Messages left unlinked when written bare, out of the 18 domains that are also
 * common file extensions, tested on 2026-10-19 (messages.android_20260331_00_RC04).
 */
private val FILE_NAME_ENDINGS = setOf("zip", "mov", "docs", "java")

/**
 * Finds the links in a message body: web addresses, email addresses and phone numbers.
 *
 * Pure on purpose (no `Linkify`, no framework calls), so the rules can be tested on the
 * JVM, and strict where Android's own matching is loose, because a tappable span turns
 * whatever it covers into an action: a link that opens a different place from the one
 * the text shows is worse than a missed link. A web address without "http://" must end in
 * a real top-level domain ([TOP_LEVEL_DOMAINS]), so `file.txt` and `1.5` are not links;
 * Android's public `WEB_URL` pattern accepts any two letters there. Phone numbers must be
 * valid for [region] (not merely possible): the lenient setting linked one-time codes,
 * amounts and dates as Iranian numbers.
 *
 * Overlaps are resolved as Android's `Linkify` does: the earliest start wins, then the
 * longer span, then email over URL over phone. So `support@example.com` is one email,
 * and `b2n.ir/09121234567` is one URL.
 *
 * @param region ISO 3166 alpha-2 country the numbers without a "+" are read in, any case
 * @return the links sorted by [MessageLink.start], never overlapping
 */
fun findMessageLinks(text: String, region: String): List<MessageLink> {
    if (text.isEmpty()) return emptyList()

    val candidates = findEmails(text) + findUrls(text) + findPhones(text, region)
    val ordered = candidates.sortedWith(
        compareBy<MessageLink> { it.start }
            .thenByDescending { it.end }
            .thenBy { overlapRank(it.kind) },
    )

    val kept = ArrayList<MessageLink>(ordered.size)
    var lastEnd = 0
    for (link in ordered) {
        if (link.start < lastEnd) continue
        kept += link
        lastEnd = link.end
    }
    return kept
}

private fun overlapRank(kind: LinkKind): Int = when (kind) {
    LinkKind.EMAIL -> 0
    LinkKind.URL -> 1
    LinkKind.PHONE -> 2
}

/**
 * [text] with every character outside ASCII replaced by a space, at the same indices, for
 * the URL and email patterns. They end a match at `\b`, which on the JDK (since 19) counts
 * only ASCII letters as word characters and on Android (ICU) counts every letter, so on the
 * raw text the tests and the phone disagreed next to Persian: on the phone,
 * `https://example.irلینک` was one link to a host that does not exist. Given only ASCII the
 * two agree. The cost: a host in Persian letters (`بانک.ir`) is not linked, which SMS
 * rarely needs.
 */
private fun asciiOnly(text: String): String =
    buildString(text.length) { for (char in text) append(if (char.code < 0x80) char else ' ') }

private fun findEmails(text: String): List<MessageLink> {
    val links = ArrayList<MessageLink>()
    val matcher = PatternsCompat.EMAIL_ADDRESS.matcher(asciiOnly(text))
    while (matcher.find()) {
        val address = matcher.group()
        if (startsMidWord(text, matcher.start()) || !isTopLevelDomain(address.substringAfterLast('.'))) continue
        links += MessageLink(matcher.start(), matcher.end(), LinkKind.EMAIL, address)
    }
    return links
}

/**
 * True when the match at [start] is the tail of something longer. In "аpple.com/login"
 * with a Cyrillic "а" the pattern sees only "pple.com/login", so a tap would open a
 * different site from the one shown; the same for "раypal.com", whose Cyrillic letters
 * hide in any label. A match that starts after a letter or digit, a dot, hyphen, "@" or
 * "/", is part of something else.
 */
private fun startsMidWord(text: String, start: Int): Boolean {
    if (start == 0) return false
    val before = text.codePointBefore(start)
    return Character.isLetterOrDigit(before) || (before <= 0x7F && NOT_A_LINK_START.indexOf(before.toChar()) >= 0)
}

private fun isTopLevelDomain(label: String): Boolean = label.lowercase(Locale.ROOT) in TOP_LEVEL_DOMAINS

private fun findPhones(text: String, region: String): List<MessageLink> =
    PHONE_UTIL
        .findNumbers(text, region.uppercase(Locale.ROOT), PhoneNumberUtil.Leniency.VALID, Long.MAX_VALUE)
        .map { match ->
            val e164 = PHONE_UTIL.format(match.number(), PhoneNumberUtil.PhoneNumberFormat.E164)
            MessageLink(match.start(), match.end(), LinkKind.PHONE, e164)
        }

private fun findUrls(text: String): List<MessageLink> {
    val links = ArrayList<MessageLink>()
    val matcher = PatternsCompat.WEB_URL.matcher(asciiOnly(text))
    while (matcher.find()) {
        val link = urlLink(text, matcher.start(), matcher.end()) ?: continue
        links += link
    }
    return links
}

/** Turns one raw pattern match into a link, or null when it is not one we should offer. */
private fun urlLink(text: String, start: Int, matchEnd: Int): MessageLink? {
    val matched = text.substring(start, matchEnd)
    val scheme = SCHEME.find(matched)
    val schemeLength = scheme?.value?.length ?: 0

    if (scheme != null) {
        // Only web links. Android matches intent schemes case-sensitively, so the
        // target gets a lowercase one below even when the text shows HTTPS://.
        val name = scheme.groupValues[1].lowercase(Locale.ROOT)
        if (name != "http" && name != "https") return null
    } else {
        if (startsMidWord(text, start)) return null
        // Written with "http://" the sender says it is a link; without, it has to end
        // like one. An IP address ends in digits, so a bare 192.168.1.1 is not.
        val ending = matched.takeWhile { it !in "/?#:" }.substringAfterLast('.').lowercase(Locale.ROOT)
        if (!isTopLevelDomain(ending) || ending in FILE_NAME_ENDINGS) return null
    }

    val end = trimTrailing(text, start, extendIntoPath(text, matched, schemeLength, matchEnd))
    if (end <= start) return null

    val shown = text.substring(start, end)
    val target = if (scheme != null) {
        scheme.groupValues[1].lowercase(Locale.ROOT) + "://" + shown.substring(schemeLength)
    } else {
        // What Android's Linkify does. Chrome upgrades to https by itself.
        "http://$shown"
    }
    return MessageLink(start, end, LinkKind.URL, target)
}

/**
 * The pattern sees no character outside ASCII ([asciiOnly]), so
 * `https://example.com/مسیر/فارسی` matches only `https://example.com`. When the match
 * ends where a path continues (a "/" follows, or it already has a path and a letter
 * follows), the link runs on to the next whitespace; the caller trims what that drags in.
 */
private fun extendIntoPath(text: String, matched: String, schemeLength: Int, matchEnd: Int): Int {
    if (matchEnd >= text.length) return matchEnd
    val next = text.codePointAt(matchEnd)
    val hasPath = matched.indexOf('/', schemeLength) >= 0
    if (next != '/'.code && !(hasPath && Character.isLetter(next))) return matchEnd

    var end = matchEnd
    while (end < text.length) {
        val codePoint = text.codePointAt(end)
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) break
        end += Character.charCount(codePoint)
    }
    return end
}

/**
 * Removes sentence punctuation from the end of `text[start, end)`, and a closing bracket
 * only when the link has more of that kind than openers: `(https://example.org/x)` loses
 * its ")", `https://en.wikipedia.org/wiki/Foo_(bar)` keeps it. Returns the new end.
 */
private fun trimTrailing(text: String, start: Int, end: Int): Int {
    var trimmed = end
    while (trimmed > start) {
        val last = text[trimmed - 1]
        val drop = last in TRAILING_PUNCTUATION ||
            (last in CLOSING_BRACKETS && hasUnmatchedCloser(text, start, trimmed, last))
        if (!drop) break
        trimmed--
    }
    return trimmed
}

private fun hasUnmatchedCloser(text: String, start: Int, end: Int, closer: Char): Boolean {
    val opener = when (closer) {
        ')' -> '('
        ']' -> '['
        else -> '{'
    }
    var closers = 0
    var openers = 0
    for (index in start until end) {
        if (text[index] == closer) closers++ else if (text[index] == opener) openers++
    }
    return closers > openers
}
