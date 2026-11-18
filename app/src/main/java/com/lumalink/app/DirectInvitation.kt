package com.lumalink.app

import org.json.JSONObject

internal data class DirectInvitation(val id: String, val key: ByteArray, val port: Int,
    val owner: String, val name: String, val size: Long, val hash: String) {
    fun encode(): String = JSONObject().put("transport", "lumalink-wifi-direct").put("v", 2)
        .put("id", id).put("key", Codec.b64(key)).put("port", port).put("owner", owner)
        .put("name", name).put("size", size).put("hash", hash).toString()
    companion object {
        fun parse(raw: String): DirectInvitation {
            require(raw.length <= 2048) { "Invalid Wi-Fi Direct QR" }
            val o = JSONObject(raw)
            require(o.getString("transport") == "lumalink-wifi-direct" && o.getInt("v") == 2) { "Update LumaLink on both phones" }
            val result = DirectInvitation(o.getString("id"), Codec.un64(o.getString("key")),
                o.getInt("port"), o.getString("owner"), o.getString("name"), DirectOffer.positiveLong(o, "size"), o.getString("hash"))
            require(result.id.matches(Regex("[a-zA-Z0-9-]{8,64}")) && result.key.size == 32 && result.port in 1..65535)
            require(result.owner.length in 1..100 && result.name.length in 1..100 && result.size > 0)
            require(result.hash.matches(Regex("[0-9a-f]{64}")))
            return result
        }
    }
}
