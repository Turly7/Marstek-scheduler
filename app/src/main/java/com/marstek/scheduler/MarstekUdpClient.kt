package com.marstek.scheduler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Client bas niveau pour le protocole JSON-RPC / UDP decrit dans
 * "Marstek Device Open API (Rev 2.0)".
 *
 * IMPORTANT SECURITE :
 * - Ce protocole ne comporte AUCUNE authentification ni chiffrement (JSON en clair sur UDP).
 *   Il ne doit JAMAIS transiter sur autre chose que le reseau local de confiance
 *   (pas d'exposition sur Internet, pas de redirection de port NAT).
 * - Toute app tierce presente sur le meme Wi-Fi pourrait techniquement envoyer les
 *   memes commandes : la securite repose entierement sur la securite du reseau local
 *   (Wi-Fi avec mot de passe fort, pas de reseau invite partage, etc.).
 */
object MarstekUdpClient {

    private const val DEFAULT_TIMEOUT_MS = 3000
    private const val BUFFER_SIZE = 4096

    data class RpcResponse(
        val raw: String,
        val src: String?,
        val result: JSONObject?,
        val error: JSONObject?
    )

    /**
     * Envoie une commande JSON-RPC et attend une reponse unicast du device.
     */
    suspend fun send(
        ip: String,
        port: Int,
        method: String,
        params: JSONObject,
        requestId: Int = 1,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): Result<RpcResponse> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject()
                .put("id", requestId)
                .put("method", method)
                .put("params", params)
                .toString()

            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val address = InetAddress.getByName(ip)
                val sendPacket = DatagramPacket(
                    payload.toByteArray(Charsets.UTF_8),
                    payload.toByteArray(Charsets.UTF_8).size,
                    address,
                    port
                )
                socket.send(sendPacket)

                val buffer = ByteArray(BUFFER_SIZE)
                val receivePacket = DatagramPacket(buffer, buffer.size)
                socket.receive(receivePacket)

                val respText = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)
                val json = JSONObject(respText)
                Result.success(
                    RpcResponse(
                        raw = respText,
                        src = json.optString("src", null),
                        result = json.optJSONObject("result"),
                        error = json.optJSONObject("error")
                    )
                )
            }
        } catch (e: SocketTimeoutException) {
            Result.failure(Exception("Pas de reponse du device $ip:$port (timeout)"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Decouverte des devices Marstek sur le reseau local via broadcast UDP
     * (Marstek.GetDevice avec ble_mac="0"), tel que decrit en section 2.2.2 du protocole.
     *
     * Necessite CHANGE_WIFI_MULTICAST_STATE + d'etre connecte au bon Wi-Fi.
     */
    suspend fun discoverDevices(
        port: Int,
        listenWindowMs: Int = 3000
    ): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredDevice>()
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.broadcast = true
                socket.soTimeout = 500
                socket.bind(InetSocketAddress(0))

                val payload = JSONObject()
                    .put("id", 0)
                    .put("method", "Marstek.GetDevice")
                    .put("params", JSONObject().put("ble_mac", "0"))
                    .toString()
                    .toByteArray(Charsets.UTF_8)

                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                socket.send(DatagramPacket(payload, payload.size, broadcastAddr, port))

                val deadline = System.currentTimeMillis() + listenWindowMs
                val buffer = ByteArray(BUFFER_SIZE)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val json = JSONObject(text)
                        val result = json.optJSONObject("result") ?: continue
                        val ip = result.optString("ip").takeIf { it.isNotBlank() } ?: continue
                        val src = json.optString("src", ip)
                        found[ip] = DiscoveredDevice(
                            src = src,
                            deviceModel = result.optString("device", "?"),
                            ip = ip,
                            bleMac = result.optString("ble_mac", ""),
                            wifiMac = result.optString("wifi_mac", "")
                        )
                    } catch (e: SocketTimeoutException) {
                        // on continue d'ecouter jusqu'a la deadline
                    }
                }
            }
        } catch (e: Exception) {
            // Decouverte best-effort : on retourne ce qui a ete trouve avant l'erreur
        }
        found.values.toList()
    }

    data class DiscoveredDevice(
        val src: String,
        val deviceModel: String,
        val ip: String,
        val bleMac: String,
        val wifiMac: String
    )

    // --- Helpers pour les commandes utilisees par l'app ---

    suspend fun setManualMode(
        ip: String,
        port: Int,
        startTime: String,
        endTime: String,
        power: Int,
        weekSet: Int = 127,
        timeNum: Int = 1
    ): Result<RpcResponse> {
        val manualCfg = JSONObject()
            .put("time_num", timeNum)
            .put("start_time", startTime)
            .put("end_time", endTime)
            .put("week_set", weekSet)
            .put("power", power)
            .put("enable", 1)
        val config = JSONObject().put("mode", "Manual").put("manual_cfg", manualCfg)
        val params = JSONObject().put("id", 0).put("config", config)
        return send(ip, port, "ES.SetMode", params)
    }

    suspend fun setAutoMode(ip: String, port: Int): Result<RpcResponse> {
        val config = JSONObject().put("mode", "Auto").put("auto_cfg", JSONObject().put("enable", 1))
        val params = JSONObject().put("id", 0).put("config", config)
        return send(ip, port, "ES.SetMode", params)
    }

    suspend fun getMode(ip: String, port: Int): Result<RpcResponse> {
        val params = JSONObject().put("id", 0)
        return send(ip, port, "ES.GetMode", params)
    }
}
