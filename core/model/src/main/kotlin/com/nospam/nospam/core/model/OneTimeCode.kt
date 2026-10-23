// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.model

/**
 * The one-time code in a received message, for a "Copy code" action, in Latin
 * digits whatever digits it was sent in; null when there is none, or more than
 * one and no telling which.
 *
 * A code is a run of 4 to 8 digits (or two groups of 3, "123 456" and
 * "123-456", copied without the separator) that a keyword names. The keyword
 * is the nearest "head" before the number on its line ("کد تایید", "رمز",
 * "code", "OTP"), or the first one after it ("123456 is your code", "5891 ⏎
 * کد تایید"). A number alone on its line takes the line before it. A head can
 * also name what is not a code ("شماره", "شناسه", "order", "promo code"), and
 * without any head, a field name such as "مبلغ", "کارت" or "پیگیری" rules the
 * number out: a Persian label reads head first, so "رمز برداشت از کارت شما"
 * is a password, while "کد پیگیری" is a tracking number. Numbers that are part
 * of something larger are skipped: dates, times, amounts with separators or a
 * unit, card numbers with stars, USSD codes and links.
 *
 * The patterns use no `\b` or `\w`, which see only ASCII letters on the JVM
 * and every letter on Android (CLAUDE.md §9); `\p{L}` means the same on both.
 */
fun findOneTimeCode(body: String): String? {
    val text = normalizeForCodes(body)
    var found: String? = null
    for (match in CANDIDATE.findAll(text)) {
        val start = match.range.first
        val end = match.range.last + 1
        if (!standsAlone(text, start, end) || unitFollows(text, end)) continue
        if (!namedAsCode(text, start, end)) continue
        val code = match.groupValues[1].filter { it in '0'..'9' }
        if (found != null && found != code) return null
        found = code
    }
    return found
}

/**
 * What a field says of its number. [BARE_CODE] is "کد" alone ("کد: 1234",
 * "کد زیر"), which names order, ticket and discount codes as well, so it
 * counts only in a message that reads like a one-time code ([OTP_CUES]) and
 * not like an offer.
 */
private enum class Verdict { CODE, BARE_CODE, NOT_CODE }

private val CANDIDATE = Regex("(?<![0-9a-z])(?:[a-z]-)?([0-9]{3}[ -][0-9]{3}|[0-9]{4,8})(?![0-9a-z])")

/** A Persian keyword starts a word; it may run into the next one ("رمزدوم"). */
private const val P = "(?<!\\p{L})"

/** An English keyword is a whole word. */
private const val E_START = "(?<![a-z])"
private const val E_END = "(?![a-z])"

/**
 * Heads, words that say what the number after them is. Group 1 is a head that
 * names something else, group 2 a code, group 3 a bare "کد". Alternatives are
 * tried in order at each position, so "promo code" is taken whole before
 * "code" alone.
 */
private val HEADS = Regex(
    "(" +
        "$P(?:شماره|شناسه)(?![ ]*(?:همراه|موبایل|تلفن|تماس))" +
        "|$E_START(?:promo|discount|coupon|voucher|gift|postal|zip|tracking|order|reference|referral|booking|invoice)[ -]?(?:code|number|no)$E_END" +
        "|$E_START(?:order|tracking|reference|ref|invoice|account|balance|amount|card|ticket|booking|flight|seat)$E_END" +
        ")|(" +
        "${P}کد[ ]*(?:تایید|ورود|فعال[ ]*سازی|احراز|یک[ ]*بار|امنیتی|اعتبار[ ]*سنجی|بازیابی|تغییر[ ]*رمز|دسترسی|عبور|محرمانه|otp)" +
        "|${P}رمز(?![ ]*(?:ارز|نگار|ینه))" +
        "|${P}یک[ ]*بار[ ]*رمز|${P}عدد[ ]*محرمانه|${P}کلمه[ ]*(?:ی[ ]*)?عبور|${P}گذرواژه|${P}پسورد" +
        "|$E_START(?:code|otp|pin|passcode|password|verification|verify|one[ -]?time|login|log[ -]in)$E_END" +
        ")|(" +
        "${P}کد[ ]*(?:زیر|ذیل)" +
        ")",
)

