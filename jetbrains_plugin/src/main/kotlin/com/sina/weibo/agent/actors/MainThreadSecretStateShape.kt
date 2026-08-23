// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.actors

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Secret state management service interface.
 */
interface MainThreadSecretStateShape : Disposable {
    /**
     * Gets the secret.
     * @param extensionId Extension ID
     * @param key Secret key identifier
     * @return Secret value, returns null if not exists
     */
    suspend fun getPassword(extensionId: String, key: String): String?

    /**
     * Sets the secret.
     * @param extensionId Extension ID
     * @param key Secret key identifier
     * @param value Secret value
     */
    suspend fun setPassword(extensionId: String, key: String, value: String)

    /**
     * Deletes the secret.
     * @param extensionId Extension ID
     * @param key Secret key identifier
     */
    suspend fun deletePassword(extensionId: String, key: String)
}

/**
 * Implementation of the secret state management service.
 * Stores secrets in ~/.costrict-jetbrains/secrets.json file.
 *
 * The store is resilient against corruption:
 * - Writes are atomic (temp file + atomic rename), so readers never observe a torn document
 *   and a crash mid-write cannot leave the file malformed.
 * - A cross-process file lock serializes read-modify-write cycles between multiple IDE
 *   instances running the plugin at the same time (e.g. IntelliJ + WebStorm); the in-JVM
 *   [Mutex] alone cannot protect the file from another process.
 * - If the existing file is already malformed (legacy torn write, manual edit, etc.), the
 *   corrupt content is preserved as a timestamped backup and the store recovers with an
 *   empty state instead of failing every subsequent secret write.
 */
class MainThreadSecretState : MainThreadSecretStateShape {
    private val logger = Logger.getInstance(MainThreadSecretState::class.java)
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val mutex = Mutex()
    
    // Configuration file path
    private val secretsDir = File(System.getProperty("user.home"), ".costrict-jetbrains")
    private val secretsFile = File(secretsDir, "secrets.json")
    // Cross-process lock file: one lock file guards the whole store across IDE instances.
    private val lockFile = File(secretsDir, "secrets.json.lock")
    
    init {
        // Ensure the directory exists
        if (!secretsDir.exists()) {
            secretsDir.mkdirs()
            logger.info("Create secret storage directory: ${secretsDir.absolutePath}")
        }
    }

    override suspend fun getPassword(extensionId: String, key: String): String? = mutex.withLock {
        try {
            withSecretsFileLock {
                val jsonObject = readSecretsObject()
                val extensionObject = jsonObject.getAsJsonObject(extensionId) ?: return@withSecretsFileLock null
                return@withSecretsFileLock extensionObject.get(key)?.asString
            }
        } catch (e: Exception) {
            logger.warn("Failed to get secret: extensionId=$extensionId, key=$key", e)
            return null
        }
    }

    override suspend fun setPassword(extensionId: String, key: String, value: String) = mutex.withLock {
        try {
            withSecretsFileLock {
                val jsonObject = loadSecretsObjectForWrite()
                
                val extensionObject = jsonObject.getAsJsonObject(extensionId) ?: JsonObject().also {
                    jsonObject.add(extensionId, it)
                }
                
                extensionObject.addProperty(key, value)
                
                writeSecretsObject(jsonObject)
            }
            
            logger.info("Successfully set secret: extensionId=$extensionId, key=$key")
        } catch (e: Exception) {
            logger.error("Failed to set secret: extensionId=$extensionId, key=$key", e)
            throw e
        }
    }

    override suspend fun deletePassword(extensionId: String, key: String) = mutex.withLock {
        try {
            withSecretsFileLock {
                if (secretsFile.exists()) {
                    val jsonObject = loadSecretsObjectForWrite()
                    val extensionObject = jsonObject.getAsJsonObject(extensionId)
                    if (extensionObject != null) {
                        extensionObject.remove(key)
                        
                        // If extension object is empty, delete the entire extension
                        if (extensionObject.size() == 0) {
                            jsonObject.remove(extensionId)
                        }
                        
                        writeSecretsObject(jsonObject)
                    }
                }
            }
            
            logger.info("Successfully deleted secret: extensionId=$extensionId, key=$key")
        } catch (e: Exception) {
            logger.error("Failed to delete secret: extensionId=$extensionId, key=$key", e)
            throw e
        }
    }

    /**
     * Runs [block] while holding an exclusive cross-process lock on the secrets store.
     * Multiple IDE instances can run the plugin at the same time; the in-process [mutex]
     * does not serialize them, so the file lock is what keeps concurrent read-modify-write
     * cycles from corrupting secrets.json. The critical section is tiny (a small file
     * read-modify-write), so lock contention is negligible.
     */
    private fun <T> withSecretsFileLock(block: () -> T): T {
        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = channel.lock()
            try {
                return block()
            } finally {
                lock.release()
            }
        }
    }

    /**
     * Reads the secrets file; returns an empty object when the file is missing, blank,
     * or malformed. A malformed file is only reported (and preserved) here; writers
     * recover through [loadSecretsObjectForWrite].
     */
    private fun readSecretsObject(): JsonObject {
        if (!secretsFile.exists()) {
            return JsonObject()
        }
        
        val jsonContent = secretsFile.readText()
        if (jsonContent.isBlank()) {
            return JsonObject()
        }
        
        return try {
            JsonParser.parseString(jsonContent).asJsonObject
        } catch (e: Exception) {
            logger.warn("Secrets file is malformed, treating as empty: ${secretsFile.absolutePath}", e)
            JsonObject()
        }
    }

    /**
     * Loads the secrets file for a read-modify-write cycle. When the existing file is
     * malformed, it is preserved as a timestamped backup and an empty store is returned
     * so the write can recover instead of failing.
     */
    private fun loadSecretsObjectForWrite(): JsonObject {
        if (!secretsFile.exists()) {
            return JsonObject()
        }
        
        val jsonContent = secretsFile.readText()
        if (jsonContent.isBlank()) {
            return JsonObject()
        }
        
        return try {
            JsonParser.parseString(jsonContent).asJsonObject
        } catch (e: Exception) {
            backupCorruptSecretsFile(e)
            JsonObject()
        }
    }

    /**
     * Preserves a malformed secrets file for forensics before it is overwritten.
     */
    private fun backupCorruptSecretsFile(cause: Exception) {
        try {
            val backup = File(secretsDir, "secrets.json.corrupt-${System.currentTimeMillis()}")
            Files.move(secretsFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            logger.warn("Backed up malformed secrets file to ${backup.absolutePath}; starting fresh. Cause: ${cause.message}")
        } catch (backupError: Exception) {
            logger.warn("Failed to back up malformed secrets file (${secretsFile.absolutePath}); it will be overwritten", backupError)
        }
    }

    /**
     * Atomically replaces the secrets file: writes to a temp file first, then moves it
     * into place, so a crash mid-write never leaves a torn document behind.
     */
    private fun writeSecretsObject(jsonObject: JsonObject) {
        val tmpFile = File(secretsDir, "secrets.json.tmp")
        tmpFile.writeText(gson.toJson(jsonObject))
        try {
            Files.move(
                tmpFile.toPath(), secretsFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmpFile.toPath(), secretsFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        
        // Best effort: keep the secrets file readable only by the owner, matching the
        // extension-host secret storage behaviour.
        try {
            Files.setPosixFilePermissions(secretsFile.toPath(), PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem (e.g. Windows)
        } catch (_: java.io.IOException) {
            // Ignore chmod failures
        }
    }

    override fun dispose() {
        logger.info("Disposing MainThreadSecretState resources")
        // JSON file storage doesn't require special resource disposal
    }
}
