# One-time codes

How NoSpam finds the one-time code in a message, and how to teach it a sender
it misses. Changing what counts as a code is an edit to a few lists of words,
plus a test.

## Where the code is offered

| Place | What the user sees | Code |
|---|---|---|
| The message's notification | A "⧉ 482913" chip above "Mark as read" and "Reply" ("Copy 482913" on Android 8 and 9) | `NotificationHelper.copyCodeAction` (core:notifications), copied by `MessageNotificationReceiver` (`:app`) |
| The conversation | A "Copy 482913" chip under the received message | `ThreadViewModel.startCodes`, `MessageBubble` (feature:thread) |

Both copy the code in Latin digits, marked sensitive, so Android 13+ hides it
in the clipboard preview. The notification keeps its place after a copy.

Samsung's **Brief** pop-up style (Settings → Notifications → Notification
pop-up style) is one line with no buttons, so the chip shows only once the
notification is expanded. With **Detailed** the pop-up is the notification
itself. An app cannot change either.

Google Messages posts no copy action of its own: the chip on its notification
comes from Android's notification assistant (Android System Intelligence on a
Pixel), which recognizes English codes in any app's notification and misses
Persian ones. A NoSpam notification carrying our chip turns those system chips
off (`setAllowSystemGeneratedContextualActions(false)`), or an English code
showed twice.

## Where the rules are

Everything is in one file, which has no Android code and is tested on the JVM:

- Rules: [`core/model/src/main/kotlin/com/nospam/nospam/core/model/OneTimeCode.kt`](../core/model/src/main/kotlin/com/nospam/nospam/core/model/OneTimeCode.kt)
- Tests: [`core/model/src/test/kotlin/com/nospam/nospam/core/model/OneTimeCodeTest.kt`](../core/model/src/test/kotlin/com/nospam/nospam/core/model/OneTimeCodeTest.kt)

The entry point is `findOneTimeCode(body)`, which returns the code or `null`.

## How a code is found

1. **Clean the text.** Persian and Arabic digits become Latin; Arabic ي and ك
   become Persian ی and ک; أ and إ become ا, and ئ becomes ی (so تأیید and
   تائید read as تایید); English becomes lower case; half-spaces (ZWNJ) and
   other spacing become plain spaces; kashida, diacritics and direction marks
   are dropped. Line breaks stay, because a line is a field.
2. **Find candidates** (`CANDIDATE`): a run of 4 to 8 digits, or two groups of
   3 ("123 456", "123-456"), optionally after one letter and a dash ("G-482913").
3. **Skip what is part of something larger** (`standsAlone`, `UNIT`): dates and
   times (`1402/05/12`, `12:30`), amounts (`1,250,000`, or a number followed
   by ریال, تومان, %, …), masked cards (`6037****1234`), USSD codes (`*100#`),
   numbers in links, and references like `off-36773`.
4. **Ask what names the number.** Its *field* is the text before it on its
   line, back to the line's start, the end of a sentence or another number of
   4+ digits. If that field has no letters (the number is alone on its line),
   the line above is used.
   - The **nearest keyword before** the number decides (`HEADS`). A Persian
     label reads head first, so in "رمز برداشت وجه از کارت شما: 123456" the
     nearest keyword is رمز and the number is a code, while in "کد پیگیری" the
     keyword is پیگیری and it is not.
   - With no keyword, a field name such as مبلغ, کارت or پیگیری
     (`NOT_CODE_FIELDS`) rules the number out.
   - With neither, the **first keyword after** the number decides, on its line
     or the next ("482913 is your code", "5891 ⏎ کد تایید").
5. **Bare "کد"** ("کد: 1234", "کد زیر") also names discount, ticket and order
   codes, so it counts only when the message has a word of a one-time code
   (`OTP_CUES`) and none of an offer (`OFFER_WORDS`).
6. **One answer.** The same code twice is one code; two different codes give
   `null`, since there is no telling which one the user wants.

## The lists you can edit

