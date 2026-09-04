package com.kgs.notes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.kgs.notes.nextcloud.NextcloudCredentials
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class NextcloudAccountStoreDeviceTest {
    @Test
    fun credentialsSurviveAStoreRestartUsingTheAndroidKeystore() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val root = Files.createTempDirectory(application.noBackupFilesDir.toPath(), "account-store-test-")
        val credentials = NextcloudCredentials(
            serverUrl = "https://cloud.invalid/",
            loginName = "device-test-user",
            appPassword = "device-test-app-password",
        )

        NextcloudAccountStore(root).save("Device test", credentials)
        val restored = NextcloudAccountStore(root).loadAll().single()

        assertEquals("Device test", restored.profile.name)
        assertEquals(credentials.serverUrl, restored.credentials.serverUrl)
        assertEquals(credentials.loginName, restored.credentials.loginName)
        assertEquals(credentials.appPassword, restored.credentials.appPassword)
    }
}
