package com.metavirtuoso.conjunction.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

class P2pJsonTest {
    @Test
    fun canonicalEscapes() {
        val m = linkedMapOf<String, Any?>("b" to "Zoë \"Z\" \\ 星\n\t\u0001\b\u000c/😀\u2028", "a" to listOf(1L, -2L, null, true), "n" to null)
        assertEquals("{\"b\":\"Zoë \\\"Z\\\" \\\\ 星\\n\\t\\u0001\\b\\f/😀\u2028\",\"a\":[1,-2,null,true],\"n\":null}", WireJson.write(m))
        assertEquals(m, WireJson.parse(WireJson.writeBytes(m)))
    }

    @Test
    fun strictParsing() {
        for (bad in listOf("{\"a\":1.0}", "{\"a\":1e3}", "{\"a\":1,\"a\":2}", "{\"a\":01}", "{\"a\":\"\\ud800\"}",
                "{\"a\":99999999999999999999}", "{\"a\":1} x", "{\"a\":\"\u0001\"}", "[", "{\"a\":NaN}", "{\"a\":\"\\x\"}")) {
            expect(P2pErrorCode.MALFORMED) { WireJson.parse(bad) }
        }
        expect(P2pErrorCode.MALFORMED) { WireJson.parse(byteArrayOf(0x22, 0xC3.toByte(), 0x22)) } // invalid UTF-8
        assertEquals(mapOf("a" to listOf(1L, "x")), WireJson.parse(" { \"a\" : [ 1 , \"x\" ] } "))
        assertEquals("\uD83D\uDE00", WireJson.parse("\"\\ud83d\\ude00\""))
    }

    @Test
    fun wireCompatIsExactForEveryCentidegree() {
        for (c in 0 until 36_000) {
            assertEquals(c, com.metavirtuoso.conjunction.core.compat.Compatibility.centidegrees(c / 100.0))
        }
    }
}
