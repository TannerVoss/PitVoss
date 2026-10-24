package com.pitvoss.app

import org.junit.Assert.*
import org.junit.Test

class LocalNetworkTest {
 @Test fun acceptsPrivateAndLinkLocalAddresses() {
  listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.153", "169.254.1.2").forEach { assertTrue(it, LocalNetwork.isAllowedHost(it)) }
 }
 @Test fun rejectsPublicAmbiguousOrMalformedAddresses() {
  listOf("8.8.8.8", "1.1.1.1", "127.0.0.1", "0.0.0.0", "172.15.0.1", "172.32.0.1", "192.169.1.1", "192.168.999.1", "192.168.1.1/", "192.168.001.1", "http://192.168.1.1", "192.168.1.1@evil.test", "localhost", "::1", "10.0.0.1\n").forEach { assertFalse(it, LocalNetwork.isAllowedHost(it)) }
 }
 @Test fun boundsResponseMemory() {
  assertEquals(262144, LocalNetwork.readBounded(ByteArray(262144) { 65 }.inputStream()).length)
  try { LocalNetwork.readBounded(ByteArray(262145).inputStream()); fail("oversized response accepted") }
  catch (_: IllegalArgumentException) { }
 }
}
