package com.kgs.notes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.kgs.notes.nextcloud.NextcloudCredentials
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Platform credential-store boundaries with the real Android Keystore.
 *
 * Each test uses a private temporary store root and never deletes the Keystore key,
 * so connected Sources of an installed debug build are left untouched.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class CredentialStoreSecurityTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var root: Path

    private val credentials = NextcloudCredentials(
        serverUrl = "https://cloud.invalid/",
        loginName = "kgs-security-login-name",
        appPassword = "kgs-security-app-password-0123456789",
    )

    @Before fun createRoot() {
        root = Files.createTempDirectory(application.noBackupFilesDir.toPath(), "credential-security-")
    }

    @After fun deleteRoot() {
        root.deleteRecursively()
    }

    @Test fun storedFilesNeverContainCredentialsInPlainText() {
        NextcloudAccountStore(root).save("Security test", credentials)

        val files = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList() }
        assertEquals("Expected one profile and one sealed secret", 2, files.size)
        for (file in files) {
            val bytes = file.readBytes()
            for (secret in listOf(credentials.appPassword, credentials.loginName, "cloud.invalid")) {
                assertFalse("$file must not contain '$secret' in plain text", bytes.contains(secret.toByteArray()))
                assertFalse(
                    "$file must not contain '$secret' as UTF-16",
                    bytes.contains(secret.toByteArray(Charsets.UTF_16BE)),
                )
            }
        }
    }

    @Test fun sealingTheSameCredentialsTwiceUsesAFreshNonce() {
        val store = NextcloudAccountStore(root)
        store.save("Security test", credentials)
        val first = secretFile().readBytes()
        store.save("Security test", credentials)
        val second = secretFile().readBytes()
        assertFalse("AES-GCM must not reuse an IV for the same key", first.contentEquals(second))
    }

    @Test fun tamperedSecretIsRejectedAndTheStoreRecoversOnReconnect() {
        NextcloudAccountStore(root).save("Security test", credentials)
        val secret = secretFile()
        val bytes = secret.readBytes()
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0x01).toByte()
        secret.writeBytes(bytes)

        val reopened = NextcloudAccountStore(root)
        assertTrue("A modified ciphertext must fail authentication", reopened.loadAll().isEmpty())

        reopened.save("Security test", credentials)
        val restored = NextcloudAccountStore(root).loadAll().single()
        assertEquals(credentials.appPassword, restored.credentials.appPassword)
    }

    @Test fun truncatedOrOversizedSecretsAreRejectedWithoutCrashing() {
        val store = NextcloudAccountStore(root)
        store.save("Security test", credentials)
        val secret = secretFile()
        val original = secret.readBytes()

        secret.writeBytes(original.copyOf(original.size / 2))
        assertTrue(NextcloudAccountStore(root).loadAll().isEmpty())

        secret.writeBytes(original + ByteArray(1))
        assertTrue("Trailing data must be rejected", NextcloudAccountStore(root).loadAll().isEmpty())

        secret.writeBytes(ByteArray(0))
        assertTrue(NextcloudAccountStore(root).loadAll().isEmpty())
    }

    @Test fun profileWithoutSecretIsSkippedAndOtherSourcesStillLoad() {
        val store = NextcloudAccountStore(root)
        store.save("Broken", credentials)
        Files.delete(secretFile())
        store.save("Healthy", NextcloudCredentials(
            serverUrl = credentials.serverUrl,
            loginName = "kgs-security-second-login",
            appPassword = credentials.appPassword,
        ))

        val loaded = NextcloudAccountStore(root).loadAll()
        assertEquals(listOf("Healthy"), loaded.map { it.profile.name })
    }

    @Test fun storeLivesOutsideBackedUpAppStorage() {
        val backedUp = application.filesDir.resolve("local-source").canonicalFile
        val accounts = application.noBackupFilesDir.resolve("nextcloud-accounts").canonicalFile
        assertFalse(
            "Credentials must not be stored below the backed-up Local Source",
            accounts.path.startsWith(backedUp.path),
        )
        assertTrue(
            "The account store must live in noBackupFilesDir",
            accounts.path.startsWith(application.noBackupFilesDir.canonicalPath),
        )
    }

    private fun secretFile(): Path = root.resolve("secrets").listDirectoryEntries("*.bin").single()

    private fun ByteArray.contains(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return (0..size - needle.size).any { start ->
            needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }
    }
}
