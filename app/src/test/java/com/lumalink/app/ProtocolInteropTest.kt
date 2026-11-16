package com.lumalink.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** The same frozen vectors are verified independently by the Python lab. */
class ProtocolInteropTest {
    @Test fun sharedPythonVectorsMatchKotlinCodecAndReceiver() {
        val raw = javaClass.classLoader!!.getResource("protocol-v2.json")!!.readText(Charsets.UTF_8)
        val fixture = JSONObject(raw)
        val data = Base64.getDecoder().decode(fixture.getString("file_base64"))
        val sender = Sender("image-光-🚀.bin", data)
        val receiver = Receiver()
        val vectors = fixture.getJSONArray("vectors")
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val packet = vector.getJSONObject("packet")
            val seq = packet.getInt("q")
            val selected = vector.getJSONArray("indices")
            assertArrayEquals(IntArray(selected.length()) { selected.getInt(it) }, Codec.indices(sender.count, seq))
            assertEquals(packet.getString("d"), JSONObject(sender.frame(seq)).getString("d"))
            assertEquals(packet.getString("h"), sender.sha)
            assertEquals(packet.getString("c"), Codec.packetHash(packet))
            receiver.accept(packet.toString())
        }
        assertArrayEquals(data, receiver.complete)
    }
}
