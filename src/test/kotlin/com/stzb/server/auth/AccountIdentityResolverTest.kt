package com.stzb.server.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AccountIdentityResolverTest {
    @Test
    fun `platform identity prefers sdkuid over userid`() {
        val preferred = AccountIdentityResolver.fromPlatformLoginRequest(
            """["{\"sdkuid\":\"alice\",\"userid\":\"fallback\"}",0,"",0]""",
        )
        val sameSdkUser = AccountIdentityResolver.fromPlatformLoginRequest(
            """["{\"sdkuid\":\"alice\"}",0,"",0]""",
        )

        assertEquals("sdkuid:alice", preferred?.displayId)
        assertEquals(preferred, sameSdkUser)
    }

    @Test
    fun `platform identity falls back to userid`() {
        val identity = AccountIdentityResolver.fromPlatformLoginRequest(
            """["{\"userid\":\"device-user\"}",0,"",0]""",
        )

        assertEquals("userid:device-user", identity?.displayId)
    }

    @Test
    fun `game login passport is only used as fallback`() {
        val identity = AccountIdentityResolver.fromGameLoginRequest(
            """["passport-alpha","token",10001]""",
        )
        val another = AccountIdentityResolver.fromGameLoginRequest(
            """["passport-beta","token",10001]""",
        )

        assertEquals("passport:passport-alpha", identity?.displayId)
        assertNotEquals(identity, another)
    }

    @Test
    fun `blank or malformed requests have no usable identity`() {
        assertNull(AccountIdentityResolver.fromPlatformLoginRequest("""["{}",0,"",0]"""))
        assertNull(AccountIdentityResolver.fromPlatformLoginRequest("not-json"))
        assertNull(AccountIdentityResolver.fromGameLoginRequest("""[" ","token",1]"""))
        assertNull(AccountIdentityResolver.fromGameLoginRequest("{}"))
    }
}