/** Field names that, with no head around, say a number is not a code. */
private val NOT_CODE_FIELDS = Regex(
    "$P(?:پیگیری|پیگری|رهگیری|مرجع|سفارش|مرسوله|قبض|قبوض|کارت|حساب|شبا|ترمینال|نوبت|ردیف|ملی(?!\\p{L})|پستی|" +
        "اشتراک|بورسی|رزرو|تخفیف|هدیه|دستوری|آوا(?!\\p{L})|قرعه|مبلغ|مانده|موجودی|کارمزد|تاریخ|سهمیه|بدهکار|کنتور|بلیط)",
)

/** "کد" with nothing between it and the number: "کد: 1234". */
private val BARE_CODE_BEFORE = Regex("${P}کد[ ]*[:=]?[ ]*$")

/** Words around a one-time code: what it is for, or not to pass it on. */
private val OTP_CUES = Regex("ورود|تایید|احراز|یک[ ]*بار|فعال[ ]*سازی|رمز|اختیار|ندهید|نگذارید|نفرستید|share|verif|login")

/** Words of an offer, whose codes are discounts, not one-time codes. */
private val OFFER_WORDS = Regex("تخفیف|هدیه|جایزه|قرعه|باشگاه|صندوقدار|discount|promo|coupon|voucher")

/** A number followed by one of these is an amount, a duration or a quantity. */
private val UNIT = Regex(
    "[ ]*(?:ریال|تومان|درصد|دقیقه|ثانیه|ساعت|روز|سهم|گیگ|مگ|امتیاز|%|٪" +
        "|(?:ت|rial|irr|toman|usd|min|mins|minutes|sec|seconds|days?|mb|gb)(?![a-z\\p{L}]))",
).toPattern()

/** A digit run this long splits a line into fields, as a line break does. */
private val FIELD_NUMBER = Regex("[0-9]{4,}")

/**
 * Not glued to something larger: a date or time (`1402/05/12`, `12:30`), an
 * amount (`1,250,000`), a masked card (`6037****1234`), a USSD code (`*1#`), a
 * link (`t.me/login/40192`) or a reference (`off-36773`).
 */
private fun standsAlone(text: String, start: Int, end: Int): Boolean {
    val before = text.getOrNull(start - 1)
    val beforeThat = text.getOrNull(start - 2)
    val after = text.getOrNull(end)
    val afterThat = text.getOrNull(end + 1)
    if (before != null && before in "/#*@_+=&\\$€") return false
    if (before == ':' && beforeThat.isDigit()) return false
    if ((before == '-' || before == '.') && (beforeThat.isAsciiLetter() || beforeThat.isDigit())) return false
    if ((before == ',' || before == ' ') && beforeThat.isDigit()) return false
    if (after != null && after in "/#*@_%٪+") return false
    if (after != null && after in "-.,: " && afterThat.isDigit()) return false
    return true
}

private fun unitFollows(text: String, end: Int): Boolean = UNIT.matcher(text).region(end, text.length).lookingAt()

private fun namedAsCode(text: String, start: Int, end: Int): Boolean {
    var before = fieldBefore(text, start)
    if (before.none { it.isLetter() }) before = previousLineField(text, start)
    when (decideBefore(before)) {
        Verdict.CODE -> return true
        Verdict.BARE_CODE -> return OTP_CUES.containsMatchIn(text) && !OFFER_WORDS.containsMatchIn(text)
        Verdict.NOT_CODE -> return false
        null -> Unit
    }
    var after = fieldAfter(text, end)
    if (after.none { it.isLetter() }) after = nextLineField(text, end)
    return decideAfter(after) == Verdict.CODE
}

/** The nearest head before the number decides; else a field name rules it out. */
private fun decideBefore(field: String): Verdict? {
    if (BARE_CODE_BEFORE.containsMatchIn(field)) return Verdict.BARE_CODE
    HEADS.findAll(field).lastOrNull()?.let { return it.verdict() }
    return if (NOT_CODE_FIELDS.containsMatchIn(field)) Verdict.NOT_CODE else null
}

/**
 * The first head after the number decides; else a field name rules it out.
 * A bare "کد" after a number does not name it.
 */
