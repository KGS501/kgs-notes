package com.kgs.notes

import android.Manifest
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.security.NetworkSecurityPolicy
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser

/** Manifest, network, and backup boundaries as installed on the device. */
class PlatformSecurityBoundaryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageManager = context.packageManager

    @Test fun cleartextTrafficIsDeniedForEveryHost() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertFalse(policy.isCleartextTrafficPermitted)
        for (host in listOf("cloud.example", "localhost", "127.0.0.1", "10.0.2.2")) {
            assertFalse("Cleartext must be denied for $host", policy.isCleartextTrafficPermitted(host))
        }
    }

    @Test fun noDangerousPermissionIsRequested() {
        @Suppress("DEPRECATION")
        val requested = packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertTrue(Manifest.permission.INTERNET in requested)
        val dangerous = requested.filter { name ->
            val info = runCatching { packageManager.getPermissionInfo(name, 0) }.getOrNull() ?: return@filter false
            info.baseProtection() == PermissionInfo.PROTECTION_DANGEROUS
        }
        assertEquals("Runtime (dangerous) permissions are not expected", emptyList<String>(), dangerous)
    }

    @Test fun onlyTheLauncherActivityIsExportedWithoutAPermission() {
        val info = packageInfo(
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS,
        )
        val unprotected = buildList<ComponentInfo> {
            info.activities.orEmpty().filter { it.exported && it.permission == null }.forEach(::add)
            info.services.orEmpty().filter { it.exported && it.permission == null }.forEach(::add)
            info.receivers.orEmpty().filter { it.exported && it.permission == null }.forEach(::add)
            info.providers.orEmpty().filter {
                it.exported && it.readPermission == null && it.writePermission == null
            }.forEach(::add)
        }.map { it.name }.filterNot { BuildConfig.DEBUG && it in DebugOnlyTestComponents }

        assertEquals(listOf(MainActivity::class.java.name), unprotected)
    }

    @Test fun backupsContainOnlyTheLocalSource() {
        val expected = listOf(BackupRule("include", "file", "local-source/"))
        assertEquals(expected, backupRules(R.xml.backup_rules).map { it.rule })
        val extraction = backupRules(R.xml.data_extraction_rules)
        assertEquals(setOf("cloud-backup", "device-transfer"), extraction.map { it.section }.toSet())
        for (section in listOf("cloud-backup", "device-transfer")) {
            assertEquals(section, expected, extraction.filter { it.section == section }.map { it.rule })
        }
    }

    @Test fun appDataIsNotWorldReadableAndAppIsNotDebuggableOutsideDebugBuilds() {
        val application = context.applicationInfo
        val debuggable = application.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        assertEquals(BuildConfig.DEBUG, debuggable)
        for (directory in listOf(context.filesDir, context.noBackupFilesDir)) {
            val mode = android.system.Os.stat(directory.path).st_mode
            // Context creates these as 0771; others may traverse but never list, read, or write.
            assertEquals("$directory must not be readable or writable by other users", 0, mode and 0b110)
        }
    }

    private fun packageInfo(flags: Int): PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(context.packageName, flags)
    }

    private fun PermissionInfo.baseProtection(): Int = if (Build.VERSION.SDK_INT >= 28) {
        protection
    } else {
        @Suppress("DEPRECATION")
        protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
    }

    private data class BackupRule(val kind: String, val domain: String?, val path: String?)
    private data class SectionRule(val section: String?, val rule: BackupRule)

    private fun backupRules(resource: Int): List<SectionRule> {
        val rules = mutableListOf<SectionRule>()
        var section: String? = null
        val parser = context.resources.getXml(resource)
        try {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "cloud-backup", "device-transfer" -> section = parser.name
                    "include", "exclude" -> rules += SectionRule(
                        section,
                        BackupRule(
                            parser.name,
                            parser.getAttributeValue(null, "domain"),
                            parser.getAttributeValue(null, "path"),
                        ),
                    )
                }
            }
        } finally {
            parser.close()
        }
        return rules
    }

    private companion object {
        /** Merged only into debug builds by compose ui-tooling and ui-test-manifest. */
        val DebugOnlyTestComponents = setOf(
            "androidx.compose.ui.tooling.PreviewActivity",
            "androidx.activity.ComponentActivity",
        )
    }
}
