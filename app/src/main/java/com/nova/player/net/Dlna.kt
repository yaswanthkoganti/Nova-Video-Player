package com.nova.player.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.*

data class DlnaDevice(val name: String, val controlUrl: String)
data class DlnaEntry(val id: String, val title: String, val isContainer: Boolean, val url: String?)

object Dlna {
    suspend fun discover(ctx: Context, timeoutMs: Long = 3500): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wm.createMulticastLock("nova-ssdp").apply { setReferenceCounted(true); acquire() }
        val locations = linkedSetOf<String>()
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 700
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n").toByteArray()
                val addr = InetAddress.getByName("239.255.255.250")
                repeat(2) { s.send(DatagramPacket(msg, msg.size, addr, 1900)) }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size); s.receive(p)
                        Regex("(?i)location:\\s*(\\S+)").find(String(p.data, 0, p.length))?.groupValues?.get(1)?.let { locations += it }
                    } catch (_: SocketTimeoutException) { }
                }
            }
        } finally { lock.release() }
        locations.mapNotNull { runCatching { describe(it) }.getOrNull() }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 8000
        return c.inputStream.bufferedReader().readText()
    }

    private fun describe(location: String): DlnaDevice? {
        val p = Xml.newPullParser(); p.setInput(StringReader(get(location)))
        var name = "Media server"; var control: String? = null
        var tag = ""; var isCd = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> { tag = p.name; if (tag == "service") isCd = false }
                XmlPullParser.TEXT -> {
                    val t = p.text.trim()
                    if (t.isEmpty()) continue
                    when (tag) {
                        "friendlyName" -> if (name == "Media server") name = t
                        "serviceType" -> isCd = t.contains("ContentDirectory")
                        "controlURL" -> if (isCd && control == null) control = t
                    }
                }
            }
        }
        val c = control ?: return null
        return DlnaDevice(name, URL(URL(location), c).toString())
    }

    suspend fun browse(dev: DlnaDevice, objectId: String): List<DlnaEntry> = withContext(Dispatchers.IO) {
        val body = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><ObjectID>$objectId</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>500</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>"""
        val c = URL(dev.controlUrl).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 5000; c.readTimeout = 10000
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        c.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
        c.outputStream.use { it.write(body.toByteArray()) }
        val resp = c.inputStream.bufferedReader().readText()

        var didl = ""
        run {
            val p = Xml.newPullParser(); p.setInput(StringReader(resp))
            var inResult = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) inResult = p.name == "Result"
                else if (p.eventType == XmlPullParser.TEXT && inResult) didl += p.text
                else if (p.eventType == XmlPullParser.END_TAG && p.name == "Result") inResult = false
            }
        }
        val out = mutableListOf<DlnaEntry>()
        val p = Xml.newPullParser(); p.setInput(StringReader(didl))
        var id = ""; var title = ""; var url: String? = null; var container = false; var inItem = false; var cur = ""
        var resProto = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    cur = p.name
                    if (cur == "container" || cur == "item") {
                        inItem = true; container = cur == "container"
                        id = p.getAttributeValue(null, "id") ?: ""; title = ""; url = null
                    } else if (cur == "res") resProto = p.getAttributeValue(null, "protocolInfo") ?: ""
                }
                XmlPullParser.TEXT -> if (inItem) {
                    val t = p.text
                    if (cur == "dc:title" || cur == "title") title += t
                    else if (cur == "res" && url == null && t.startsWith("http") && resProto.contains("video", true)) url = t.trim()
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "container" || p.name == "item") {
                        inItem = false
                        if (container || url != null) out += DlnaEntry(id, title.trim(), container, url)
                    }
                    cur = ""
                }
            }
        }
        out
    }
}
