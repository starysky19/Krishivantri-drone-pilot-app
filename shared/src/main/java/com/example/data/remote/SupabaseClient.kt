package com.example.data.remote

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class CloudConnectionState {
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    OFFLINE,
    NOT_CONFIGURED
}

data class CloudStatusSnapshot(
    val state: CloudConnectionState = CloudConnectionState.CONNECTING,
    val lastSyncedAtMs: Long? = null,
    val isRealtimeLive: Boolean = false,
    val detailMessage: String = "Connecting to Supabase Cloud..."
)

@JsonClass(generateAdapter = true)
data class SupabaseUserIdDto(
    val id: String
)

@JsonClass(generateAdapter = true)
data class SupabaseUserDto(
    val id: String,
    val phone: String,
    val name: String,
    val role: String,
    val created_at: Long
)

@JsonClass(generateAdapter = true)
data class SupabaseUserWithEmailDto(
    val id: String,
    val phone: String,
    val email: String? = null,
    val name: String,
    val role: String,
    val created_at: Long
)

@JsonClass(generateAdapter = true)
data class SupabaseAuthUserMetadata(
    val full_name: String? = null,
    val phone: String? = null,
    val role: String? = null,
    val pilot_user_id: String? = null,
    val drone_type: String? = null,
    val tank_capacity: Double? = null,
    val bank_upi_id: String? = null,
    val license_document_uri: String? = null
)

@JsonClass(generateAdapter = true)
data class SupabaseAuthIdentityDto(
    val id: String? = null,
    val provider: String? = null
)

@JsonClass(generateAdapter = true)
data class SupabaseAuthUserDto(
    val id: String,
    val email: String? = null,
    val phone: String? = null,
    val user_metadata: SupabaseAuthUserMetadata? = null,
    val identities: List<SupabaseAuthIdentityDto>? = null
)

@JsonClass(generateAdapter = true)
data class SupabaseAuthSessionResponse(
    val access_token: String? = null,
    val refresh_token: String? = null,
    val expires_in: Long? = null,
    val expires_at: Long? = null,
    val token_type: String? = null,
    val user: SupabaseAuthUserDto? = null
)

@JsonClass(generateAdapter = true)
data class SupabaseSignupRequest(
    val email: String,
    val password: String,
    val data: SupabaseAuthUserMetadata
)

@JsonClass(generateAdapter = true)
data class SupabaseOtpRequest(
    val email: String,
    val create_user: Boolean = false,
    val data: SupabaseAuthUserMetadata? = null
)

@JsonClass(generateAdapter = true)
data class SupabaseVerifyOtpRequest(
    val email: String,
    val token: String,
    val type: String = "email"
)

@JsonClass(generateAdapter = true)
data class SupabaseRefreshTokenRequest(
    val refresh_token: String
)

@JsonClass(generateAdapter = true)
data class SupabaseFarmerProfileDto(
    val user_id: String,
    val farmer_id_number: String,
    val coins: Int = 0
)

@JsonClass(generateAdapter = true)
data class SupabasePilotProfileDto(
    val user_id: String,
    val drone_type: String = "quadcopter",
    val tank_capacity: Double = 10.0,
    val license_document_uri: String = "",
    val is_license_verified: Boolean = false,
    val bank_upi_id: String = "",
    val wallet_balance: Double = 0.0,
    val is_online: Boolean = false,
    val rating_average: Double = 5.0,
    val rating_count: Int = 0,
    val current_lat: Double? = null,
    val current_lng: Double? = null,
    val location_updated_at: Long = 0L
)

@JsonClass(generateAdapter = true)
data class SupabaseOrderDto(
    val id: String,
    val farmer_id: String,
    val assigned_pilot_id: String? = null,
    val field_polygon_coordinates: List<SupabaseCoordDto>,
    val acres_to_spray: Double,
    val location_address: String,
    val total_amount: Double,
    val admin_commission: Double,
    val pilot_earnings: Double,
    val payment_method: String,
    val payment_status: String,
    val order_status: String,
    val pilot_eta_minutes: Int = 15,
    val initial_eta_minutes: Int = 15,
    val distance_km: Double? = null,
    val field_center_lat: Double = 0.0,
    val field_center_lng: Double = 0.0,
    val accepted_at: Long? = null,
    val pilot_rating: Int? = null,
    val razorpay_payment_id: String? = null,
    val created_at: Long = System.currentTimeMillis()
)

@JsonClass(generateAdapter = true)
data class SupabaseOrderRatingPatchDto(
    val pilot_rating: Int
)

@JsonClass(generateAdapter = true)
data class SupabaseFarmBoundaryDto(
    val id: String,
    val farmer_id: String,
    val farm_name: String,
    val polygon_coordinates: List<SupabaseCoordDto>,
    val area_acres: Double,
    val area_hectares: Double,
    val center_lat: Double,
    val center_lng: Double,
    val created_at: String? = null
)

