package com.poolguide.tool

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Manifest guard (v1.0.43, REA hardening R7).
 *
 * The companion model only works because the tool stays offline and
 * unprivileged: no network, no package enumeration, no usage stats,
 * no storage access, no accessibility binding. The game already
 * enumerates displays and reads device properties (signals S8, S9,
 * S10 in the REA deep dive), so any new permission would be a new
 * detection surface. This test parses app/src/main/AndroidManifest.xml
 * and fails if a forbidden permission appears, if the permission set
 * drifts from the known minimal footprint, or if any component other
 * than MainActivity is exported, so a future cycle cannot silently
 * add a permission or an exported component. A deliberate change
 * must update this test in the same PR.
 */
class ManifestGuardTest {

    /** Permissions that must never appear in the manifest. */
    private val forbiddenPermissions = setOf(
        "INTERNET",
        "QUERY_ALL_PACKAGES",
        "PACKAGE_USAGE_STATS",
        "READ_EXTERNAL_STORAGE",
        "BIND_ACCESSIBILITY_SERVICE"
    )

    /** The full permission set the manifest is allowed to declare.
     * v1.0.71 adds REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (a settings
     * request, not a data permission — it opens the system screen
     * that asks the user to exempt the app from battery optimisation,
     * so Vivo/Funtouch cannot silently kill the overlay service
     * mid-game; it adds no detection surface). */
    private val allowedPermissions = setOf(
        "SYSTEM_ALERT_WINDOW",
        "FOREGROUND_SERVICE",
        "FOREGROUND_SERVICE_MEDIA_PROJECTION",
        "FOREGROUND_SERVICE_SPECIAL_USE",
        "POST_NOTIFICATIONS",
        "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    )

    private fun manifestDocument(): Element {
        val manifest = File("src/main/AndroidManifest.xml").takeIf { it.isFile }
            ?: File("app/src/main/AndroidManifest.xml").takeIf { it.isFile }
            ?: error("cannot find AndroidManifest.xml from ${File(".").absolutePath}")
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val doc = factory.newDocumentBuilder().parse(manifest)
        return doc.documentElement
    }

    private fun declaredPermissions(root: Element): Set<String> {
        val out = HashSet<String>()
        val nodes = root.getElementsByTagName("uses-permission")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            val name = el.getAttribute("android:name")
            if (name.isNotEmpty()) out += name.substringAfterLast('.')
        }
        return out
    }

    private fun components(root: Element): List<Element> {
        val out = ArrayList<Element>()
        for (tag in listOf("activity", "service", "receiver", "provider")) {
            val nodes = root.getElementsByTagName(tag)
            for (i in 0 until nodes.length) out += nodes.item(i) as Element
        }
        return out
    }

    @Test
    fun `manifest declares no forbidden permission`() {
        val declared = declaredPermissions(manifestDocument())
        val hits = declared.intersect(forbiddenPermissions)
        assertTrue(
            "forbidden permission declared: " + hits.joinToString(", "),
            hits.isEmpty()
        )
    }

    @Test
    fun `manifest permission set is exactly the minimal footprint`() {
        val declared = declaredPermissions(manifestDocument())
        assertEquals(
            "permission set drifted, update this test deliberately if the new permission is intended: " +
                "extra=" + (declared - allowedPermissions).joinToString(", ") +
                " missing=" + (allowedPermissions - declared).joinToString(", "),
            allowedPermissions,
            declared
        )
    }

    @Test
    fun `only MainActivity is exported`() {
        val root = manifestDocument()
        val exported = components(root).filter { it.getAttribute("android:exported") == "true" }
        assertTrue("no exported component found, MainActivity must be exported", exported.isNotEmpty())
        val bad = exported.filter { !it.getAttribute("android:name").endsWith("MainActivity") }
        assertTrue(
            "exported component other than MainActivity: " +
                bad.joinToString(", ") { it.tagName + " " + it.getAttribute("android:name") },
            bad.isEmpty()
        )
        val main = exported.singleOrNull { it.getAttribute("android:name").endsWith("MainActivity") }
        assertTrue("MainActivity must be the one exported component", main != null)
    }
}
