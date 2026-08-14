package com.dall06.karani.adapters.evaluators

import com.dall06.karani.ports.spi.BodyEvaluator
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.PathNotFoundException

class JsonBodyEvaluator : BodyEvaluator {
    override fun evaluate(rawBody: String, expression: String): String? {
        return try {
            val document = JsonPath.parse(rawBody)
            val result = document.read<Any>(expression)
            result?.toString()
        } catch (e: PathNotFoundException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    override fun supports(contentType: String?): Boolean {
        if (contentType == null) return false
        val cleanType = contentType.lowercase().trim()
        return cleanType.contains("application/json") || cleanType.contains("text/json")
    }
}

class RegexBodyEvaluator : BodyEvaluator {
    companion object {
        private val regexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    }

    override fun evaluate(rawBody: String, expression: String): String? {
        return try {
            val regex = regexCache.computeIfAbsent(expression) { it.toRegex() }
            val match = regex.find(rawBody)
            match?.value
        } catch (e: Exception) {
            null
        }
    }

    override fun supports(contentType: String?): Boolean {
        return true
    }
}

class XmlBodyEvaluator : BodyEvaluator {
    companion object {
        private val xpathFactory = javax.xml.xpath.XPathFactory.newInstance()
    }

    override fun evaluate(rawBody: String, expression: String): String? {
        return try {
            val xpath = xpathFactory.newXPath()
            val inputSource = org.xml.sax.InputSource(java.io.StringReader(rawBody))
            val result = xpath.evaluate(expression, inputSource)
            if (result.isEmpty()) return null
            result
        } catch (e: Exception) {
            null
        }
    }

    override fun supports(contentType: String?): Boolean {
        if (contentType == null) return false
        val cleanType = contentType.lowercase().trim()
        return cleanType.contains("application/xml") || cleanType.contains("text/xml")
    }
}

object BodyEvaluatorTool {
    private val evaluators = listOf(
        JsonBodyEvaluator(),
        XmlBodyEvaluator(),
        RegexBodyEvaluator()
    )

    fun evaluate(body: String, contentType: String?, expression: String): String? {
        if (expression.startsWith("$.") || expression.startsWith("$[")) {
            return JsonBodyEvaluator().evaluate(body, expression)
        }
        
        if (expression.startsWith("/") || expression.startsWith("descendant::")) {
            return XmlBodyEvaluator().evaluate(body, expression)
        }

        val evaluator = evaluators.find { it.supports(contentType) }
        if (evaluator != null) {
            return try {
                evaluator.evaluate(body, expression)
            } catch (e: Exception) {
                RegexBodyEvaluator().evaluate(body, expression)
            }
        }
        return RegexBodyEvaluator().evaluate(body, expression)
    }
}