@JsonClass(generateAdapter = true)
data class SupabaseCoordDto(
    val lat: Double,
    val lng: Double
)

@JsonClass(generateAdapter = true)
data class SupabaseOperatorLocationDto(
    val id: String,
    val name: String,
    val role_type: String, // 'drone_operator' | 'drone_mitra'
    val lat: Double,
    val lng: Double,
    val drone_type: String = "quadcopter",
    val is_available: Boolean = true,
    val heading_degrees: Float = 0f,
    val updated_at: String? = null
)

@JsonClass(generateAdapter = true)
data class SupabasePilotWithdrawalDto(
    val id: String,
    val pilot_id: String,
    val amount: Double,
    val method: String,
    val account_holder_name: String = "",
    val bank_account_last4: String = "",
    val ifsc_code: String = "",
    val bank_name: String = "",
    val upi_id: String = "",
    val reference_id: String = "",
    val status: String = "completed",
    val created_at: Long = System.currentTimeMillis()
)

interface SupabasePostgrestApi {
    @GET("rest/v1/users?select=id&limit=1")
    suspend fun pingUsers(): Response<List<SupabaseUserIdDto>>

    // --- Supabase Auth (Email OTP & Sessions) ---
    @POST("auth/v1/signup")
    suspend fun authSignUp(@Body body: SupabaseSignupRequest): Response<SupabaseAuthSessionResponse>

    @POST("auth/v1/otp")
    suspend fun authSendOtp(@Body body: SupabaseOtpRequest): Response<Unit>

    @POST("auth/v1/verify")
    suspend fun authVerifyOtp(@Body body: SupabaseVerifyOtpRequest): Response<SupabaseAuthSessionResponse>

    @POST("auth/v1/token?grant_type=refresh_token")
    suspend fun authRefreshSession(
        @Body body: SupabaseRefreshTokenRequest
    ): Response<SupabaseAuthSessionResponse>

    @GET("auth/v1/user")
    suspend fun authGetUser(
        @Header("Authorization") bearerToken: String
    ): Response<SupabaseAuthUserDto>

    @POST("auth/v1/logout")
    suspend fun authLogout(
        @Header("Authorization") bearerToken: String
    ): Response<Unit>

    // --- Users & Profiles ---
    @Headers("Prefer: resolution=merge-duplicates,return=representation")
    @POST("rest/v1/users")
    suspend fun upsertUser(@Body body: SupabaseUserDto): Response<List<SupabaseUserDto>>

    @Headers("Prefer: resolution=merge-duplicates,return=representation")
    @POST("rest/v1/users")
    suspend fun upsertUserWithEmail(
        @Body body: SupabaseUserWithEmailDto
    ): Response<List<SupabaseUserWithEmailDto>>

    @GET("rest/v1/users?select=*")
    suspend fun getUsersByPhone(@Query("phone") phoneFilter: String): Response<List<SupabaseUserDto>>

    @GET("rest/v1/users?select=*")
    suspend fun getUsersByEmail(
        @Query("email") emailFilter: String
    ): Response<List<SupabaseUserWithEmailDto>>

    @GET("rest/v1/users?select=*")
    suspend fun getUsersById(@Query("id") idFilter: String): Response<List<SupabaseUserDto>>

    @Headers("Prefer: resolution=merge-duplicates,return=representation")
    @POST("rest/v1/farmer_profiles")
    suspend fun upsertFarmerProfile(
        @Body body: SupabaseFarmerProfileDto
    ): Response<List<SupabaseFarmerProfileDto>>

    @GET("rest/v1/farmer_profiles?select=*")
    suspend fun getFarmerProfile(
        @Query("user_id") userIdFilter: String
    ): Response<List<SupabaseFarmerProfileDto>>

    @Headers("Prefer: resolution=merge-duplicates,return=representation")
    @POST("rest/v1/pilot_profiles")
    suspend fun upsertPilotProfile(
        @Body body: SupabasePilotProfileDto
    ): Response<List<SupabasePilotProfileDto>>

    @GET("rest/v1/pilot_profiles?select=*")
    suspend fun getPilotProfile(
        @Query("user_id") userIdFilter: String
    ): Response<List<SupabasePilotProfileDto>>

    // --- Spray Orders ---
    @Headers("Prefer: resolution=merge-duplicates,return=representation")
    @POST("rest/v1/orders")
    suspend fun upsertOrder(@Body body: SupabaseOrderDto): Response<List<SupabaseOrderDto>>

    @GET("rest/v1/orders?order=created_at.desc&limit=50&select=*")
    suspend fun getRecentOrders(): Response<List<SupabaseOrderDto>>

