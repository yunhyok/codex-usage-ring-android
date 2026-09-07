package io.github.yunhyok.usagering

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class NetworkSecurityConfigTest {
    @Test
    fun manifestKeepsCleartextDisabledAndReferencesTheDedicatedConfig() {
        val manifest = parse("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml")
        val applications = manifest.getElementsByTagName("application")
        assertEquals("AndroidManifest.xml must declare exactly one application", 1, applications.length)

        val application = applications.item(0) as Element
        assertEquals(
            "false",
            application.getAttributeNS(ANDROID_NAMESPACE, "usesCleartextTraffic"),
        )
        assertEquals(
            "@xml/network_security_config",
            application.getAttributeNS(ANDROID_NAMESPACE, "networkSecurityConfig"),
        )
    }

    @Test
    fun networkConfigHasOnlyTheCrlDistributionHostCleartextException() {
        val config = parse("app/src/main/res/xml/network_security_config.xml", "src/main/res/xml/network_security_config.xml")
        assertEquals("network-security-config", config.documentElement.tagName)
        assertTrue("global cleartext policy must remain implicit", !config.documentElement.hasAttribute("cleartextTrafficPermitted"))

        val baseConfigs = config.getElementsByTagName("base-config")
        assertEquals("exactly one base-config is required", 1, baseConfigs.length)
        val base = baseConfigs.item(0) as Element
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))

        val trustAnchors = base.getElementsByTagName("trust-anchors")
        assertEquals(1, trustAnchors.length)
        val certificates = (trustAnchors.item(0) as Element).getElementsByTagName("certificates")
        assertEquals("base-config must use exactly one trust anchor declaration", 1, certificates.length)
        assertEquals("system", (certificates.item(0) as Element).getAttribute("src"))

        val domainConfigs = config.getElementsByTagName("domain-config")
        assertEquals("exactly one cleartext exception is required", 1, domainConfigs.length)
        val domainConfig = domainConfigs.item(0) as Element
        assertEquals("true", domainConfig.getAttribute("cleartextTrafficPermitted"))

        val domains = config.getElementsByTagName("domain")
        assertEquals("no domains other than c.pki.goog may be allowlisted", 1, domains.length)
        val domain = domains.item(0) as Element
        assertEquals("c.pki.goog", domain.textContent.trim())
        assertEquals("false", domain.getAttribute("includeSubdomains"))
        assertTrue("the exception must be scoped to the one domain-config", domainConfig.isSameNode(domain.parentNode))
    }

    @Test
    fun configHasNoDebugOverridesPinsOrNonSystemTrustAnchors() {
        val config = parse("app/src/main/res/xml/network_security_config.xml", "src/main/res/xml/network_security_config.xml")
        assertEquals(0, config.getElementsByTagName("debug-overrides").length)
        assertEquals(0, config.getElementsByTagName("pin-set").length)
        assertEquals(0, config.getElementsByTagName("pin").length)

        val allCertificates = config.getElementsByTagName("certificates")
        assertEquals(1, allCertificates.length)
        assertEquals("system", (allCertificates.item(0) as Element).getAttribute("src"))

        val allElements = config.getElementsByTagName("*")
        for (index in 0 until allElements.length) {
            val element = allElements.item(index) as Element
            if (element.hasAttribute("cleartextTrafficPermitted")) {
                assertTrue(
                    "only base-config and the single domain-config may set cleartext policy",
                    element.tagName == "base-config" || element.tagName == "domain-config",
                )
            }
        }
    }

    private fun parse(vararg relativePaths: String): Document {
        val path = locate(*relativePaths)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(path.toFile()).also { it.documentElement.normalize() }
    }

    private fun locate(vararg relativePaths: String): Path {
        var directory = Path.of("").toAbsolutePath()
        while (true) {
            relativePaths.forEach { relative ->
                val candidate = directory.resolve(relative).normalize()
                if (Files.isRegularFile(candidate)) return candidate
            }
            directory = directory.parent ?: break
        }
        throw AssertionError("could not locate static Android resource: ${relativePaths.joinToString()}")
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
