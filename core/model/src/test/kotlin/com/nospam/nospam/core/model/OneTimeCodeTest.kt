// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The wording is that of real messages from Iranian banks and services and
 * international ones; the codes, amounts and card numbers are made up.
 */
class OneTimeCodeTest {

    private fun assertCode(expected: String, body: String) = assertEquals(body, expected, findOneTimeCode(body))

    private fun assertNoCode(body: String) = assertNull(body, findOneTimeCode(body))

    @Test fun `a keyword before the code on its line`() {
        assertCode("7128", "کد تایید شما در سامانه ثبت تغییر نشانی : 7128")
        assertCode("462158", "کد تاییدیه ورود شما به سامانه احراز هویت 462158")
        assertCode("230240", "کد احراز هویت شما در سامانه مدیریت سهام عدالت  230240  می باشد. لطفا جهت ورود این کد را وارد نمایید.")
        assertCode("24913", "رمز یکبار مصرف جهت ورود به درگاه یکپارچه ذینفعان بازار سرمایه : 24913  شرکت سپرده گذاری مرکزی")
        assertCode("415171", "کد ورود شما در هاست ایران: 415171")
        assertCode("500980", "مشتری گرامی، کد اعتبار سنجی تغییر شماره همراه شما عبارت است از 500980\n بانک ملت")
        assertCode("242645", "کد تایید شماره همراه شما در  عبارت است از:242645 تهران پرداخت")
    }

    @Test fun `a code alone on its line takes the line before`() {
        assertCode("28534", "کد تایید جهت ورود به سامانه سجام\n 28534 \n شرکت سپرده گذاری مرکزی")
        assertCode("415171", "کد بازیابی رمز عبور شما در تهران پیمنت :  \n415171")
        assertCode("18554", "کد تایید پنجره ملی خدمات دولت هوشمند: \n\n 18554 \n این کد مختص شما می‌باشد.")
    }

    @Test fun `a keyword after the code`() {
        assertCode("633964", "633964 کد احراز شماره همراه/ example.ir")
        assertCode("5891", "5891\nکد تایید تترلند\n\nتترلند - سادگی نهایت هوشمندی است")
        assertCode("8726", "کاربر گرامی 8726\nکد تاییدیه شما در میزبان کلود\nexample.com")
        assertCode("75807", "75807 کد فعالسازی شما در سامانه ارتباط با مشتریان شرکت گاز")
    }

    @Test fun `bank dynamic passwords`() {
        assertCode("80462391", "خرید از\nاسنپ فود\nمبلغ 1,250,000\nرمز پویا 80462391")
        assertCode("3302917", "پاسارگاد\nخرید\nایرانسل\nمبلغ:450000\nرمز:3302917\n12:38:38")
        assertCode("6619024", "بانک سامان\nخرید\nدیجی کالا\nمبلغ 2,300,000 ریال\nرمز 6619024\nزمان اعتبار رمز 23:19:42")
        assertCode("55120", "بانک ملی\nمحرمانه\nکارت: 9406\nرمزدوم: 55120\nاعتبارتا: 23:37:00")
        assertCode("771203", "محرمانه\nرمز برداشت وجه از کارت شما: 771203\nاعتبار: 120 ثانیه")
        assertCode("1125447", "هشدار\nمشتری گرامی شما درحال ورود به سامانه بانکداری اینترنتی می باشید. \nتوجه نمایید که عدد محرمانه: 1125447 یکباررمز شما جهت تایید ورود به سامانه بانکداری اینترنتی می باشد.")
    }

    @Test fun `the number that is not the password in a bank message`() {
        // "09990" is a bill's number; the amount has no separators.
        assertCode("4417302", "پرداخت\nپرداخت قبوض 09990\nمبلغ 250000\nرمز پویا 4417302")
        assertCode("9031277", "بلوبانک \nبفرمایید رمز پویا\nپرداخت\n992*2784\nمبلغ: 1,250,000 ریال\nرمز: 9031277\nزمان ارسال رمز: 01:35:18")
    }

    @Test fun `english messages`() {
        assertCode("293637", "G-293637 is your Google verification code.")
        assertCode("831204", "Your LinkedIn verification code is 831204.")
        assertCode("41620", "Telegram code: 41620\n\nYou can also tap on this link to log in:\nhttps://t.me/login/40192")
        assertCode("120443", "<#> Your WhatsApp code: 120-443\n\nYou can also tap on this link to verify your phone: v.whatsapp.com/120443\n\nDon't share this code with others\nAbCdEf12345")
        assertCode("554123", "Microsoft access code: 554123")
        assertCode("903117", "Code: 903117 \nکد ورود شما به اسنپ!\n\njU4m+Zli/TH")
    }

    @Test fun `persian and arabic digits are copied as latin digits`() {
        assertCode("482913", "کد تایید شما: ۴۸۲۹۱۳")
        assertCode("482913", "رمز یکبار مصرف: ٤٨٢٩١٣")
    }

