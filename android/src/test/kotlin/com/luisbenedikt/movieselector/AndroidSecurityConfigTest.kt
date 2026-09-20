package com.luisbenedikt.movieselector

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The app talks only to the Movie Selector backend, over HTTPS, and carries no backend secrets. */
class AndroidSecurityConfigTest {
    private fun sources() = File("src").listFiles { f -> f.name != "test" }!!.asSequence()
        .flatMap { it.walkTopDown() }.filter { it.isFile && it.extension in setOf("kt", "xml") }

    @Test fun `no wencke host, credentials or database access is referenced`() {
        val forbidden = Regex("""wencke\.love|site[-_ ]?password|WENCKE_|postgres|jdbc:|:5432|api/v1/""", RegexOption.IGNORE_CASE)
        val hits = sources().flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (forbidden.containsMatchIn(l)) "${f.name}:${i + 1}: ${l.trim()}" else null } }.toList()
        assertTrue(hits.isEmpty(), hits.joinToString("\n"))
    }

    @Test fun `default API base is the production https prefix`() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue("\"https://game.luisbenedikt.de/play/movie-selector/api\"" in gradle.replace("\\\"", "\""))
    }

    @Test fun `main manifest forbids cleartext and uses the https-only network config`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("""android:usesCleartextTraffic="false"""" in manifest)
        assertTrue("@xml/network_security_config" in manifest)
        val cfg = File("src/main/res/xml/network_security_config.xml").readText()
        assertFalse("cleartextTrafficPermitted=\"true\"" in cfg)
        assertTrue("<domain-config" !in cfg)
    }

    @Test fun `no build variant overrides the network config (no cleartext, no domain-config)`() {
        assertFalse(File("src/debug").exists(), "a debug override would ship cleartext/domain rules in the installable APK")
    }
}
