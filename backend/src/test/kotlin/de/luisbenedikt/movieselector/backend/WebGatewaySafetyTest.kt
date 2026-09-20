package de.luisbenedikt.movieselector.backend

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * GamePage's gateway (internal/gateway/rewrite.go) prefixes every quoted string that starts with
 * `/` in the JS it serves with `/play/<game>`. A `"/api"` or `"/session"` literal in the web
 * client therefore becomes a mangled, doubly-prefixed URL in production (404). Keep them out.
 */
class WebGatewaySafetyTest {
    // A literal starting with `/`, or a template piece that would (`"$id/accept"` -> `"/accept"`).
    private val UNSAFE = Regex("""\"/|\$\{?\w+\}?/""")

    @Test fun `web client sources contain no string literal starting with a slash`() {
        val offenders = File("../web/src/main/kotlin").walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { f -> f.readLines().mapIndexed { i, l -> Triple(f.name, i + 1, l) } }
            .filter { (_, _, line) -> UNSAFE.containsMatchIn(line) && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") }
            .map { (name, n, line) -> "$name:$n: ${line.trim()}" }
            .toList()
        assertTrue(offenders.isEmpty(), "gateway would rewrite these literals:\n" + offenders.joinToString("\n"))
    }
}