    @Test fun `spelling variants of the keywords`() {
        assertCode("731624", "کد تأیید: 731624")
        assertCode("731624", "کد تائید: 731624\nبرای آغاز تغییر ایمیل")
        assertCode("731624", "كد تاييد شما: 731624")
        assertCode("731624", "کد فعال‌سازی: 731624")
        assertCode("731624", "رمز یک‌بار مصرف: 731624")
        assertCode("731624", "کدتایید 731624")
    }

    @Test fun `a code glued to the next word`() {
        assertCode("482913", "رمز جدید شما 482913میباشد. مرکز آموزش مجازی")
        assertCode("731624", "\nکد فعالسازی ثبت نام 731624باشگاه ستاره جنوب")
    }

    @Test fun `the code below`() {
        assertCode("731624", "*بانکداری دیجیتالی پاسارگاد*\nجهت فعال سازی رمز اول\nکارت به شماره\n6037-99**-****-7890\nکد زیر را ارسال نمایید\n731624\nهمچنین می توانید جهت تایید کد ارسال شده وارد لینک زیر شوید.")
        assertCode("731624", "برای تغییر رمز عبور از کد زیر استفاده کنید 731624.")
    }

    @Test fun `a bare code word counts in a message about a one-time code`() {
        assertCode("731624", "بانک ملی\nکد 731624 را جهت ورود به سامانه بام وارد نمایید.")
        assertCode("731624", "بازار\nکد: 731624\nاین کد را در اختیار فردی دیگر قرار ندهید")
    }

    @Test fun `a bare code word in an offer is a discount code`() {
        assertNoCode("30 هزار تومان تخفیف سفارش اول!\nکد: 731624\nاعتبار: تا 23 آذر\nحداقل سفارش: 50 هزار تومان")
        assertNoCode("سه روز تخفیف تپسی!\nکد 731624 را در قسمت جایزه و تخفیف اپلیکیشن وارد کنید.")
        assertNoCode("کد تخفیف: 731624\nاعتبار تا 3 خرداد")
    }

    @Test fun `a bare code word elsewhere is not a one-time code`() {
        assertNoCode("قیمت1,242,000\n01/1/11-13:50\nقطار381س8ر15ص59\nکد731624")
        assertNoCode("✅نمایندگی منتخب کد731624\n\nلغو11")
    }

    @Test fun `tracking and reference numbers are not codes`() {
        assertNoCode("رسید کارت به کارت\n\nمبلغ : 1,250,000 ریال\nشماره پیگیری : 731624\nبا موفقیت انتقال یافت.")
        assertNoCode("مرسوله شما\nبا کد رهگیری 731624\nتحویل شرکت ملی پست شد.")
        assertNoCode("بانک ملی\nشناسه تایید پرداخت قبض تلفن همراه شما:54750\nمبلغ:120000")
        assertNoCode("شماره قبض: 59689\nکد اشتراک: 731624")
        assertNoCode("کد پستی:97766")
        assertNoCode("از خرید شما متشکریم.\nکد سفارش شما : 731624")
        assertNoCode("Your order 731624 has shipped. Tracking number: 88120044")
        assertNoCode("Use promo code 7316 for 20% off")
    }

    @Test fun `amounts, dates, times, phone numbers and cards are not codes`() {
        assertNoCode("رمز اول کارت شما در ساعت 17:00 اشتباه وارد شد.\nبلولاین:021-87641")
        assertNoCode("تغییر رمز ورود به سامانه همراه بانک ملت توسط شما انجام شد.\n1400/12/07 - 10:59")
        assertNoCode("رمز اول6037-9912-3456-7890\nدر1400/10/01\n14:56:26\nاشتباه وارد شده است")
        assertNoCode("بانک ملی\nشارژ\n 990*1294\nمبلغ 50000")
        assertNoCode("کد تایید: 5000 تومان")
        assertNoCode("رمز عبور شما به شماره 09121234567 ارسال شد")
    }

    @Test fun `ussd codes and links are not codes`() {
        assertNoCode("برای اطلاع از وضعیت بسته، کد\n*100*10#\nرا شماره گیری کنید.")
        assertNoCode("کد تایید را در لینک وارد کنید: https://example.ir/verify/48213")
        assertNoCode("لغو: off-36773")
    }

    @Test fun `a ringtone list is not a list of codes`() {
        assertNoCode("آواهای منتخب\nفعالسازی با ارسال کد آوا به 8989\n18712 زیبای من\n18689 خودت")
    }

    @Test fun `the same code twice is one code`() {
        assertCode("731624", "کد فعال‌سازی: 731624\nبه دیجی‌کالا جت خوش آمدی!\n@example.com #731624")
        assertCode("760183", "code: 760183\n\nکد تایید شماره همراه شما عبارت است از:\n760183")
    }

    @Test fun `two different codes are no telling which`() {
        assertNoCode("کد تایید: 731624\nرمز یکبار مصرف: 482913")
    }

    @Test fun `messages without a code`() {
        assertNoCode("")
        assertNoCode("سلام، فردا ساعت 10 جلسه داریم")
        assertNoCode("Meeting moved to 3pm, room 1204")
        assertNoCode("بانک ملت\nمشتری گرامی، رمز اینترنتی ملت کارت تغییر یافت.\n1400/12/07")
    }
}
