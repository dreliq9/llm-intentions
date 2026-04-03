package com.taichi.trading

import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant

/**
 * Exchange address registry. Tracks known CEX hot wallets for exchange flow analysis.
 * Tiers: seed (hardcoded) → confirmed (manual) → candidates (auto) → rejected.
 * Persisted as JSON file.
 */
class AddressStore(private val dataDir: File) {

    private val storeFile = File(dataDir, "exchange_addresses.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // Seed addresses — well-known CEX hot wallets (immutable)
    private val seedAddresses = mapOf(
        "0x28c6c06298d514db089934071355e5743bf21d60" to "Binance",
        "0x21a31ee1afc51d94c2efccaa2092ad1028285549" to "Binance",
        "0xdfd5293d8e347dfe59e90efd55b2956a1343963d" to "Binance",
        "0x56eddb7aa87536c09ccc2793473599fd21a8b17f" to "Binance",
        "0xf977814e90da44bfa03b6295a0616a897441acec" to "Binance",
        "0xa9d1e08c7793af67e9d92fe308d5697fb81d3e43" to "Coinbase",
        "0x503828976d22510aad0201ac7ec88293211d23da" to "Coinbase",
        "0x71660c4005ba85c37ccec55d0c4493e66fe775d3" to "Coinbase",
        "0x2910543af39aba0cd09dbb2d50200b3e800a63d2" to "Kraken",
        "0x53d284357ec70ce289d6d64134dfac8e511c8a3d" to "Kraken",
        "0x6cc5f688a315f3dc28a7781717a9a798a59fda7b" to "OKX",
        "0x98ec059dc3adfbdd63429227d09cb6473b7a10a5" to "OKX",
        "0xf89d7b9c864f589bbf53a82105107622b35eaa40" to "Bybit",
        "0xd6216fc19db775df9774a6e33526131da7d19a2c" to "KuCoin",
        "0x0d0707963952f2fba59dd06f2b425ace40b492fe" to "Gate.io",
        "0x1db92e2eebc8e0c075a02bea49a2935bcd2dfcf4" to "HTX",
        "0x46340b20830761efd32832a74d7169b29feb9758" to "HTX",
    )

    // In-memory state
    private var confirmed = mutableMapOf<String, AddressEntry>()
    private var candidates = mutableMapOf<String, CandidateEntry>()
    private var rejected = mutableSetOf<String>()

    init {
        dataDir.mkdirs()
        load()
    }

    fun isExchange(address: String): Boolean {
        val norm = address.lowercase()
        return norm in seedAddresses || norm in confirmed
    }

    fun getLabel(address: String): String? {
        val norm = address.lowercase()
        return seedAddresses[norm] ?: confirmed[norm]?.label
    }

    fun getAllExchangeAddresses(): Set<String> {
        return seedAddresses.keys + confirmed.keys
    }

    fun addAddress(address: String, label: String, source: String = "manual"): JsonObject {
        val norm = address.lowercase()
        if (norm in seedAddresses) return buildJsonObject {
            put("status", "exists")
            put("message", "Address is already a seed address (${seedAddresses[norm]})")
        }
        confirmed[norm] = AddressEntry(label, source, Instant.now().toString())
        rejected.remove(norm)
        save()
        return buildJsonObject {
            put("status", "success")
            put("message", "Added $label ($norm) as confirmed exchange address")
        }
    }

    fun removeAddress(address: String): JsonObject {
        val norm = address.lowercase()
        if (norm in seedAddresses) return buildJsonObject {
            put("status", "error")
            put("message", "Cannot remove seed addresses")
        }
        confirmed.remove(norm)
        save()
        return buildJsonObject {
            put("status", "success")
            put("message", "Removed $norm from confirmed addresses")
        }
    }

    fun addCandidate(address: String, score: Double, reason: String): JsonObject {
        val norm = address.lowercase()
        if (norm in seedAddresses || norm in confirmed || norm in rejected) {
            return buildJsonObject { put("status", "skipped") }
        }
        candidates[norm] = CandidateEntry(score, reason, Instant.now().toString())
        save()
        return buildJsonObject { put("status", "success") }
    }

    fun confirmCandidate(address: String, label: String): JsonObject {
        val norm = address.lowercase()
        val candidate = candidates.remove(norm) ?: return buildJsonObject {
            put("status", "error")
            put("message", "Not a candidate: $norm")
        }
        confirmed[norm] = AddressEntry(label, "promoted_candidate", Instant.now().toString())
        save()
        return buildJsonObject { put("status", "success") }
    }

    fun rejectCandidate(address: String): JsonObject {
        val norm = address.lowercase()
        candidates.remove(norm)
        rejected.add(norm)
        save()
        return buildJsonObject { put("status", "success") }
    }

    fun getSummary(): JsonObject {
        return buildJsonObject {
            put("seed_count", seedAddresses.size)
            put("confirmed_count", confirmed.size)
            put("candidate_count", candidates.size)
            put("rejected_count", rejected.size)
            put("total_known", seedAddresses.size + confirmed.size)
            put("seed_exchanges", buildJsonObject {
                val byExchange = seedAddresses.values.groupBy { it }
                byExchange.forEach { (exchange, addrs) -> put(exchange, addrs.size) }
            })
            if (candidates.isNotEmpty()) {
                put("pending_candidates", JsonArray(candidates.entries
                    .sortedByDescending { it.value.score }
                    .take(10)
                    .map { (addr, entry) ->
                        buildJsonObject {
                            put("address", addr)
                            put("score", entry.score)
                            put("reason", entry.reason)
                        }
                    }
                ))
            }
        }
    }

    private data class AddressEntry(val label: String, val source: String, val addedAt: String)
    private data class CandidateEntry(val score: Double, val reason: String, val discoveredAt: String)

    private fun save() {
        val obj = buildJsonObject {
            put("confirmed", buildJsonObject {
                confirmed.forEach { (addr, entry) ->
                    put(addr, buildJsonObject {
                        put("label", entry.label)
                        put("source", entry.source)
                        put("added_at", entry.addedAt)
                    })
                }
            })
            put("candidates", buildJsonObject {
                candidates.forEach { (addr, entry) ->
                    put(addr, buildJsonObject {
                        put("score", entry.score)
                        put("reason", entry.reason)
                        put("discovered_at", entry.discoveredAt)
                    })
                }
            })
            put("rejected", JsonArray(rejected.map { JsonPrimitive(it) }))
        }
        storeFile.writeText(json.encodeToString(JsonObject.serializer(), obj))
    }

    private fun load() {
        if (!storeFile.exists()) return
        try {
            val obj = json.parseToJsonElement(storeFile.readText()).jsonObject

            obj["confirmed"]?.jsonObject?.forEach { (addr, data) ->
                val d = data.jsonObject
                confirmed[addr] = AddressEntry(
                    d["label"]?.jsonPrimitive?.content ?: "",
                    d["source"]?.jsonPrimitive?.content ?: "manual",
                    d["added_at"]?.jsonPrimitive?.content ?: ""
                )
            }

            obj["candidates"]?.jsonObject?.forEach { (addr, data) ->
                val d = data.jsonObject
                candidates[addr] = CandidateEntry(
                    d["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    d["reason"]?.jsonPrimitive?.content ?: "",
                    d["discovered_at"]?.jsonPrimitive?.content ?: ""
                )
            }

            obj["rejected"]?.jsonArray?.forEach { rejected.add(it.jsonPrimitive.content) }
        } catch (_: Exception) { /* corrupted file, start fresh */ }
    }
}
