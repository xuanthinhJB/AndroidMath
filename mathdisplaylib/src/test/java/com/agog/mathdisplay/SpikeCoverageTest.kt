package com.agog.mathdisplay

import com.agog.mathdisplay.parse.MTMathListBuilder
import com.agog.mathdisplay.parse.MTParseError
import com.agog.mathdisplay.parse.MTParseErrors
import org.junit.Test
import java.io.File

/** Temporary: how much of MathMate's LaTeX the parser reads (SPIKE_FORMULAS = a JSON file). */
class SpikeCoverageTest {
    private fun strings(json: String, key: String): List<String> {
        val start = json.indexOf("\"$key\"")
        val open = json.indexOf('[', start)
        val out = mutableListOf<String>()
        var i = open + 1
        val buffer = StringBuilder()
        var inString = false
        while (i < json.length) {
            val c = json[i]
            if (!inString) {
                if (c == ']') break
                if (c == '"') { inString = true; buffer.clear() }
            } else if (c == '\\') {
                val n = json[i + 1]
                when (n) {
                    'n' -> buffer.append('\n')
                    't' -> buffer.append('\t')
                    'u' -> { buffer.append(json.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                    else -> buffer.append(n)
                }
                i++
            } else if (c == '"') {
                inString = false; out.add(buffer.toString())
            } else buffer.append(c)
            i++
        }
        return out
    }

    @Test
    fun coverage() {
        val path = System.getenv("SPIKE_FORMULAS") ?: return
        val json = File(path).readText()
        for (key in listOf("templates", "answers")) {
            val all = strings(json, key)
            val failures = mutableListOf<String>()
            for (latex in all) {
                val prepared = latex.replace(Regex("""\\operatorname\{([^{}]*)\}"""), """\\mathrm{$1}""")
                val error = MTParseError()
                val list = MTMathListBuilder.buildFromString(prepared, error)
                if (list == null || error.errorcode != MTParseErrors.ErrorNone) failures.add("$latex  →  ${error.errordesc}")
            }
            println("SPIKE $key: ${all.size - failures.size}/${all.size}")
            failures.forEach { println("SPIKE   ✗ $it") }
        }
    }
}
