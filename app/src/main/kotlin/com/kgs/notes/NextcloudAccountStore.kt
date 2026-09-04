package com.kgs.notes

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.kgs.notes.engine.SourceId
import com.kgs.notes.nextcloud.NextcloudCredentials
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.Writer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension

data class NextcloudAccountProfile(
    val id: SourceId,
    val name: String,
)

data class ConnectedNextcloudAccount(
    val profile: NextcloudAccountProfile,
    val credentials: NextcloudCredentials,
)

internal interface SecretBox {
    fun seal(plainText: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

internal class NextcloudAccountStore(
    private val root: Path,
    private val secretBox: SecretBox,
) {
    constructor(root: Path) : this(root, AndroidKeystoreSecretBox())

    private val profilesRoot = root.resolve("profiles")
    private val secretsRoot = root.resolve("secrets")

    init {
        profilesRoot.createDirectories()
        secretsRoot.createDirectories()
    }

    fun save(name: String, credentials: NextcloudCredentials): ConnectedNextcloudAccount {
        val profile = profileFor(name, credentials)
        val secret = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(SECRET_FORMAT_VERSION)
                output.writeUTF(credentials.serverUrl)
                output.writeUTF(credentials.loginName)
                output.writeUTF(credentials.appPassword)
            }
            secretBox.seal(bytes.toByteArray())
        }
        val properties = Properties().apply {
            setProperty("schema", PROFILE_SCHEMA.toString())
            setProperty("id", profile.id.value)
            setProperty("name", profile.name)
        }
        writeBytesAtomically(secretPath(profile.id), secret)
        writePropertiesAtomically(profilePath(profile.id), properties)
        return ConnectedNextcloudAccount(profile, credentials)
    }

    fun profileFor(name: String, credentials: NextcloudCredentials) = NextcloudAccountProfile(
        id = stableSourceId(credentials),
        name = normalizeSourceName(name),
    )

    fun loadAll(): List<ConnectedNextcloudAccount> = Files.list(profilesRoot).use { paths ->
        paths.iterator().asSequence()
            .filter { it.isRegularFile() && it.extension == "properties" }
            .map { profilePath -> runCatching { load(profilePath) }.getOrNull() }
            .filter { it != null }
            .map { it!! }
            .toList()
            .sortedBy { it.profile.name.lowercase() }
    }

    fun remove(id: SourceId) {
        profilePath(id).deleteIfExists()
        secretPath(id).deleteIfExists()
    }

    private fun load(path: Path): ConnectedNextcloudAccount {
        val properties = Properties()
        Files.newBufferedReader(path).use(properties::load)
        require(properties.getProperty("schema") == PROFILE_SCHEMA.toString())
        val id = SourceId(properties.getProperty("id") ?: path.nameWithoutExtension)
        val plainText = secretBox.open(Files.readAllBytes(secretPath(id)))
        val credentials = DataInputStream(ByteArrayInputStream(plainText)).use { input ->
            require(input.readInt() == SECRET_FORMAT_VERSION)
            NextcloudCredentials(
                serverUrl = input.readUTF(),
                loginName = input.readUTF(),
                appPassword = input.readUTF(),
            )
        }
        return ConnectedNextcloudAccount(
            profile = NextcloudAccountProfile(
                id = id,
                name = normalizeSourceName(properties.getProperty("name", "Nextcloud")),
            ),
            credentials = credentials,
        )
    }

    private fun profilePath(id: SourceId) = profilesRoot.resolve("${id.value}.properties")
    private fun secretPath(id: SourceId) = secretsRoot.resolve("${id.value}.bin")

    private fun writePropertiesAtomically(path: Path, properties: Properties) {
        path.parent.createDirectories()
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            Files.newBufferedWriter(
                temporary,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { writer: Writer -> properties.store(writer, null) }
            moveAtomically(temporary, path)
        } finally {
            temporary.deleteIfExists()
        }
    }

    private fun writeBytesAtomically(path: Path, bytes: ByteArray) {
        path.parent.createDirectories()
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING)
            moveAtomically(temporary, path)
        } finally {
            temporary.deleteIfExists()
        }
    }

    private fun moveAtomically(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val PROFILE_SCHEMA = 1
        const val SECRET_FORMAT_VERSION = 1
        const val MAX_SOURCE_NAME_CODE_POINTS = 48

        fun stableSourceId(credentials: NextcloudCredentials): SourceId {
            val identity = "${credentials.serverUrl.trimEnd('/')}\n${credentials.loginName}"
            val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            return SourceId("nextcloud-" + digest.take(12).joinToString("") { "%02x".format(it) })
        }

        fun normalizeSourceName(value: String): String {
            val clean = value.lineSequence().firstOrNull().orEmpty().trim().ifBlank { "Nextcloud" }
            val points = clean.codePoints().toArray()
            return if (points.size <= MAX_SOURCE_NAME_CODE_POINTS) clean else {
                String(points, 0, MAX_SOURCE_NAME_CODE_POINTS)
            }
        }
    }
}

private class AndroidKeystoreSecretBox : SecretBox {
    override fun seal(plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plainText)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(FORMAT_VERSION)
                output.writeInt(cipher.iv.size)
                output.write(cipher.iv)
                output.writeInt(encrypted.size)
                output.write(encrypted)
            }
            bytes.toByteArray()
        }
    }

    override fun open(sealed: ByteArray): ByteArray = DataInputStream(ByteArrayInputStream(sealed)).use { input ->
        require(input.readInt() == FORMAT_VERSION)
        val ivLength = input.readInt()
        require(ivLength in 12..16)
        val iv = ByteArray(ivLength).also(input::readFully)
        val encryptedLength = input.readInt()
        require(encryptedLength in 16..MAX_ENCRYPTED_BYTES)
        val encrypted = ByteArray(encryptedLength).also(input::readFully)
        require(input.read() == -1)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.doFinal(encrypted)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "kgs-notes-nextcloud-accounts-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = 1
        const val MAX_ENCRYPTED_BYTES = 1_048_576
    }
}
