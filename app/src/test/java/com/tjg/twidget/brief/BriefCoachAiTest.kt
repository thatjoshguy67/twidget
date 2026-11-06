package com.tjg.twidget.brief

import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleThreadItem
import com.tjg.twidget.schedule.ScheduledPost
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BriefCoachAiTest {
    private val now = 1_800_000_000_000L
    private val strings = TestBriefStrings()
    private val text = "Finally shipped this. Thoughts?"
    private val report = BriefCoachReport(listOf("Recorded 1000 views and 10 public interactions."),
        listOf("Canned factor never sent to AI"), listOf("Canned suggestion never sent to AI"), listOf("Causes are unknown."))
    private fun input() = BriefCoachInput("draft", text, true, 0, report, emptyList(), now, "en-US")
    private fun response(quote: String = "Finally shipped this.", ids: List<String> = listOf("subject", "e0"),
        rewrite: String = "I shipped [what you built] to help [who] [do what].", needsDetails: Boolean = true): String =
        JSONObject().put("possibleExplanation", "A new reader may not know what was shipped.")
            .put("suggestions", JSONArray().put(JSONObject().put("action", "Describe what you shipped and who it helps.")
                .put("reason", "The wording does not name it. Your counts alone cannot establish whether that affected response.")
                .put("quote", quote).put("rewrite", rewrite).put("needsDetails", needsDetails)
                .put("evidenceIds", JSONArray(ids))))
            .put("question", "What did you ship, and who is it for?").toString()

    @Test fun `reviews remove em dashes from all authored fields but keep exact evidence`() {
        val subject = input().copy(text = "Finally shipped this\u2014thoughts?", language = "fr-FR")
        val packet = subject.packet()
        val raw = JSONObject(response(quote = subject.text, rewrite = "I shipped this\u2014here is what changed.", needsDetails = false))
            .put("possibleExplanation", "Votre texte est court\u2014le contexte manque peut-être.")
            .put("question", "Quel outil\u2014pour qui ?")
        raw.getJSONArray("suggestions").getJSONObject(0)
            .put("action", "Nommez votre outil\u2014puis son utilité.")
            .put("reason", "Votre texte est bref\u2014cela reste une hypothèse.")
        val feedback = BriefCoachAi.parse(raw.toString(), packet)!!
        val suggestion = feedback.suggestions.single()
        assertFalse(listOf(feedback.possibleExplanation, feedback.question!!, suggestion.action,
            suggestion.reason).any { '\u2014' in it })
        assertNull(suggestion.rewrite)
        assertEquals(subject.text, suggestion.quote)
        assertEquals(subject.text, packet.subject)
        val cached = feedback.copy(possibleExplanation = "Saved copy\u2014still readable.",
            suggestions = listOf(suggestion.copy(rewrite = "I shipped this\u2014here is what changed.")))
        val displayed = BriefReviewCopy.clean(cached, subject, TestBriefStrings(Locale.FRENCH))
        assertEquals("Saved copy, still readable.", displayed.possibleExplanation)
        assertNull(displayed.suggestions.single().rewrite)
        assertEquals(subject.text, displayed.suggestions.single().quote)
        listOf(BriefCoachAi.prompt(subject, packet), BriefCoachAi.textPrompt(subject, packet)).forEach {
            assertTrue(it.contains(BriefVoicePolicy.PUNCTUATION_INSTRUCTION))
        }
        val plain = """
            EXPLANATION: Your opening is brief\u2014context may help.
            PASSAGE: q0
            ACTION: Name the tool\u2014then its purpose.
            REASON: Your wording is brief\u2014this is a hypothesis.
            REWRITE: I shipped this\u2014here is what changed.
            DETAILS: no
            SOURCES: subject, e0
            QUESTION: Which tool\u2014for whom?
        """.trimIndent().replace("\\u2014", "\u2014")
        val local = BriefCoachAi.parseText(plain, packet)!!
        assertFalse(local.possibleExplanation.contains('\u2014'))
        assertNull(local.suggestions.single().rewrite)
        assertEquals(subject.text, local.suggestions.single().quote)
    }

    @Test fun `AI owns the advice while measurements and exact citations remain app owned`() {
        val packet = input().packet()
        val prompt = BriefCoachAi.prompt(input(), packet)
        assertFalse(prompt.contains("Canned suggestion"))
        assertFalse(prompt.contains("Canned factor"))
        assertFalse("A no-history prompt must not teach the model to cite a nonexistent history post", prompt.contains("\"p0\""))
        assertTrue(prompt.contains(text))
        val feedback = BriefCoachAi.parse(response(), packet)!!
        assertEquals(report.evidence.single(), feedback.suggestions.single().citations.last().text)
        assertEquals("Finally shipped this.", feedback.suggestions.single().quote)
        assertFalse(feedback.suggestions.single().needsDetails)
        val byPassage = JSONObject(response())
        byPassage.getJSONArray("suggestions").getJSONObject(0).apply { remove("quote"); put("quoteId", "q0") }
        assertEquals("Finally shipped this.", BriefCoachAi.parse(byPassage.toString(), packet)!!.suggestions.single().quote)
        byPassage.getJSONArray("suggestions").getJSONObject(0).put("quoteId", "q99")
        assertNull(BriefCoachAi.parse(byPassage.toString(), packet))
        val observations = report.copy(evidence = report.evidence + "Fixed wording check", measurements = report.evidence)
        assertFalse(BriefCoachAi.prompt(input(), input().copy(report = observations).packet()).contains("Fixed wording check"))
    }

    @Test fun `fabricated quotes citations and malformed output never become feedback`() {
        val packet = input().packet()
        assertNull(BriefCoachAi.parse(response(quote = "Twidget shipped a new feature"), packet))
        assertNull(BriefCoachAi.parse(response(ids = listOf("p99")), packet))
        assertNull(BriefCoachAi.parse(response(ids = emptyList()), packet))
        assertNull(BriefCoachAi.parse(response(quote = ""), packet))
        assertNotNull(BriefCoachAi.parse("```json\n${response()}\n```", packet))
        assertNull(BriefCoachAi.parse("```json\n${response().dropLast(10)}\n```", packet))
        assertNull(BriefCoachAi.parse(response().dropLast(10), packet))
        assertNotNull(BriefCoachAi.parse(response().replace("\"needsDetails\":true", "\"needsDetails\":\"true\""), packet))
    }

    @Test fun `valid citations cannot legitimise invented figures in factual narration`() = runBlocking {
        val packet = input().packet(cloud = true)
        for (claim in listOf("You received 9,999 views.", "Your response rate was 50%.", "You received -1000 views.")) {
            val invalid = JSONObject(response()).put("possibleExplanation", claim).toString()
            var reason = ""
            assertNull(BriefCoachAi.parse(invalid, packet) { reason = it })
            assertEquals("numeric_evidence", reason)
        }
        val badReason = JSONObject(response()).apply {
            getJSONArray("suggestions").getJSONObject(0).put("reason", "Your post received 9999 views.")
        }.toString()
        assertNull(BriefCoachAi.parse(badReason, packet))
        val supported = JSONObject(response()).put("possibleExplanation", "{{observation:e0}}")
        assertNotNull(BriefCoachAi.parse(supported.toString(), packet))
        // Counts of proposed experiments are advice, not claims about measured performance.
        supported.getJSONArray("suggestions").getJSONObject(0).put("action", "Try 3 different openings.")
        assertNotNull(BriefCoachAi.parse(supported.toString(), packet))
        var calls = 0
        val generated = BriefCoachAiRunner.generateWith(input(), cloud = true) { prompt, _ ->
            calls++
            if (calls == 1) badReason else {
                assertTrue(prompt.contains("A number in your explanation or reason was not supported"))
                supported.toString()
            }
        }
        assertTrue(generated is BriefCoachGeneration.Success)
        assertEquals(2, calls)
        for ((language, measured, narration) in listOf(
            Triple("fr-FR", "1\u202f200 vues et 3,5 %", "Vous avez reçu 1 200 vues, avec un taux de 3,50 %."),
            Triple("de-DE", "1.200 Aufrufe und 3,5 %", "Du hast 1.200 Aufrufe und eine Rate von 3,50 %."))) {
            val subject = input().copy(language = language, report = report.copy(measurements = listOf(measured)))
            val raw = JSONObject(response()).put("possibleExplanation", narration)
            assertNull(BriefCoachAi.parse(raw.toString(), subject.packet(cloud = true)))
            raw.put("possibleExplanation", "{{observation:e0}}")
            val parsed = BriefCoachAi.parse(raw.toString(), subject.packet(cloud = true))!!
            assertEquals(measured, BriefReviewCopy.clean(parsed, subject, TestBriefStrings(Locale.forLanguageTag(language))).possibleExplanation)
        }
    }

    @Test fun `review comments use the app language while rewrites keep the selected entry language`() = runBlocking {
        val cases = listOf(
            Triple("fr", text, "I shipped a tool for comparing tweets. Which comparison would help you most?"),
            Triple("en", "Enfin disponible. Votre avis ?", "Mon outil de comparaison de tweets est disponible. Quelle comparaison vous aiderait le plus ?"),
        )
        for ((language, original, rewrite) in cases) {
            val subject = input().copy(language = language, text = original)
            val packet = subject.packet(cloud = true)
            for (prompt in listOf(BriefCoachAi.prompt(subject, packet), BriefCoachAi.textPrompt(subject, packet))) {
                assertTrue(prompt.contains("app language: $language"))
                assertTrue(prompt.contains("Keep quoted passages verbatim"))
                assertTrue(prompt.contains("Do not generate replacement tweets or rewrites"))
            }
            val raw = JSONObject(response(quote = packet.passages.values.first(), rewrite = rewrite, needsDetails = false))
                .put("possibleExplanation", if (language == "fr") "Votre introduction pourrait donner plus de contexte." else "Your introduction could give more context.")
            val result = BriefCoachAiRunner.generateWith(subject, cloud = true) { _, _ -> raw.toString() } as BriefCoachGeneration.Success
            assertNull(result.feedback.suggestions.single().rewrite)
            assertEquals(packet.passages.values.first(), result.feedback.suggestions.single().quote)
            assertEquals(raw.getString("possibleExplanation"), result.feedback.possibleExplanation)
        }
    }

    @Test fun `missing context rewrites must be explicitly labelled fill in drafts`() {
        assertNotNull(BriefCoachAi.parse(response(needsDetails = false), input().packet()))
        val excerpt = input().copy(complete = false).packet()
        assertNull(BriefCoachAi.parse(response(), excerpt)!!.suggestions.single().rewrite)
        assertNotNull(BriefCoachAi.parse(response(rewrite = ""), excerpt))
        val long = input().copy(text = text + " More context.".repeat(400)).packet()
        assertFalse(long.textComplete)
        assertNull(BriefCoachAi.parse(response(), long)!!.suggestions.single().rewrite)
    }

    @Test fun `an optional rewrite cannot discard valid excerpt feedback or bypass evidence validation`() = runBlocking {
        val subject = input().copy(kind = "published", text = "Pixel Tablet > Apple HomeView", complete = false)
        val raw = response(quote = subject.text, rewrite = "I prefer the Pixel Tablet for [reason].")
        var calls = 0
        val result = BriefCoachAiRunner.generateWith(subject, cloud = true) { prompt, _ ->
            calls++
            assertFalse(prompt.contains("\"rewrite\":"))
            raw
        } as BriefCoachGeneration.Success
        assertEquals(1, calls)
        assertNull(result.feedback.suggestions.single().rewrite)
        assertEquals("Describe what you shipped and who it helps.", result.feedback.suggestions.single().action)
        assertFalse(result.completeText)
        assertNull(BriefCoachAi.parse(response(quote = subject.text, ids = listOf("p99")), subject.packet()))
        assertNull(BriefCoachAi.parse(response(quote = "Invented text"), subject.packet()))
    }

    @Test fun `Nano plain text keeps multiline rewrites and verifies every selected source`() {
        val raw = """
            EXPLANATION: The opening may need context for a new reader.
            PASSAGE: q0
            ACTION: Name what you shipped and who it helps.
            REASON: This is based on your wording, not a demonstrated performance effect.
            REWRITE: I shipped [what you built].

            It helps [who] [do what].
            DETAILS: yes
            SOURCES: subject, e0
            QUESTION: What did you ship?
        """.trimIndent()
        val parsed = BriefCoachAi.parseText(raw, input().packet())!!
        assertEquals("Finally shipped this.", parsed.suggestions.single().quote)
        assertNull(parsed.suggestions.single().rewrite)
        assertFalse(parsed.suggestions.single().needsDetails)
        val echo = BriefCoachAi.parseText(raw + "\nDATA: {\"incomplete echoed input\":", input().packet())!!
        assertEquals("What did you ship?", echo.question)
        assertNotNull(BriefCoachAi.parseText(raw.replace("ACTION:", "**Action:**"), input().packet()))
        assertNull(BriefCoachAi.parseText(raw.replace("subject, e0", "p99"), input().packet()))
        assertNull(BriefCoachAi.parseText(raw.replace("subject, e0", "subject, p99"), input().packet()))
        assertNull(BriefCoachAi.parseText(raw.replace("PASSAGE: q0", "PASSAGE: q99"), input().packet()))
        assertNotNull(BriefCoachAi.parseText(raw.replace("PASSAGE: q0", "PASSAGE: Finally shipped this. Thoughts?")
            .replace("subject, e0", "q0, e0"), input().packet()))
        assertNull(BriefCoachAi.parseText(raw + "\nACTION: A conflicting action", input().packet()))
        val prompt = BriefCoachAi.textPrompt(input(), input().packet())
        assertFalse(prompt.contains("Return only this JSON object"))
        assertTrue(prompt.contains("labelled plain text"))
    }

    @Test fun `all thread text supplies context without inventing a product from older posts`() {
        val draft = ScheduledPost(provider = ScheduleProvider.LOCAL_REMINDER, accountUsername = "example", scheduledAt = null,
            thread = listOf(ScheduleThreadItem(text = text), ScheduleThreadItem(text = "Twidget now compares posts at similar ages.")))
        val subject = BriefCoachAi.draft(draft, null, report, strings, now)
        assertTrue(subject.packet().subject.contains("Twidget now compares"))
        assertEquals("Twidget now compares posts at similar ages.", subject.packet().data.getJSONArray("threadEntries")
            .getJSONObject(1).getString("text"))
        assertTrue(BriefCoachAi.prompt(subject, subject.packet()).contains("Preserve the author's intent and facts"))
    }

    @Test fun `history is own mature comparable data chosen by relevance not winning counts`() {
        val target = post("subject", "Android widgets and post feedback", 1000, 10)
        val quiet = post("quiet", "Android widgets now show post feedback", 1000, 0)
        val loud = post("loud", "Unrelated football news", 1_000_000, 100_000)
        val foreign = quiet.copy(url = "https://x.com/other/status/foreign", authorUserName = "other")
        val old = quiet.copy(url = "https://x.com/example/status/old", timestamp = now - 300 * 3_600_000L)
        val reply = quiet.copy(url = "https://x.com/example/status/reply", isReply = true)
        val early = quiet.copy(url = "https://x.com/example/status/early", timestamp = now - 2 * 3_600_000L)
        val snapshot = analytics(listOf(target, quiet, loud, foreign, old, early), listOf(reply))
        val subject = BriefCoachAi.review(target, snapshot, report, strings, now)
        assertEquals(listOf(quiet, loud), subject.history)
        assertEquals(0, subject.history.first().likes.toInt())
        assertTrue(BriefCoachAi.review(target, snapshot.copy(cachedAt = now - 3 * 86_400_000L), report, strings, now).history.isEmpty())
        assertTrue(BriefCoachAi.review(target, snapshot.copy(userName = "other"), report, strings, now).history.isEmpty())
    }

    @Test fun `reply history excludes originals and keeps quiet replies`() {
        val reply = post("reply", "Android widgets", 1000, 10).copy(isReply = true)
        val quiet = reply.copy(url = "https://x.com/example/status/quiet", likes = 0)
        val subject = BriefCoachAi.review(reply, analytics(listOf(post("original", "Android widgets", 1000, 100)), listOf(reply, quiet)), report, strings, now)
        assertEquals(listOf(quiet), subject.history)
    }

    @Test fun `media can answer missing context but only attached images are marked visible`() {
        val media = listOf(BriefCoachMediaRef("content://fixture/image", true, false, 2, "Twidget comparison screen"),
            BriefCoachMediaRef("https://example.com/video.jpg", false, true, 3))
        val subject = input().copy(mediaCount = 2, media = media)
        val packet = subject.packet(attachedMediaIds = setOf("m0"))
        val items = packet.data.getJSONArray("media")
        assertTrue(items.getJSONObject(0).getBoolean("imageAttached"))
        assertEquals(2, items.getJSONObject(0).getInt("entry"))
        assertFalse(items.getJSONObject(1).getBoolean("imageAttached"))
        assertTrue(items.getJSONObject(1).getString("representation").contains("no motion or audio"))
        assertNotNull(BriefCoachAi.parse(response(ids = listOf("m0")), packet))
        assertTrue(BriefCoachAi.prompt(subject, packet).contains("Inspect attached images before judging missing context"))
        assertFalse(packet.data.toString().contains("content://"))
        assertFalse(packet.data.toString().contains("https://example.com/video"))
        val visual = subject.copy(visualContext = mapOf("m0" to "Twidget comparison screen", "m1" to "Unseen clip"))
            .packet(attachedMediaIds = setOf("m0"))
        val context = visual.data.getJSONArray("visualContext")
        assertEquals(1, context.length())
        assertTrue(context.getJSONObject(0).getString("origin").contains("not a verified fact"))
    }

    @Test fun `compact retry changes the available context and validates against that exact packet`() = runBlocking {
        var calls = 0
        val result = BriefCoachAiRunner.generateWith(input(), false) { _, _ ->
            calls++
            if (calls == 1) response(ids = listOf("invented")) else response()
        }
        assertEquals(2, calls)
        assertTrue(result is BriefCoachGeneration.Success)
        assertEquals(BriefCoachGeneration.Unavailable, BriefCoachAiRunner.generateWith(input(), false) { _, _ -> "invalid" })
    }

    @Test fun `cancellation is not swallowed or retried`() = runBlocking {
        var calls = 0
        try {
            BriefCoachAiRunner.generateWith(input(), false) { _, _ -> calls++; throw CancellationException() }
            fail("Cancellation should propagate")
        } catch (_: CancellationException) { assertEquals(1, calls) }
    }



    @Test fun `auto may fall back to cloud while local only never calls it`() = runBlocking {
        assertEquals(listOf(BriefNanoModelMode.STABLE_FULL, BriefNanoModelMode.STABLE_FAST), BriefNanoModelMode.AUTO_STABLE.probeOrder(preferFull = true))
        BriefNanoModelMode.entries.filter { it != BriefNanoModelMode.AUTO_STABLE }.forEach {
            assertEquals(listOf(it), it.probeOrder(preferFull = true))
        }
        for (mode in BriefProviderMode.entries) for (hasKey in listOf(false, true)) {
            val calls = mutableListOf<Boolean>()
            BriefCoachAiRunner.route(false, coachAllowsCloudFallback(mode, hasKey), 0) { cloud ->
                calls += cloud
                BriefCoachGeneration.Unavailable
            }
            assertEquals(if (mode == BriefProviderMode.AUTO && hasKey) listOf(false, true) else listOf(false), calls)
        }
    }

    @Test fun `cloud can supply missing image context and failure preserves usable local advice`() = runBlocking {
        val feedback = BriefCoachAi.parse(response(), input().packet())!!
        val local = BriefCoachGeneration.Success(feedback, false, setOf("m0"))
        val cloud = BriefCoachGeneration.Success(feedback, true, setOf("m0", "m1"))
        val calls = mutableListOf<Boolean>()
        assertEquals(cloud, BriefCoachAiRunner.route(false, true, 2) { useCloud -> calls += useCloud; if (useCloud) cloud else local })
        assertEquals(listOf(false, true), calls)
        assertEquals(local, BriefCoachAiRunner.route(false, true, 2) { if (it) BriefCoachGeneration.Unavailable else local })
        calls.clear()
        BriefCoachAiRunner.route(false, true, 1) { calls += it; local }
        assertEquals(listOf(false), calls)
        calls.clear()
        val excerpt = local.copy(completeText = false)
        assertEquals(cloud, BriefCoachAiRunner.route(false, true, 1) { useCloud -> calls += useCloud; if (useCloud) cloud else excerpt })
        assertEquals(listOf(false, true), calls)
        val longer = input().copy(text = text + " More detail.".repeat(400))
        assertFalse(longer.packet().textComplete)
        assertTrue(longer.packet(cloud = true).textComplete)
    }

    @Test fun `all UI locales supply complete coaching copy with correct argument formats`() {
        for (locale in listOf(Locale.US, Locale.GERMAN, Locale.FRENCH, Locale.forLanguageTag("es"))) {
            val copy = TestBriefStrings(locale)
            assertFalse(copy.text(com.tjg.twidget.R.string.coach_ai_auto_note).isBlank())
            assertFalse(copy.text(com.tjg.twidget.R.string.coach_ai_quote, text).isBlank())
            assertFalse(copy.text(com.tjg.twidget.R.string.coach_ai_media_video, 1, 2).isBlank())
        }
    }

    private fun post(id: String, text: String, views: Long, likes: Long) = PostSummary(
        "https://x.com/example/status/$id", text, views, likes, 0, 0, 0, likes, now - 30 * 3_600_000L,
        "", "Example", "example", "", fullText = text)
    private fun analytics(posts: List<PostSummary>, replies: List<PostSummary> = emptyList()) = AnalyticsClient.analyticsFromPosts(
        "example", 100, posts.size + replies.size, false, posts, bestCandidates = posts + replies, cachedAt = now)
}
