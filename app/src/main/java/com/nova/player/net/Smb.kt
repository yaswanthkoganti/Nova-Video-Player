package com.nova.player.net

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.Properties

data class SmbEntry(val name: String, val url: String, val isDir: Boolean)

object Smb {
    private val videoExt = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "mpg", "mpeg", "3gp", "iso")

    private fun ctx(user: String, pass: String): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
        }
        val base = BaseContext(PropertyConfiguration(props))
        return if (user.isBlank()) base.withCredentials(NtlmPasswordAuthenticator(null, "guest", ""))
        else base.withCredentials(NtlmPasswordAuthenticator(null, user, pass))
    }

    suspend fun list(url: String, user: String, pass: String): List<SmbEntry> = withContext(Dispatchers.IO) {
        val dir = SmbFile(url, ctx(user, pass))
        dir.listFiles().orEmpty()
            .filter { !it.name.endsWith("$/") && !it.name.startsWith(".") }
            .map { f -> SmbEntry(f.name.trimEnd('/'), f.url.toString(), f.isDirectory) }
            .filter { it.isDir || it.name.substringAfterLast('.', "").lowercase() in videoExt }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    /** libVLC understands smb://user:pass@host/path directly. */
    fun playUrl(url: String, user: String, pass: String): String =
        if (user.isBlank()) url
        else url.replaceFirst("smb://", "smb://${URLEncoder.encode(user, "UTF-8")}:${URLEncoder.encode(pass, "UTF-8")}@")
}
