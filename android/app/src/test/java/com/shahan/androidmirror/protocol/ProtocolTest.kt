package com.shahan.androidmirror.protocol

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun `accepts bounded nonempty envelope`() { assertTrue(validEnvelope("{\"version\":1}")) }
    @Test fun `rejects oversized or empty envelope`() { assertFalse(validEnvelope("")); assertFalse(validEnvelope("x".repeat(Wire.MAX_BYTES + 1))) }
    @Test fun `wire ids are distinct and expected length`() { val a = Wire.id(); assertEquals(32, a.length); assertNotEquals(a, Wire.id()) }
}
