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

    /** GraphQL validation errors, if the body carries any (`{"errors":[{"message":...}]}`). */
    fun graphQlErrors(body: String): List<String> = try {
        val root = json.parseToJsonElement(body).jsonObject
        (root["errors"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("message") } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    /** Parses the `CustomerSmartHome` GraphQL response (`data.endpoints.items`). */
    fun parseGraphQlEndpoints(body: String): List<SmartHomeDevice> {
        val root = json.parseToJsonElement(body).jsonObject
        val items = root["data"]?.jsonObject?.get("endpoints")?.jsonObject?.get("items") as? JsonArray ?: return emptyList()
        val found = LinkedHashMap<String, SmartHomeDevice>()
        for (el in items) {
            val item = el as? JsonObject ?: continue
            val legacy = item["legacyAppliance"] as? JsonObject
            val endpointId = item.str("endpointId") ?: item.str("id")
            val applianceId = legacy?.str("applianceId") ?: endpointId ?: continue
            val name = item.str("friendlyName")?.takeIf { it.isNotBlank() }
                ?: legacy?.str("friendlyName")?.takeIf { it.isNotBlank() }
                ?: "(sans nom)"
            val netState = legacy?.get("applianceNetworkState") as? JsonObject
            val reachability = when (netState?.str("reachability")?.uppercase()) {
                "REACHABLE" -> Reachability.REACHABLE
                "UNREACHABLE" -> Reachability.UNREACHABLE
                else -> Reachability.UNKNOWN
            }
            val categories = (item["displayCategories"] as? JsonObject)
            val types = (legacy?.get("applianceTypes") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.takeIf { it.isNotEmpty() }
                ?: ((categories?.get("all") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("value") }?.takeIf { it.isNotEmpty() })
                ?: listOfNotNull((categories?.get("primary") as? JsonObject)?.str("value"))
            val driver = legacy?.get("driverIdentity") as? JsonObject
            val driverId = driver?.str("identifier")
            val skillId = listOfNotNull(driverId, legacy?.str("connectedVia"), applianceId)
                .firstNotNullOfOrNull { SKILL_ID.find(it)?.value }
            val manufacturer = legacy?.str("manufacturerName")?.takeIf { it.isNotBlank() }
            val enabled = legacy?.bool("isEnabled")
                ?: item.str("enablement")?.let { !it.equals("DISABLED", true) }
                ?: true
            val entityId = legacy?.str("entityId")
                ?: (item["legacyIdentifiers"] as? JsonObject)?.get("chrsIdentifier")?.let { (it as? JsonObject)?.str("entityId") }
            val capabilities = (legacy?.get("capabilities") as? JsonArray)?.size ?: 0
            val source = deriveSource(manufacturer, null, driver?.str("namespace"), driverId, legacy?.str("connectedVia"))
            val device = SmartHomeDevice(
                applianceId = applianceId,
                entityId = entityId,
                friendlyName = name,
                manufacturerName = manufacturer,
                friendlyDescription = legacy?.str("friendlyDescription"),
                modelName = legacy?.str("modelName"),
                applianceTypes = types,
                isEnabled = enabled,
                reachability = reachability,
                connectedVia = legacy?.str("connectedVia")?.takeIf { it.isNotBlank() },
                source = source,
                skillId = skillId,
                bridgeKey = null,
                createdAt = netState?.long("createdAt"),
                lastSeenAt = netState?.long("lastSeenAt"),
                capabilityCount = capabilities,
                endpointId = endpointId,
                raw = item.toString(),
            )
            if (!found.containsKey(device.applianceId)) found[device.applianceId] = device
        }
        return found.values.toList()
    }

    /**
     * Parses `POST /api/phoenix/state` and returns entityId → reachability. Entities listed in
     * `errors` with `ENDPOINT_UNREACHABLE` are offline; entities with states are online.
     */
    fun parseStateReachability(body: String): Map<String, Reachability> {
        val root = json.parseToJsonElement(body).jsonObject
        val result = HashMap<String, Reachability>()
        (root["deviceStates"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val id = (o["entity"] as? JsonObject)?.str("entityId") ?: return@forEach
            val states = (o["capabilityStates"] as? JsonArray)?.size ?: 0
            val error = o.str("error") ?: (o["error"] as? JsonObject)?.str("code")
            result[id] = when {
                error != null && error.contains("UNREACHABLE", true) -> Reachability.UNREACHABLE
                states > 0 || error == null -> Reachability.REACHABLE
                else -> Reachability.UNKNOWN
            }
        }
        (root["errors"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val id = (o["entity"] as? JsonObject)?.str("entityId") ?: return@forEach
            val code = o.str("code") ?: o.str("message") ?: ""
            result[id] = if (code.contains("UNREACHABLE", true) || code.contains("OFFLINE", true)) Reachability.UNREACHABLE else Reachability.UNKNOWN
        }
        return result
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
