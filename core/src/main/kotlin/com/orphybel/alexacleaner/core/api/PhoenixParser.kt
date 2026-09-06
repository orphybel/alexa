package com.orphybel.alexacleaner.core.api

import com.orphybel.alexacleaner.core.model.EchoDevice
import com.orphybel.alexacleaner.core.model.Reachability
import com.orphybel.alexacleaner.core.model.SmartHomeDevice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parses the private Alexa endpoints used by the app.
 *
 * The `/api/phoenix` payload is a JSON document whose `networkDetail` field is itself a JSON
 * *string*. Its structure has changed several times over the years, so instead of relying on
 * exact paths we walk the whole tree and pick every object that looks like an appliance.
 */
object PhoenixParser {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun parseSmartHome(body: String): List<SmartHomeDevice> {
        val root = json.parseToJsonElement(body)
        val networkDetail = (root as? JsonObject)?.get("networkDetail")
        val tree: JsonElement = when (networkDetail) {
            null -> root
            is JsonPrimitive -> networkDetail.contentOrNull?.let { json.parseToJsonElement(it) } ?: root
            else -> networkDetail
        }
        val found = LinkedHashMap<String, SmartHomeDevice>()
        walk(tree, bridgeKey = null, found = found)
        return found.values.toList()
    }

    private fun walk(element: JsonElement, bridgeKey: String?, found: MutableMap<String, SmartHomeDevice>) {
        when (element) {
            is JsonObject -> {
                if (looksLikeAppliance(element)) {
                    val device = toDevice(element, bridgeKey)
                    if (device != null && !found.containsKey(device.applianceId)) found[device.applianceId] = device
                    return
                }
                for ((key, value) in element) {
                    if (key == "amazonBridgeDetails" && value is JsonObject) {
                        // {"amazonBridgeDetails": {"amazonBridgeDetails": {"<bridge key>": {...}}}}
                        val inner = value["amazonBridgeDetails"] as? JsonObject ?: value
                        for ((bk, bridge) in inner) walk(bridge, bk, found)
                    } else {
                        walk(value, bridgeKey, found)
                    }
                }
            }
            is JsonArray -> element.forEach { walk(it, bridgeKey, found) }
            else -> Unit
        }
    }

    private fun looksLikeAppliance(o: JsonObject): Boolean =
        o["applianceId"] is JsonPrimitive && (o.containsKey("friendlyName") || o.containsKey("applianceTypes"))

    private fun toDevice(o: JsonObject, bridgeKey: String?): SmartHomeDevice? {
        val applianceId = o.str("applianceId") ?: return null
        val name = o.str("friendlyName")?.takeIf { it.isNotBlank() } ?: "(sans nom)"
        val netState = o["applianceNetworkState"] as? JsonObject
        val reachability = when (netState?.str("reachability")?.uppercase()) {
            "REACHABLE" -> Reachability.REACHABLE
            "UNREACHABLE" -> Reachability.UNREACHABLE
            else -> Reachability.UNKNOWN
        }
        val driver = o["driverIdentity"] as? JsonObject
        val driverNs = driver?.str("namespace")
        val driverId = driver?.str("identifier")
        val skillId = listOfNotNull(driverId, bridgeKey)
            .firstNotNullOfOrNull { SKILL_ID.find(it)?.value }
        val manufacturer = o.str("manufacturerName")?.takeIf { it.isNotBlank() }
        val types = (o["applianceTypes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        val capabilities = (o["capabilities"] as? JsonArray)?.size ?: 0
        val source = deriveSource(manufacturer, bridgeKey, driverNs, driverId, o.str("connectedVia"))
        return SmartHomeDevice(
            applianceId = applianceId,
            entityId = o.str("entityId"),
            friendlyName = name,
            manufacturerName = manufacturer,
            friendlyDescription = o.str("friendlyDescription"),
            modelName = o.str("modelName"),
            applianceTypes = types,
            isEnabled = o.bool("isEnabled") ?: true,
            reachability = reachability,
            connectedVia = o.str("connectedVia")?.takeIf { it.isNotBlank() },
            source = source,
            skillId = skillId,
            bridgeKey = bridgeKey,
            createdAt = netState?.long("createdAt") ?: o.long("createdAt"),
            lastSeenAt = netState?.long("lastSeenAt") ?: o.long("lastSeenAt"),
            capabilityCount = capabilities,
            raw = o.toString(),
        )
    }

    /**
     * Best-effort human readable origin. Bridge keys look like
     * `LambdaBridge_SmartThings/xxx`, `LambdaBridge_AAA/SonarCloudService`, `Hue_Bridge_…`,
     * `AlexaBridge_…`. Fall back to the manufacturer.
     */
    private fun deriveSource(manufacturer: String?, bridgeKey: String?, driverNs: String?, driverId: String?, connectedVia: String?): String {
        val fromBridge = bridgeKey?.let { key ->
            val stripped = key.removePrefix("LambdaBridge_").substringBefore('/')
            when {
                stripped.isBlank() -> null
                stripped.equals("AAA", true) -> "Amazon (AAA)"
                stripped.startsWith("amzn1.ask.skill", true) -> null
                else -> stripped
            }
        }
        val fromDriver = when {
            driverNs.equals("SKILL", true) -> null
            driverNs.equals("AlexaBridge", true) -> "Alexa (hub intégré)"
            else -> null
        }
        return manufacturer ?: fromBridge ?: fromDriver ?: connectedVia?.takeIf { it.isNotBlank() } ?: "Inconnu"
    }

    fun parseEchoDevices(body: String): List<EchoDevice> {
        val root = json.parseToJsonElement(body).jsonObject
        val devices = root["devices"]?.jsonArray ?: return emptyList()
        return devices.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val serial = o.str("serialNumber") ?: return@mapNotNull null
            EchoDevice(
                serialNumber = serial,
                deviceType = o.str("deviceType") ?: "",
                accountName = o.str("accountName") ?: serial,
                online = o.bool("online") ?: false,
                deviceFamily = o.str("deviceFamily"),
                softwareVersion = o.str("softwareVersion"),
            )
        }
    }

    private val SKILL_ID = Regex("amzn1\\.ask\\.skill\\.[0-9a-fA-F-]+")

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { p ->
        p.longOrNull ?: p.contentOrNull?.toLongOrNull()
    }
}