    @GET("rest/v1/orders?order=created_at.desc&select=*")
    suspend fun getOrdersForFarmer(
        @Query("farmer_id") farmerIdFilter: String
    ): Response<List<SupabaseOrderDto>>

    @GET("rest/v1/orders?order=created_at.desc&select=*")
    suspend fun getOrdersForPilot(
        @Query("assigned_pilot_id") pilotIdFilter: String
    ): Response<List<SupabaseOrderDto>>

    @Headers("Prefer: return=representation")
    @PATCH("rest/v1/orders")
    suspend fun patchOrderRating(
        @Query("id") orderIdFilter: String,
        @Body body: SupabaseOrderRatingPatchDto
    ): Response<List<SupabaseOrderDto>>

    // --- Farm Boundaries ---
    @Headers("Prefer: resolution=merge-duplicates,return=representation")
    @POST("rest/v1/farm_boundaries")
    suspend fun upsertFarmBoundary(
        @Body body: SupabaseFarmBoundaryDto
    ): Response<List<SupabaseFarmBoundaryDto>>

    @GET("rest/v1/farm_boundaries?order=created_at.desc")
    suspend fun getFarmBoundariesForFarmer(
        @Query("farmer_id") farmerIdFilter: String
    ): Response<List<SupabaseFarmBoundaryDto>>

    // --- Drone Operators / Pilots Live Location ---
    @GET("rest/v1/operator_locations?select=*")
    suspend fun getOperatorLocations(): Response<List<SupabaseOperatorLocationDto>>

    @Headers("Prefer: resolution=merge-duplicates,return=representation")
    @POST("rest/v1/operator_locations")
    suspend fun upsertOperatorLocation(
        @Body body: SupabaseOperatorLocationDto
    ): Response<List<SupabaseOperatorLocationDto>>

    // --- Pilot Withdrawals ---
    @Headers("Prefer: resolution=merge-duplicates,return=representation")
    @POST("rest/v1/pilot_withdrawals")
    suspend fun upsertPilotWithdrawal(
        @Body body: SupabasePilotWithdrawalDto
    ): Response<List<SupabasePilotWithdrawalDto>>

    @GET("rest/v1/pilot_withdrawals?order=created_at.desc&select=*")
    suspend fun getPilotWithdrawals(
        @Query("pilot_id") pilotIdFilter: String
    ): Response<List<SupabasePilotWithdrawalDto>>
}

object SupabaseClient {

    @Volatile
    private var cachedSignature: String? = null

    @Volatile
    private var cachedApi: SupabasePostgrestApi? = null

    private val _cloudStatus = MutableStateFlow(CloudStatusSnapshot())
    val cloudStatus: StateFlow<CloudStatusSnapshot> = _cloudStatus.asStateFlow()

    private fun normalizedRootUrl(): String =
        KrishivantriRuntimeConfig.normalizeSupabaseBaseUrl(KrishivantriRuntimeConfig.supabaseUrl)

    fun isConfigured(): Boolean {
        val url = normalizedRootUrl()
        val key = KrishivantriRuntimeConfig.cleanSecret(KrishivantriRuntimeConfig.supabaseAnonKey)
        val validScheme = url.startsWith("https://") ||
            url.startsWith("http://localhost") ||
            url.startsWith("http://127.0.0.1")
        return validScheme &&
            !url.contains("your-project") &&
            key.isNotBlank() &&
            !key.startsWith("MY_SUPABASE")
    }

    fun markConnecting(detail: String = "Connecting to Supabase Cloud...") {
        if (!isConfigured()) {
            markNotConfigured()
            return
        }
        _cloudStatus.update { current ->
            val nextState = if (current.lastSyncedAtMs != null) {
                CloudConnectionState.RECONNECTING
            } else {
                CloudConnectionState.CONNECTING
            }
            current.copy(state = nextState, detailMessage = detail)
        }
    }

    fun markConnected(detail: String = "Supabase Cloud Connected") {
        val now = System.currentTimeMillis()
        _cloudStatus.update { current ->
            current.copy(
                state = CloudConnectionState.CONNECTED,
                lastSyncedAtMs = now,
                detailMessage = detail
            )
        }
    }

    fun markRealtimeState(live: Boolean) {
        if (!isConfigured()) return
        _cloudStatus.update { current ->
            val updatedDetail = when {
                live -> "Supabase Cloud Live • Realtime"
                current.state == CloudConnectionState.CONNECTED -> "Supabase Cloud Connected"
                else -> current.detailMessage
            }
            current.copy(
                state = if (live) CloudConnectionState.CONNECTED else current.state,
                isRealtimeLive = live,
                lastSyncedAtMs = if (live) System.currentTimeMillis() else current.lastSyncedAtMs,
                detailMessage = updatedDetail
            )
        }
    }

