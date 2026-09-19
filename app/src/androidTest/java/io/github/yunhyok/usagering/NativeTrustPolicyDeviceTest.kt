package io.github.yunhyok.usagering

import android.net.http.X509TrustManagerExtensions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Collections
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeTrustPolicyDeviceTest {
    @Test
    fun productionManagerRejectsUserCaWhileAcceptingSystemRoots() {
        assumeTrue(
            "trust-policy evidence argument is disabled",
            InstrumentationRegistry.getArguments().getString("usageRingTrustPolicyEvidence")
                ?.equals("true", ignoreCase = true) == true,
        )

        val userCa = fixture("trust_policy_user_ca.cer")
        val leaf = fixture("trust_policy_leaf.cer")
        val androidCaStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val fixtureAlias = Collections.list(androidCaStore.aliases()).firstOrNull { alias ->
            alias.startsWith("user:") &&
                (androidCaStore.getCertificate(alias) as? X509Certificate)?.encoded?.contentEquals(userCa.encoded) == true
        }
        assertNotNull("fixture CA is not installed in AndroidCAStore as a user certificate", fixtureAlias)

        val legacyManager = trustManager(androidCaStore)
        assertTrue(
            "raw AndroidCAStore must accept the installed user-CA chain",
            legacyManager.checkServerTrusted(arrayOf(leaf, userCa), "RSA", TEST_HOST).isNotEmpty(),
        )

        val verifierClass = Class.forName(VERIFIER_CLASS)
        val verifier = verifierClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        val lazyManager = verifierClass.getDeclaredField("systemTrustManager")
            .apply { isAccessible = true }
            .get(verifier) as Lazy<*>
        val productionManager = lazyManager.value as? X509TrustManagerExtensions
            ?: throw AssertionError("vendored production systemTrustManager is unavailable")

        try {
            productionManager.checkServerTrusted(arrayOf(leaf, userCa), "RSA", TEST_HOST)
            fail("vendored production manager accepted an installed user CA")
        } catch (_: CertificateException) {
            // Expected: the app Network Security Configuration trusts system anchors only.
        }

        val systemRoots = defaultTrustManager().acceptedIssuers
        val acceptedSystemRoot = systemRoots.firstOrNull { root ->
            runCatching {
                root.checkValidity()
                productionManager.checkServerTrusted(
                    arrayOf(root),
                    root.publicKey.algorithm,
                    SYSTEM_ROOT_HOST,
                )
            }.isSuccess
        }
        assertNotNull("vendored production manager must accept at least one current system root", acceptedSystemRoot)
    }

    private fun fixture(name: String): X509Certificate {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return assets.open(name).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }

    private fun trustManager(keyStore: KeyStore): X509TrustManagerExtensions =
        X509TrustManagerExtensions(x509TrustManager(keyStore))

    private fun defaultTrustManager(): X509TrustManager = x509TrustManager(null)

    private fun x509TrustManager(keyStore: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private companion object {
        const val VERIFIER_CLASS = "org.rustls.platformverifier.CertificateVerifier"
        const val TEST_HOST = "trust-policy.test"
        const val SYSTEM_ROOT_HOST = "system-root.invalid"
    }
}