| List | Line (2026-10-23) | Meaning | Add to it when |
|---|---|---|---|
| `HEADS`, group 2 | `OneTimeCode.kt:63` | Keywords that name a code: کد تایید, کد ورود, رمز, عدد محرمانه, code, otp, password, … | A sender's code is **missed** |
| `HEADS`, group 1 | same | Keywords that name something else: شماره, شناسه, order, tracking, promo code, … | A number is **wrongly offered** and has a label in front of it |
| `HEADS`, group 3 | same | Bare "کد" phrases: کد زیر, کد ذیل | A phrase like "the code below" is missed |
| `NOT_CODE_FIELDS` | `OneTimeCode.kt:79` | Field names that rule a number out: مبلغ, کارت, پیگیری, سفارش, تاریخ, … | A tracking number, amount or date field is offered |
| `OTP_CUES` | `OneTimeCode.kt:88` | Words that let a bare "کد: 1234" count: ورود, تایید, ندهید, نفرستید, … | A sender writes only "کد: 1234" and is missed |
| `OFFER_WORDS` | `OneTimeCode.kt:91` | Words of an offer; a bare "کد" never counts with one: تخفیف, هدیه, جایزه, … | A discount code is offered |
| `UNIT` | `OneTimeCode.kt:94` | Units after a number: ریال, تومان, درصد, دقیقه, … | An amount, duration or quantity is offered |
| `CANDIDATE` | `OneTimeCode.kt:48` | What a code looks like | Codes of another length or shape |

Line numbers drift; search the file for the name.

### Writing a keyword

Write it in the cleaned form of step 1, or it never matches:

- Persian ی and ک, never the Arabic forms; ا, never أ or إ; تایید, never
  تأیید or تائید.
- No half-space inside a keyword. Where a space may or may not be written, use
  `[ ]*`: `یک[ ]*بار`, `فعال[ ]*سازی`.
- English in lower case.
- No Persian digits are needed anywhere.
- Never `\b` or `\w`: on the JVM they see only ASCII letters, on Android every
  letter, so a test could pass on a match the phone never makes (CLAUDE.md §9).
  Persian keywords start with `$P` (`(?<!\p{L})`, no letter right before);
  English ones are wrapped in `$E_START` … `$E_END`.
- Inside a group, put a longer phrase before a shorter one that starts the
  same way: the alternatives are tried in order.

## Adding a sender: a worked example

A sender writes "کد شناسایی شما: 482913" and no chip appears.

1. Add the message to `OneTimeCodeTest.kt`, with made-up digits if you like,
   and run it to see it fail:

   ```kotlin
   @Test fun `an identification code`() {
       assertCode("482913", "کد شناسایی شما: 482913")
   }
   ```

   ```bash
   ./gradlew :core:model:test --tests '*OneTimeCodeTest*'
   ```

2. Add the keyword to `HEADS`, group 2, after `کد[ ]*(?:`:

   ```kotlin
   "${P}کد[ ]*(?:تایید|ورود|فعال[ ]*سازی|احراز|...|otp|شناسایی)" +
   ```

3. Run the tests again. All of them must pass: the file holds every message
   the rules were tuned on (from persian-sms-spam's `sms_labeled.jsonl`,
   8,779 messages, 2026-10-23), so a keyword that also appears in tracking or
   discount messages fails one of the "not a code" tests.

For a number wrongly offered, do the same with `assertNoCode(message)` and add
the label in front of the number to `HEADS` group 1 or `NOT_CODE_FIELDS`.

Keep real codes, names, card and account numbers out of the tests: change the
digits and drop the names, and keep the wording, which is what the rules read.

## Known limits

- Codes with letters (`27A4BA0F42`) are not offered, except "G-482913": in the
  training messages nearly all such codes were discount codes.
- A message with no keyword at all ("برای تکمیل استرداد 9415 را وارد کنید") is
  not offered.
- A code shorter than 4 or longer than 8 digits is not offered.
