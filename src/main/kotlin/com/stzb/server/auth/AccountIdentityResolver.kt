package com.stzb.server.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest

data class AccountIdentity(
    val accountKey: String,
    val displayId: String,
)

/**
 * The SDK platform request carries the only stable account identity available
 * before the game-server login. Account keys are hashed before persistence so
 * SDK identifiers never become file names.
 */
object AccountIdentityResolver {
    private val mapper = jacksonObjectMapper()

    fun fromPlatformLoginRequest(bodyText: String): AccountIdentity? {
        val outer = runCatching { mapper.readTree(bodyText) }.getOrNull()
            ?.takeIf { it.isArray }
            ?: return null
        val credentials = outer.get(0)?.let { node ->
            when {
                node.isTextual -> runCatching { mapper.readTree(node.asText()) }.getOrNull()
                node.isObject -> node
                else -> null
            }
        } ?: return null
        return credentials.text("sdkuid")?.let { canonical("sdkuid", it) }
            ?: credentials.text("userid")?.let { canonical("userid", it) }
    }

    fun fromGameLoginRequest(bodyText: String): AccountIdentity? {
        val outer = runCatching { mapper.readTree(bodyText) }.getOrNull()
            ?.takeIf { it.isArray }
            ?: return null
        return outer.get(0)
            ?.asText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { canonical("passport", it) }
    }

    private fun canonical(source: String, rawId: String): AccountIdentity {
        val normalized = rawId.trim()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$source:$normalized".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return AccountIdentity(
            accountKey = "$source-$digest",
            displayId = "$source:$normalized",
        )
    }

    private fun com.fasterxml.jackson.databind.JsonNode.text(field: String): String? =
        get(field)
            ?.asText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
