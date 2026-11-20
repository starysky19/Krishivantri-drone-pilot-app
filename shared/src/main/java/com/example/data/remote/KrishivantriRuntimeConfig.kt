package com.example.data.remote

/**
 * Runtime configuration populated at startup from the application's BuildConfig
 * (supplied via .env / AI Studio Secrets panel).
 */
object KrishivantriRuntimeConfig {
    @Volatile
    var apiBaseUrl: String = "https://api.krishivantri.in/api/v1/"

    @Volatile
    var supabaseUrl: String = "https://leawuzhjkuzwgzdduwzq.supabase.co"

    @Volatile
    var supabaseAnonKey: String = "MY_SUPABASE_ANON_KEY"

    @Volatile
    var razorpayKeyId: String = "MY_RAZORPAY_KEY_ID"

    @Volatile
    var satelliteTileUrl: String =
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

    @Volatile
    var satelliteApiKey: String = "MY_SATELLITE_API_KEY"

    @Volatile
    var isDebugLogging: Boolean = false

    fun isRazorpayConfigured(): Boolean {
        val key = razorpayKeyId.trim()
        return key.startsWith("rzp_") && !key.contains("MY_RAZORPAY") && !key.contains("placeholder")
    }

    fun normalizeSupabaseBaseUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        return runCatching {
            val uri = java.net.URI(trimmed)
            val scheme = uri.scheme
            val authority = uri.authority
            if (!scheme.isNullOrBlank() && !authority.isNullOrBlank()) {
                "$scheme://$authority"
            } else {
                trimmed
                    .replace(Regex("/rest/v1.*$"), "")
                    .replace(Regex("/realtime/v1.*$"), "")
                    .trimEnd('/')
            }
        }.getOrElse {
            trimmed
                .replace(Regex("/rest/v1.*$"), "")
                .replace(Regex("/realtime/v1.*$"), "")
                .trimEnd('/')
        }
    }

    fun configure(
        apiBaseUrl: String,
        supabaseUrl: String,
        supabaseAnonKey: String,
        razorpayKeyId: String = this.razorpayKeyId,
        satelliteTileUrl: String = this.satelliteTileUrl,
        satelliteApiKey: String = this.satelliteApiKey,
        isDebugLogging: Boolean = false
    ) {
        this.apiBaseUrl = apiBaseUrl
        this.supabaseUrl = normalizeSupabaseBaseUrl(supabaseUrl)
        this.supabaseAnonKey = supabaseAnonKey
        this.razorpayKeyId = razorpayKeyId
        this.satelliteTileUrl = satelliteTileUrl
        this.satelliteApiKey = satelliteApiKey
        this.isDebugLogging = isDebugLogging
    }
}
