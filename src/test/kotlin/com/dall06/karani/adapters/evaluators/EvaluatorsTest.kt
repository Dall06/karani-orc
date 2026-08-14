package com.dall06.karani.adapters.evaluators

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvaluatorsTest {

    private val jsonEvaluator = JsonBodyEvaluator()
    private val regexEvaluator = RegexBodyEvaluator()

    @Test
    fun testJsonBodyEvaluator() {
        data class TestCase(
            val name: String,
            val rawBody: String,
            val expression: String,
            val expected: String?
        )

        val jsonPayload = """
            {
                "event": "payment.succeeded",
                "data": {
                    "amount": 1000,
                    "currency": "usd",
                    "livemode": false
                },
                "items": [{"id": 1, "name": "shirt"}, {"id": 2, "name": "pants"}]
            }
        """.trimIndent()

        val testCases = listOf(
            TestCase("Root field", jsonPayload, "$.event", "payment.succeeded"),
            TestCase("Nested field", jsonPayload, "$.data.amount", "1000"),
            TestCase("Nested boolean", jsonPayload, "$.data.livemode", "false"),
            TestCase("Deep array item field", jsonPayload, "$.items[1].name", "pants"),
            TestCase("Non-existent field", jsonPayload, "$.data.invalid_field", null),
            TestCase("Malformed path", jsonPayload, "invalid-path", null)
        )

        for (tc in testCases) {
            val result = jsonEvaluator.evaluate(tc.rawBody, tc.expression)
            assertEquals(tc.expected, result, "Failed case: ${tc.name}")
        }
    }

    @Test
    fun testJsonBodyEvaluatorSupports() {
        assertTrue(jsonEvaluator.supports("application/json"))
        assertTrue(jsonEvaluator.supports("application/json; charset=utf-8"))
        assertTrue(jsonEvaluator.supports("text/json"))
        assertFalse(jsonEvaluator.supports("text/plain"))
        assertFalse(jsonEvaluator.supports(null))
    }

    @Test
    fun testRegexBodyEvaluator() {
        data class TestCase(
            val name: String,
            val rawBody: String,
            val expression: String,
            val expected: String?
        )

        val textPayload = "Hello, your verification code is 123456. Have a nice day!"

        val testCases = listOf(
            TestCase("Extract digits", textPayload, "\\d+", "123456"),
            TestCase("Exact word match", textPayload, "verification", "verification"),
            TestCase("No match", textPayload, "failed_payment", null),
            TestCase("Invalid regex pattern", textPayload, "[", null)
        )

        for (tc in testCases) {
            val result = regexEvaluator.evaluate(tc.rawBody, tc.expression)
            assertEquals(tc.expected, result, "Failed case: ${tc.name}")
        }
    }

    @Test
    fun testXmlBodyEvaluator() {
        val xmlEvaluator = XmlBodyEvaluator()

        data class TestCase(
            val name: String,
            val rawBody: String,
            val expression: String,
            val expected: String?
        )

        val xmlPayload = """
            <notification>
                <event>refund.created</event>
                <data>
                    <amount>150.00</amount>
                    <status>succeeded</status>
                </data>
            </notification>
        """.trimIndent()

        val testCases = listOf(
            TestCase("Root event element", xmlPayload, "/notification/event/text()", "refund.created"),
            TestCase("Nested status element", xmlPayload, "/notification/data/status/text()", "succeeded"),
            TestCase("Nested amount element", xmlPayload, "/notification/data/amount/text()", "150.00"),
            TestCase("Non-existent element", xmlPayload, "/notification/invalid_tag/text()", null),
            TestCase("Malformed XPath", xmlPayload, "///invalid", null)
        )

        for (tc in testCases) {
            val result = xmlEvaluator.evaluate(tc.rawBody, tc.expression)
            assertEquals(tc.expected, result, "Failed case: ${tc.name}")
        }

        assertTrue(xmlEvaluator.supports("application/xml"))
        assertTrue(xmlEvaluator.supports("text/xml"))
        assertFalse(xmlEvaluator.supports("application/json"))
    }
}
