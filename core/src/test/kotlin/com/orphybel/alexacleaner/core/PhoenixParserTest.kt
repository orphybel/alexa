package com.orphybel.alexacleaner.core

import com.orphybel.alexacleaner.core.api.PhoenixParser
import com.orphybel.alexacleaner.core.model.Reachability
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

object Samples {
    val NETWORK_DETAIL = """
    {
      "locationDetails": {
        "locationDetails": {
          "Default_Location": {
            "locationId": "Default_Location",
            "amazonBridgeDetails": {
              "amazonBridgeDetails": {
                "LambdaBridge_SmartThings/amzn1.ask.skill.11111111-2222-3333-4444-555555555555": {
                  "amazonBridgeIdentifier": {"amazonBridgeDSN": "x", "amazonBridgeType": "LambdaBridge"},
                  "applianceDetails": {
                    "applianceDetails": {
                      "SKILL_aaa": {
                        "applianceId": "SKILL_aaa",
                        "entityId": "e-1",
                        "friendlyName": "Lampe Salon",
                        "manufacturerName": "SmartThings",
                        "friendlyDescription": "Lampe via SmartThings",
                        "modelName": "ST-1",
                        "applianceTypes": ["LIGHT"],
                        "isEnabled": true,
                        "capabilities": [{"interfaceName": "Alexa.PowerController"}],
                        "applianceNetworkState": {"reachability": "UNREACHABLE", "lastSeenAt": 1700000000000, "createdAt": 1690000000000},
                        "driverIdentity": {"namespace": "SKILL", "identifier": "amzn1.ask.skill.11111111-2222-3333-4444-555555555555"}
                      },
                      "SKILL_bbb": {
                        "applianceId": "SKILL_bbb",
                        "friendlyName": "Lampe Salon",
                        "manufacturerName": "SmartThings",
                        "applianceTypes": ["LIGHT"],
                        "isEnabled": true,
                        "applianceNetworkState": {"reachability": "REACHABLE"}
                      }
                    }
                  }
                },
                "LambdaBridge_AAA/SonarCloudService": {
                  "applianceDetails": {
                    "applianceDetails": {
                      "AAA_SonarCloudService_c1": {
                        "applianceId": "AAA_SonarCloudService_c1",
                        "friendlyName": "Echo Salon",
                        "manufacturerName": "Amazon",
                        "applianceTypes": ["ALEXA_VOICE_ENABLED"],
                        "isEnabled": true,
                        "applianceNetworkState": {"reachability": "REACHABLE"}
                      }
                    }
                  }
                },
                "Hue_Bridge_001788": {
                  "applianceDetails": {
                    "applianceDetails": {
                      "hue-1": {
                        "applianceId": "hue-1",
                        "friendlyName": "Cuisine",
                        "applianceTypes": ["LIGHT"],
                        "isEnabled": false
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    """.trimIndent()

    fun phoenixBody(): String = """{"networkDetail": ${JsonPrimitive(NETWORK_DETAIL)}}"""

    const val DEVICES_V2 = """{"devices":[
      {"accountName":"Echo Salon","serialNumber":"G090","deviceType":"A3S5BH2HU6VAYF","online":true,"deviceFamily":"ECHO","softwareVersion":"123"},
      {"accountName":"Fire TV","serialNumber":"G091","deviceType":"AKPGW064GI9HE","online":false,"deviceFamily":"FIRE_TV"}
    ]}"""
}

class PhoenixParserTest {

    @Test
    fun `parses appliances from nested networkDetail string`() {
        val devices = PhoenixParser.parseSmartHome(Samples.phoenixBody())
        assertEquals(4, devices.size)

        val a = devices.first { it.applianceId == "SKILL_aaa" }
        assertEquals("Lampe Salon", a.friendlyName)
        assertEquals(Reachability.UNREACHABLE, a.reachability)
        assertEquals("SmartThings", a.source)
        assertEquals("amzn1.ask.skill.11111111-2222-3333-4444-555555555555", a.skillId)
        assertEquals(1700000000000, a.lastSeenAt)
        assertEquals(1690000000000, a.createdAt)
        assertEquals(1, a.capabilityCount)
        assertTrue(a.isEnabled)
        assertNotNull(a.raw)

        val amazon = devices.first { it.applianceId == "AAA_SonarCloudService_c1" }
        assertEquals("Amazon", amazon.source)
        assertEquals(Reachability.REACHABLE, amazon.reachability)

        val hue = devices.first { it.applianceId == "hue-1" }
        assertEquals(Reachability.UNKNOWN, hue.reachability)
        assertEquals("Hue_Bridge_001788", hue.source)
        assertEquals(false, hue.isEnabled)
    }

    @Test
    fun `accepts networkDetail already expanded as an object`() {
        val devices = PhoenixParser.parseSmartHome("""{"networkDetail": ${Samples.NETWORK_DETAIL}}""")
        assertEquals(4, devices.size)
    }

    @Test
    fun `parses echo devices`() {
        val echos = PhoenixParser.parseEchoDevices(Samples.DEVICES_V2)
        assertEquals(2, echos.size)
        assertEquals("Echo Salon", echos[0].accountName)
        assertTrue(echos[0].online)
        assertEquals("FIRE_TV", echos[1].deviceFamily)
        assertEquals(false, echos[1].online)
    }
}
