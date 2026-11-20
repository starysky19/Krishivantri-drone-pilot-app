package com.example.data.remote

/**
 * Runtime configuration populated at startup from the application's BuildConfig
 * (supplied via .env / AI Studio Secrets panel).
 */
object KrishivantriRuntimeConfig {
    @Volatile
    var apiBaseUrl: String = "https://api.krishivantri.in/api/v1/"

    @Volatile
    var supabaseUrl: String = ""

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

    fun cleanSecret(raw: String): String =
        raw.trim().trim('"', '\'').trim()

    fun isRazorpayConfigured(): Boolean {
        val key = cleanSecret(razorpayKeyId)
        return key.startsWith("rzp_") && !key.contains("MY_RAZORPAY") && !key.contains("placeholder")
    }

    fun normalizeSupabaseBaseUrl(rawUrl: String): String {
        val trimmed = cleanSecret(rawUrl).trimEnd('/')
        if (trimmed.isBlank() ||
            trimmed.contains("your-project", ignoreCase = true) ||
            trimmed.startsWith("MY_SUPABASE", ignoreCase = true)
        ) {
            return ""
        }
        val withScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://localhost", ignoreCase = true) ||
                trimmed.startsWith("http://127.0.0.1", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) ->
                "https://" + trimmed.removePrefix("http://").removePrefix("HTTP://")
            !trimmed.contains(".") && !trimmed.contains("/") ->
                "https://$trimmed.supabase.co"
            else -> "https://$trimmed"
        }
        return runCatching {
            val uri = java.net.URI(withScheme)
            val scheme = uri.scheme
            val authority = uri.authority
            if (!scheme.isNullOrBlank() && !authority.isNullOrBlank()) {
                "${scheme.lowercase()}://$authority"
            } else {
                withScheme
                    .replace(Regex("/rest/v1.*$"), "")
                    .replace(Regex("/realtime/v1.*$"), "")
                    .trimEnd('/')
            }
        }.getOrElse {
            withScheme
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
        val cleanedBase = cleanSecret(apiBaseUrl)
        this.apiBaseUrl = if (cleanedBase.isBlank()) {
            this.apiBaseUrl
        } else if (cleanedBase.endsWith("/")) {
            cleanedBase
        } else {
            "$cleanedBase/"
        }
        this.supabaseUrl = normalizeSupabaseBaseUrl(supabaseUrl)
        this.supabaseAnonKey = cleanSecret(supabaseAnonKey)
        this.razorpayKeyId = cleanSecret(razorpayKeyId)
        this.satelliteTileUrl = cleanSecret(satelliteTileUrl)
        this.satelliteApiKey = cleanSecret(satelliteApiKey)
        this.isDebugLogging = isDebugLogging
    }
}