private fun decideAfter(field: String): Verdict? {
    HEADS.find(field)?.let { return it.verdict() }
    return if (NOT_CODE_FIELDS.containsMatchIn(field)) Verdict.NOT_CODE else null
}

private fun MatchResult.verdict(): Verdict = when {
    groups[1] != null -> Verdict.NOT_CODE
    groups[2] != null -> Verdict.CODE
    else -> Verdict.BARE_CODE
}

/** What precedes the number in its field: back to the line's start, a sentence's end or another number. */
private fun fieldBefore(text: String, start: Int): String {
    val lineStart = text.lastIndexOf('\n', start - 1) + 1
    var field = text.substring(lineStart, start)
    FIELD_NUMBER.findAll(field).lastOrNull()?.let { field = field.substring(it.range.last + 1) }
    val sentenceEnd = lastSentenceEnd(field)
    return if (sentenceEnd >= 0) field.substring(sentenceEnd + 1) else field
}

/** What follows the number in its field. */
private fun fieldAfter(text: String, end: Int): String {
    val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
    var field = text.substring(end, lineEnd)
    FIELD_NUMBER.find(field)?.let { field = field.substring(0, it.range.first) }
    val sentenceEnd = firstSentenceEnd(field)
    return if (sentenceEnd >= 0) field.substring(0, sentenceEnd) else field
}

/** The last field of the nearest non-blank line before the number's. */
private fun previousLineField(text: String, start: Int): String {
    var lineStart = text.lastIndexOf('\n', start - 1)
    while (lineStart > 0) {
        val previousStart = text.lastIndexOf('\n', lineStart - 1) + 1
        val line = text.substring(previousStart, lineStart)
        if (line.isNotBlank()) return fieldBefore(text, lineStart)
        lineStart = previousStart - 1
    }
    return ""
}

/** The first field of the nearest non-blank line after the number's. */
private fun nextLineField(text: String, end: Int): String {
    var lineEnd = text.indexOf('\n', end)
    while (lineEnd in 0 until text.length - 1) {
        val nextEnd = text.indexOf('\n', lineEnd + 1).let { if (it < 0) text.length else it }
        val line = text.substring(lineEnd + 1, nextEnd)
        if (line.isNotBlank()) return fieldAfter(text, lineEnd + 1)
        lineEnd = nextEnd
    }
    return ""
}

private const val SENTENCE_ENDS = ".!?؟؛;"

private fun lastSentenceEnd(field: String): Int =
    (field.length - 2 downTo 0).firstOrNull { field[it] in SENTENCE_ENDS && field[it + 1] == ' ' } ?: -1

private fun firstSentenceEnd(field: String): Int =
    field.indices.firstOrNull { field[it] in SENTENCE_ENDS && (it == field.length - 1 || field[it + 1] == ' ') } ?: -1

private fun Char?.isDigit(): Boolean = this != null && this in '0'..'9'

private fun Char?.isAsciiLetter(): Boolean = this != null && this in 'a'..'z'

/**
 * One form for what is written several ways: Persian and Arabic digits as
 * Latin, Arabic yeh and kaf as Persian, hamza forms of alef and yeh as plain
 * ones ("تأیید", "تائید"), ASCII lower case; spacing characters as spaces and
 * invisible ones (kashida, diacritics, direction marks) dropped. Line breaks
 * stay, because a line is a field.
 */
private fun normalizeForCodes(body: String): String {
    val out = StringBuilder(body.length)
    for (c in body) {
        when (c) {
            in '۰'..'۹' -> out.append('0' + (c - '۰'))
            in '٠'..'٩' -> out.append('0' + (c - '٠'))
            '٬' -> out.append(',')
            '٫' -> out.append('.')
            'ي', 'ئ' -> out.append('ی')
            'ك' -> out.append('ک')
            'أ', 'إ' -> out.append('ا')
            in 'A'..'Z' -> out.append(c + ('a' - 'A'))
            '\r' -> out.append('\n')
            '\t', ' ', ' ', ' ', '‌' -> out.append(' ')
            'ـ', 'ٰ', in 'ً'..'ْ', '​', '‍', '‎', '‏', '﻿',
            in '‪'..'‮', in '⁦'..'⁩' -> Unit
            else -> out.append(c)
        }
    }
    return out.toString()
}
