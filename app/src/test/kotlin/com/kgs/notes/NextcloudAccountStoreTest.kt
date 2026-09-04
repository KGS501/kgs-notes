package com.kgs.notes

import com.kgs.notes.nextcloud.NextcloudCredentials
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NextcloudAccountStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun reconnectingTheSameAccountRestoresOneEncryptedSource() {
        val root = temporary.newFolder("accounts").toPath()
        val credentials = NextcloudCredentials(
            serverUrl = "https://cloud.invalid/nextcloud/",
            loginName = "river",
            appPassword = "very-secret-app-password",
        )
        val store = NextcloudAccountStore(root, ReversibleTestSecretBox)

        val first = store.save("Home Cloud", credentials)
        val second = store.save("Renamed Cloud", credentials)
        val restored = NextcloudAccountStore(root, ReversibleTestSecretBox).loadAll().single()

        assertEquals(first.profile.id, second.profile.id)
        assertEquals("Renamed Cloud", restored.profile.name)
        assertEquals(credentials.serverUrl, restored.credentials.serverUrl)
        assertEquals(credentials.loginName, restored.credentials.loginName)
        assertEquals(credentials.appPassword, restored.credentials.appPassword)
        val durableText = Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile)
                .map { String(Files.readAllBytes(it), Charsets.ISO_8859_1) }
                .toList()
                .joinToString()
        }
        assertFalse(durableText.contains(credentials.appPassword))
    }

    private object ReversibleTestSecretBox : SecretBox {
        override fun seal(plainText: ByteArray): ByteArray = plainText.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun open(sealed: ByteArray): ByteArray = seal(sealed)
    }
}