    fun markSyncFailure(detail: String, offline: Boolean = true) {
        if (!isConfigured()) {
            markNotConfigured()
            return
        }
        _cloudStatus.update { current ->
            current.copy(
                state = if (offline) CloudConnectionState.OFFLINE else CloudConnectionState.RECONNECTING,
                isRealtimeLive = if (offline) false else current.isRealtimeLive,
                detailMessage = detail
            )
        }
    }

    fun markNotConfigured() {
        _cloudStatus.value = CloudStatusSnapshot(
            state = CloudConnectionState.NOT_CONFIGURED,
            lastSyncedAtMs = null,
            isRealtimeLive = false,
            detailMessage = "Add SUPABASE_URL & SUPABASE_ANON_KEY in Secrets"
        )
    }

    suspend fun verifyConnection(): CloudStatusSnapshot {
        val postgrest = api
        if (postgrest == null) {
            markNotConfigured()
            return _cloudStatus.value
        }
        markConnecting("Verifying Supabase Cloud link...")
        return try {
            val resp = postgrest.pingUsers()
            if (resp.isSuccessful) {
                markConnected("Supabase Cloud Connected")
            } else {
                val msg = when (resp.code()) {
                    401, 403 -> "Supabase key rejected (HTTP ${resp.code()})"
                    404 -> "Supabase table missing (HTTP 404)"
                    else -> "Cloud sync HTTP ${resp.code()}"
                }
                markSyncFailure(msg, offline = resp.code() in 500..599)
            }
            _cloudStatus.value
        } catch (t: Throwable) {
            markSyncFailure(
                detail = "Cloud offline — retrying (${t.localizedMessage ?: "network error"})",
                offline = true
            )
            _cloudStatus.value
        }
    }

    fun realtimeWebSocketUrl(): String {
        val base = normalizedRootUrl()
        require(base.startsWith("https://")) { "Supabase URL must use HTTPS." }
        val wsBase = base.replaceFirst("https://", "wss://")
        val encodedKey = URLEncoder.encode(
            KrishivantriRuntimeConfig.cleanSecret(KrishivantriRuntimeConfig.supabaseAnonKey),
            "UTF-8"
        )
        return "$wsBase/realtime/v1/websocket?apikey=$encodedKey&vsn=1.0.0"
    }

    val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            redactHeader("apikey")
            redactHeader("Authorization")
            level = if (KrishivantriRuntimeConfig.isDebugLogging) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .pingInterval(25, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val key = KrishivantriRuntimeConfig.cleanSecret(KrishivantriRuntimeConfig.supabaseAnonKey)
                val reqBuilder = originalRequest.newBuilder()
                    .header("apikey", key)
                    .header("Content-Type", "application/json")
                // Legacy Supabase anon keys are 3-segment JWTs and require Authorization Bearer;
                // newer sb_publishable_ keys authenticate via apikey header alone.
                // Do not overwrite user-specific Authorization headers passed to /auth/v1/user or /logout.
                if (originalRequest.header("Authorization") == null && key.count { it == '.' } == 2) {
                    reqBuilder.header("Authorization", "Bearer $key")
                }
                val request = reqBuilder.build()
                try {
                    val response = chain.proceed(request)
                    val path = request.url.encodedPath
                    if (path.contains("/rest/v1/")) {
                        if (response.isSuccessful) {
                            markConnected(
                                if (_cloudStatus.value.isRealtimeLive) {
                                    "Supabase Cloud Live • Realtime"
                                } else {
                                    "Supabase Cloud Connected"
                                }
                            )
                        } else if (response.code == 401 || response.code == 403 || response.code >= 500) {
                            markSyncFailure(
                                detail = "Cloud error HTTP ${response.code}",
                                offline = response.code >= 500
                            )
                        }
                    }
                    response
                } catch (io: IOException) {
                    markSyncFailure(
                        detail = "Cloud offline — retrying automatically",
                        offline = true
                    )
                    throw io
                }
            }
            .addInterceptor(logging)
            .build()
    }

    val api: SupabasePostgrestApi?
        get() {
            if (!isConfigured()) {
                markNotConfigured()
                return null
            }
            val baseUrl = normalizedRootUrl() + "/"
            val signature = "$baseUrl|${KrishivantriRuntimeConfig.cleanSecret(KrishivantriRuntimeConfig.supabaseAnonKey)}"
            val existing = cachedApi
            if (existing != null && cachedSignature == signature) {
                return existing
            }
            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            val created = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(SupabasePostgrestApi::class.java)
            cachedSignature = signature
            cachedApi = created
            return created
        }
}
