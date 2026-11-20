package com.example

import com.example.data.remote.KrishivantriRuntimeConfig
import com.example.data.remote.SupabaseClient
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for runtime configuration and Supabase endpoint normalization.
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun supabaseUrl_stripsRestPathSuffix() {
    assertEquals(
      "https://nzuhgpzwxdmgjdozodhn.supabase.co",
      KrishivantriRuntimeConfig.normalizeSupabaseBaseUrl("https://nzuhgpzwxdmgjdozodhn.supabase.co/rest/v1/")
    )
    assertEquals(
      "https://nzuhgpzwxdmgjdozodhn.supabase.co",
      KrishivantriRuntimeConfig.normalizeSupabaseBaseUrl("https://nzuhgpzwxdmgjdozodhn.supabase.co/rest/v1")
    )

    KrishivantriRuntimeConfig.configure(
      apiBaseUrl = "https://api.krishivantri.in/api/v1/",
      supabaseUrl = "https://nzuhgpzwxdmgjdozodhn.supabase.co/rest/v1/",
      supabaseAnonKey = "sb_publishable_test_key"
    )
    assertEquals("https://nzuhgpzwxdmgjdozodhn.supabase.co", KrishivantriRuntimeConfig.supabaseUrl)
    assertEquals(
      "wss://nzuhgpzwxdmgjdozodhn.supabase.co/realtime/v1/websocket?apikey=sb_publishable_test_key&vsn=1.0.0",
      SupabaseClient.realtimeWebSocketUrl()
    )
  }
}
