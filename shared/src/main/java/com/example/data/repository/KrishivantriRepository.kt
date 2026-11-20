package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import com.example.data.local.KrishivantriDatabase
import com.example.data.local.PersistedAuthSession
import com.example.data.model.CommissionLogEntity
import com.example.data.model.DailyOrderVolume
import com.example.data.model.DroneType
import com.example.data.model.FarmerProfileEntity
import com.example.data.model.FarmerWithUser
import com.example.data.model.LatLngPoint
import com.example.data.model.OrderEntity
import com.example.data.model.OrderStatus
import com.example.data.model.OrderWithDetails
import com.example.data.model.PaymentMethod
import com.example.data.model.PaymentStatus
import com.example.data.model.PilotBankAccountEntity
import com.example.data.model.PilotPerformanceStat
import com.example.data.model.PilotProfileEntity
import com.example.data.model.PilotWithdrawalEntity
import com.example.data.model.PilotWithUser
import com.example.data.model.RejectedOrderEntity
import com.example.data.model.UserEntity
import com.example.data.model.UserRole
import com.example.data.model.WithdrawalMethod
import com.example.data.model.WithdrawalStatus
import com.example.data.remote.CloudStatusSnapshot
import com.example.data.remote.SupabaseAuthUserDto
import com.example.data.remote.SupabaseAuthUserMetadata
import com.example.data.remote.SupabaseClient
import com.example.data.remote.SupabaseCoordDto
import com.example.data.remote.SupabaseFarmerProfileDto
import com.example.data.remote.SupabaseOrderDto
import com.example.data.remote.SupabaseOrderRatingPatchDto
import com.example.data.remote.SupabaseOtpRequest
import com.example.data.remote.SupabasePilotProfileDto
import com.example.data.remote.SupabasePilotWithdrawalDto
import com.example.data.remote.SupabasePostgrestApi
import com.example.data.remote.SupabaseRefreshTokenRequest
import com.example.data.remote.SupabaseSignupRequest
import com.example.data.remote.SupabaseUserDto
import com.example.data.remote.SupabaseUserWithEmailDto
import com.example.data.remote.SupabaseVerifyOtpRequest
import com.example.domain.GeoPolygonCalculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

sealed class AuthResult {
    data class Success(
        val user: UserEntity,
        val accessToken: String = "",
        val refreshToken: String = "",
        val expiresAtEpochSec: Long = 0L
    ) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

sealed class OtpRequestResult {
    data class Sent(
        val message: String,
        val cooldownSeconds: Int = 60,
        val expiresInSeconds: Int = 300
    ) : OtpRequestResult()
    data class RateLimited(
        val waitSeconds: Int,
        val message: String
    ) : OtpRequestResult()
    data class Error(val message: String) : OtpRequestResult()
}

sealed class SessionValidationResult {
    data class Valid(
        val user: UserEntity,
        val accessToken: String,
        val refreshToken: String,
        val expiresAtEpochSec: Long
    ) : SessionValidationResult()
    data object OfflineKeepLocal : SessionValidationResult()
    data class ExpiredOrRevoked(val reason: String) : SessionValidationResult()
}

sealed class AcceptOrderResult {
    data object Success : AcceptOrderResult()
    data class InsufficientWallet(
        val requiredCommission: Double,
        val currentBalance: Double
    ) : AcceptOrderResult()
    data class Error(val message: String) : AcceptOrderResult()
}

sealed class CloudSyncResult {
    data object NotConfigured : CloudSyncResult()
    data class Success(val syncedOrders: Int) : CloudSyncResult()
    data class Error(val message: String) : CloudSyncResult()
}

sealed class WithdrawResult {
    data class Success(val withdrawal: PilotWithdrawalEntity) : WithdrawResult()
    data class Error(val message: String) : WithdrawResult()
}

class KrishivantriRepository(
    private val database: KrishivantriDatabase,
    context: Context? = null
) {

    private val dao = database.dao()
    private val pendingSyncPrefs: SharedPreferences? = context?.applicationContext
        ?.getSharedPreferences("krishivantri_cloud_sync_queue", Context.MODE_PRIVATE)

    private val dirtyUsers = ConcurrentHashMap.newKeySet<String>()
    private val dirtyProfiles = ConcurrentHashMap.newKeySet<String>()
    private val dirtyOrders = ConcurrentHashMap.newKeySet<String>()
    private val dirtyWithdrawals = ConcurrentHashMap.newKeySet<String>()

    init {
        pendingSyncPrefs?.let { prefs ->
            dirtyUsers.addAll(prefs.getStringSet(KEY_DIRTY_USERS, emptySet()).orEmpty())
            dirtyProfiles.addAll(prefs.getStringSet(KEY_DIRTY_PROFILES, emptySet()).orEmpty())
            dirtyOrders.addAll(prefs.getStringSet(KEY_DIRTY_ORDERS, emptySet()).orEmpty())
            dirtyWithdrawals.addAll(prefs.getStringSet(KEY_DIRTY_WITHDRAWALS, emptySet()).orEmpty())
        }
    }

    @Volatile
    private var lastPilotLocationPushMs: Long = 0L

    @Volatile
    private var remoteUsersHasEmailColumn: Boolean = true

    private val otpLastSentMsByEmail = ConcurrentHashMap<String, Long>()
    private val otpRequestHistoryByEmail = ConcurrentHashMap<String, MutableList<Long>>()
    private val otpFailedAttemptsByEmail = ConcurrentHashMap<String, Int>()

    companion object {
        private val VALID_PHONE_REGEX = Regex("^[0-9]{10,15}$")
        private val VALID_EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        private val VALID_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,128}$")
        private val VALID_BANK_ACCOUNT_REGEX = Regex("^[0-9]{9,18}$")
        private val VALID_IFSC_REGEX = Regex("^[A-Z]{4}0[A-Z0-9]{6}$")
        private const val OTP_COOLDOWN_MS = 60_000L
        private const val OTP_RATE_WINDOW_MS = 15 * 60_000L
        private const val MAX_OTP_REQUESTS_PER_WINDOW = 5
        private const val MAX_OTP_VERIFY_ATTEMPTS = 5
        private const val OTP_EXPIRY_SECONDS = 300
        private const val KEY_DIRTY_USERS = "dirty_users"
        private const val KEY_DIRTY_PROFILES = "dirty_profiles"
        private const val KEY_DIRTY_ORDERS = "dirty_orders"
        private const val KEY_DIRTY_WITHDRAWALS = "dirty_withdrawals"

        fun isValidEmail(email: String): Boolean = VALID_EMAIL_REGEX.matches(email.trim())
        fun normalizeEmail(email: String): String = email.trim().lowercase(Locale.US)
    }

    private fun saveDirtySet(key: String, set: Set<String>) {
        pendingSyncPrefs?.edit()?.putStringSet(key, set.toSet())?.apply()
    }

    private fun markDirty(key: String, set: MutableSet<String>, id: String) {
        set.add(id)
        saveDirtySet(key, set)
    }

    private fun clearDirty(key: String, set: MutableSet<String>, id: String) {
        if (set.remove(id)) {
            saveDirtySet(key, set)
        }
    }

    private fun normalizePhoneOrNull(raw: String): String? {
        val digitsOnly = raw.trim().filter(Char::isDigit)
        return digitsOnly.takeIf { VALID_PHONE_REGEX.matches(it) }
    }

    private fun isSafeIdentifier(id: String): Boolean = VALID_ID_REGEX.matches(id)

    private fun generateRandomAuthSecret(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#\$%"
        val rng = SecureRandom()
        return buildString(32) {
            repeat(32) { append(alphabet[rng.nextInt(alphabet.length)]) }
        }
    }

    suspend fun verifyLocalDatabaseReady() {
        dao.getUserById("__db_health_check__")
    }

    suspend fun checkSupabaseConnection(): CloudStatusSnapshot = withContext(Dispatchers.IO) {
        SupabaseClient.verifyConnection()
    }

    private suspend fun pushUserToSupabase(user: UserEntity): Boolean = withContext(Dispatchers.IO) {
        val api = SupabaseClient.api ?: return@withContext false
        return@withContext try {
            if (user.email.isNotBlank() && remoteUsersHasEmailColumn) {
                val withEmailResp = api.upsertUserWithEmail(
                    SupabaseUserWithEmailDto(
                        id = user.id,
                        phone = user.phone,
                        email = user.email,
                        name = user.name,
                        role = user.role,
                        created_at = user.createdAt
                    )
                )
                if (withEmailResp.isSuccessful) {
                    clearDirty(KEY_DIRTY_USERS, dirtyUsers, user.id)
                    return@withContext true
                }
                val errTxt = withEmailResp.errorBody()?.string().orEmpty()
                if (withEmailResp.code() in 400..404 &&
                    (errTxt.contains("email", ignoreCase = true) ||
                        errTxt.contains("42703") ||
                        errTxt.contains("PGRST204"))
                ) {
                    remoteUsersHasEmailColumn = false
                } else {
                    return@withContext false
                }
            }
            val resp = api.upsertUser(
                SupabaseUserDto(
                    id = user.id,
                    phone = user.phone,
                    name = user.name,
                    role = user.role,
                    created_at = user.createdAt
                )
            )
            if (resp.isSuccessful) {
                clearDirty(KEY_DIRTY_USERS, dirtyUsers, user.id)
                true
            } else {
                false
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun pushPilotProfileToSupabase(profile: PilotProfileEntity): Boolean =
        withContext(Dispatchers.IO) {
            val api = SupabaseClient.api ?: return@withContext false
            return@withContext try {
                val resp = api.upsertPilotProfile(profile.toSupabaseDto())
                if (resp.isSuccessful) {
                    clearDirty(KEY_DIRTY_PROFILES, dirtyProfiles, profile.userId)
                    true
                } else {
                    false
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                false
            }
        }

    private suspend fun pushOrderToSupabase(order: OrderEntity): Boolean =
        withContext(Dispatchers.IO) {
            val api = SupabaseClient.api ?: return@withContext false
            return@withContext try {
                val resp = api.upsertOrder(order.toSupabaseDto())
                if (resp.isSuccessful) {
                    clearDirty(KEY_DIRTY_ORDERS, dirtyOrders, order.id)
                    true
                } else {
                    false
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                false
            }
        }

    private suspend fun pushWithdrawalToSupabase(withdrawal: PilotWithdrawalEntity): Boolean =
        withContext(Dispatchers.IO) {
            val api = SupabaseClient.api ?: return@withContext false
            return@withContext try {
                val resp = api.upsertPilotWithdrawal(
                    SupabasePilotWithdrawalDto(
                        id = withdrawal.id,
                        pilot_id = withdrawal.pilotId,
                        amount = withdrawal.amount,
                        method = withdrawal.method,
                        account_holder_name = withdrawal.accountHolderName,
                        bank_account_last4 = withdrawal.bankAccountNumber.takeLast(4),
                        ifsc_code = withdrawal.ifscCode,
                        bank_name = withdrawal.bankName,
                        upi_id = withdrawal.upiId,
                        reference_id = withdrawal.referenceId,
                        status = withdrawal.status,
                        created_at = withdrawal.createdAt
                    )
                )
                if (resp.isSuccessful) {
                    clearDirty(KEY_DIRTY_WITHDRAWALS, dirtyWithdrawals, withdrawal.id)
                    true
                } else {
                    false
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                false
            }
        }

    suspend fun flushPendingCloudWrites(pilotId: String? = null) = withContext(Dispatchers.IO) {
        if (SupabaseClient.api == null) return@withContext

        for (userId in dirtyUsers.toList()) {
            val user = dao.getUserById(userId)
            if (user == null) {
                clearDirty(KEY_DIRTY_USERS, dirtyUsers, userId)
            } else {
                pushUserToSupabase(user)
            }
        }

        for (profileId in dirtyProfiles.toList()) {
            val profile = dao.getPilotProfile(profileId)
            if (profile == null) {
                clearDirty(KEY_DIRTY_PROFILES, dirtyProfiles, profileId)
            } else {
                pushPilotProfileToSupabase(profile)
            }
        }

        for (orderId in dirtyOrders.toList()) {
            val order = dao.getOrderById(orderId)
            if (order == null) {
                clearDirty(KEY_DIRTY_ORDERS, dirtyOrders, orderId)
            } else {
                pushOrderToSupabase(order)
            }
        }

        if (dirtyWithdrawals.isNotEmpty() && !pilotId.isNullOrBlank()) {
            val localWithdrawals = dao.getPilotWithdrawals(pilotId).associateBy { it.id }
            for (wdId in dirtyWithdrawals.toList()) {
                val wd = localWithdrawals[wdId] ?: continue
                pushWithdrawalToSupabase(wd)
            }
        }
    }

    suspend fun registerFarmer(
        name: String,
        phone: String,
        farmerIdNumber: String
    ): AuthResult {
        val cleanPhone = normalizePhoneOrNull(phone)
            ?: return AuthResult.Error("Enter a valid 10 to 15-digit mobile number.")
        val existing = dao.getUserByPhone(cleanPhone)
        if (existing != null) {
            return AuthResult.Error("Mobile number already registered. Please sign in.")
        }
        val user = UserEntity(
            id = UUID.randomUUID().toString(),
            phone = cleanPhone,
            name = name.trim(),
            role = UserRole.FARMER.apiValue
        )
        val profile = FarmerProfileEntity(
            userId = user.id,
            farmerIdNumber = farmerIdNumber.trim().uppercase(Locale.getDefault()),
            coins = 0
        )
        database.withTransaction {
            dao.insertUser(user)
            dao.insertFarmerProfile(profile)
        }
        withContext(Dispatchers.IO) {
            runCatching {
                SupabaseClient.api?.let { api ->
                    api.upsertUser(
                        SupabaseUserDto(
                            id = user.id,
                            phone = user.phone,
                            name = user.name,
                            role = user.role,
                            created_at = user.createdAt
                        )
                    )
                    api.upsertFarmerProfile(
                        SupabaseFarmerProfileDto(
                            user_id = profile.userId,
                            farmer_id_number = profile.farmerIdNumber,
                            coins = profile.coins
                        )
                    )
                }
            }
        }
        return AuthResult.Success(user)
    }

    suspend fun registerPilot(
        name: String,
        email: String,
        phone: String,
        droneType: DroneType,
        tankCapacity: Double,
        licenseDocumentUri: String,
        bankUpiId: String
    ): AuthResult {
        val cleanName = name.trim()
        val cleanEmail = normalizeEmail(email)
        val cleanPhone = normalizePhoneOrNull(phone)
        val cleanUpi = bankUpiId.trim()

        if (cleanName.isBlank()) {
            return AuthResult.Error("Enter your full name.")
        }
        if (!isValidEmail(cleanEmail)) {
            return AuthResult.Error("Enter a valid email address.")
        }
        if (cleanPhone == null) {
            return AuthResult.Error("Enter a valid 10 to 15-digit contact number.")
        }
        if (tankCapacity !in 1.0..200.0) {
            return AuthResult.Error("Enter a valid spray tank capacity (1–200 liters).")
        }
        if (cleanUpi.isBlank()) {
            return AuthResult.Error("Enter your Bank UPI ID for payouts.")
        }

        if (dao.getUserByEmail(cleanEmail) != null) {
            return AuthResult.Error("An account with this email address is already registered. Please sign in with Email OTP.")
        }
        if (dao.getUserByPhone(cleanPhone) != null) {
            return AuthResult.Error("Contact number already registered. Please sign in with Email OTP.")
        }

        var resolvedUserId = UUID.randomUUID().toString()
        var issuedAccessToken = ""
        var issuedRefreshToken = ""
        var issuedExpiresAtEpochSec = 0L

        val api = SupabaseClient.api
        if (api != null) {
            val remotePrepError = withContext(Dispatchers.IO) {
                try {
                    findRemoteUserByEmail(api, cleanEmail)?.let {
                        return@withContext "An account with this email address is already registered. Please sign in with Email OTP."
                    }
                    val remoteByPhone = findRemoteUserByPhone(api, cleanPhone, UserRole.PILOT)
                    if (remoteByPhone != null) {
                        if (!remoteByPhone.role.equals(UserRole.PILOT.apiValue, ignoreCase = true)) {
                            return@withContext "This mobile number is already registered under another account."
                        }
                        val profileResp = api.getPilotProfile("eq.${remoteByPhone.id}")
                        if (!profileResp.body().isNullOrEmpty()) {
                            return@withContext "Contact number already registered. Please sign in with Email OTP."
                        }
                        if (isSafeIdentifier(remoteByPhone.id)) {
                            resolvedUserId = remoteByPhone.id
                        }
                    }

                    val signupResp = api.authSignUp(
                        SupabaseSignupRequest(
                            email = cleanEmail,
                            password = generateRandomAuthSecret(),
                            data = SupabaseAuthUserMetadata(
                                full_name = cleanName,
                                phone = cleanPhone,
                                role = UserRole.PILOT.apiValue,
                                pilot_user_id = resolvedUserId,
                                drone_type = droneType.apiValue,
                                tank_capacity = tankCapacity,
                                bank_upi_id = cleanUpi,
                                license_document_uri = licenseDocumentUri
                            )
                        )
                    )
                    if (!signupResp.isSuccessful) {
                        val errStr = signupResp.errorBody()?.string().orEmpty()
                        return@withContext when {
                            signupResp.code() == 429 ->
                                "Too many registration attempts. Please wait a minute and try again."
                            errStr.contains("already", ignoreCase = true) ||
                                errStr.contains("exists", ignoreCase = true) ||
                                errStr.contains("registered", ignoreCase = true) ->
                                "An account with this email address is already registered. Please sign in with Email OTP."
                            errStr.contains("invalid", ignoreCase = true) && errStr.contains("email", ignoreCase = true) ->
                                "Enter a valid email address."
                            else ->
                                "Unable to complete registration on authentication server (HTTP ${signupResp.code()}). Please try again."
                        }
                    }

                    val body = signupResp.body()
                    val authUser = body?.user
                    // GoTrue returns empty identities list when signup obfuscates an already-registered email
                    if (authUser?.identities != null && authUser.identities.isEmpty()) {
                        return@withContext "An account with this email address is already registered. Please sign in with Email OTP."
                    }
                    val authId = authUser?.id
                    if (authId != null && isSafeIdentifier(authId) && remoteByPhone == null) {
                        resolvedUserId = authId
                    }
                    issuedAccessToken = body?.access_token.orEmpty()
                    issuedRefreshToken = body?.refresh_token.orEmpty()
                    issuedExpiresAtEpochSec = body?.expires_at
                        ?: body?.expires_in?.let { (System.currentTimeMillis() / 1000L) + it }
                        ?: 0L
                    null
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: IOException) {
                    "Unable to reach cloud authentication server. Check your internet connection and try again."
                } catch (_: Throwable) {
                    "Registration failed due to a server error. Please try again."
                }
            }
            if (remotePrepError != null) {
                return AuthResult.Error(remotePrepError)
            }
        }

        val user = UserEntity(
            id = resolvedUserId,
            phone = cleanPhone,
            email = cleanEmail,
            name = cleanName,
            role = UserRole.PILOT.apiValue
        )
        val profile = PilotProfileEntity(
            userId = user.id,
            droneType = droneType.apiValue,
            tankCapacity = tankCapacity,
            licenseDocumentUri = licenseDocumentUri,
            isLicenseVerified = false,
            bankUpiId = cleanUpi,
            walletBalance = 0.0,
            isOnline = false,
            ratingAverage = 5.0,
            ratingCount = 0
        )
        database.withTransaction {
            dao.insertUser(user)
            dao.insertPilotProfile(profile)
        }
        markDirty(KEY_DIRTY_USERS, dirtyUsers, user.id)
        markDirty(KEY_DIRTY_PROFILES, dirtyProfiles, profile.userId)
        withContext(Dispatchers.IO) {
            runCatching {
                SupabaseClient.api?.let { remoteApi ->
                    val userOk = pushUserToSupabase(user)
                    if (userOk) {
                        pushPilotProfileToSupabase(profile)
                    } else {
                        val existingRemote = remoteApi.getUsersByPhone("eq.${user.phone}").body()?.firstOrNull()
                        if (existingRemote != null && existingRemote.role.equals(UserRole.PILOT.apiValue, ignoreCase = true)) {
                            pushPilotProfileToSupabase(profile.copy(userId = existingRemote.id))
                        }
                    }
                }
            }
        }
        return AuthResult.Success(
            user = user,
            accessToken = issuedAccessToken,
            refreshToken = issuedRefreshToken,
            expiresAtEpochSec = issuedExpiresAtEpochSec
        )
    }

    suspend fun requestPilotEmailOtp(email: String): OtpRequestResult {
        val cleanEmail = normalizeEmail(email)
        if (!isValidEmail(cleanEmail)) {
            return OtpRequestResult.Error("Enter a valid registered email address.")
        }

        val now = System.currentTimeMillis()
        val lastSent = otpLastSentMsByEmail[cleanEmail]
        if (lastSent != null && (now - lastSent) < OTP_COOLDOWN_MS) {
            val waitSec = (((OTP_COOLDOWN_MS - (now - lastSent)) + 999L) / 1000L).toInt().coerceAtLeast(1)
            return OtpRequestResult.RateLimited(
                waitSeconds = waitSec,
                message = "Please wait ${waitSec}s before requesting another OTP."
            )
        }

        val recentRequests = otpRequestHistoryByEmail.getOrPut(cleanEmail) { mutableListOf() }
        synchronized(recentRequests) {
            recentRequests.removeAll { (now - it) > OTP_RATE_WINDOW_MS }
            if (recentRequests.size >= MAX_OTP_REQUESTS_PER_WINDOW) {
                return OtpRequestResult.RateLimited(
                    waitSeconds = 60,
                    message = "Too many OTP requests. Please wait before trying again."
                )
            }
        }

        val api = SupabaseClient.api
            ?: return OtpRequestResult.Error(
                "Cloud authentication is not configured (missing SUPABASE_URL or SUPABASE_ANON_KEY in Secrets)."
            )

        return withContext(Dispatchers.IO) {
            try {
                val resp = api.authSendOtp(SupabaseOtpRequest(email = cleanEmail, create_user = false))
                val code = resp.code()
                when {
                    resp.isSuccessful -> {
                        otpLastSentMsByEmail[cleanEmail] = now
                        synchronized(recentRequests) { recentRequests.add(now) }
                        otpFailedAttemptsByEmail.remove(cleanEmail)
                        OtpRequestResult.Sent(
                            message = "Verification OTP sent to $cleanEmail. Valid for 5 minutes.",
                            cooldownSeconds = (OTP_COOLDOWN_MS / 1000L).toInt(),
                            expiresInSeconds = OTP_EXPIRY_SECONDS
                        )
                    }
                    code == 422 -> {
                        // Legacy pilots registered in public.users before Email OTP may lack an auth.users row.
                        // Provision auth.users ONLY if this email already belongs to a verified pilot record;
                        // never create accounts or disclose registration status for unknown emails.
                        val existingRemotePilot = runCatching {
                            findRemoteUserByEmail(api, cleanEmail)
                        }.getOrNull()?.takeIf { it.role.equals(UserRole.PILOT.apiValue, ignoreCase = true) }
                        val existingLocalPilot = if (existingRemotePilot == null) {
                            dao.getUserByEmail(cleanEmail)?.takeIf { it.role.equals(UserRole.PILOT.apiValue, ignoreCase = true) }
                        } else {
                            null
                        }
                        if (existingRemotePilot != null || existingLocalPilot != null) {
                            val pilotId = existingRemotePilot?.id ?: existingLocalPilot?.id
                            val pilotName = existingRemotePilot?.name ?: existingLocalPilot?.name
                            val pilotPhone = existingRemotePilot?.phone ?: existingLocalPilot?.phone
                            val provisionResp = api.authSendOtp(
                                SupabaseOtpRequest(
                                    email = cleanEmail,
                                    create_user = true,
                                    data = SupabaseAuthUserMetadata(
                                        full_name = pilotName,
                                        phone = pilotPhone,
                                        role = UserRole.PILOT.apiValue,
                                        pilot_user_id = pilotId
                                    )
                                )
                            )
                            if (provisionResp.code() == 429) {
                                otpLastSentMsByEmail[cleanEmail] = now
                                return@withContext OtpRequestResult.RateLimited(
                                    waitSeconds = 60,
                                    message = "Too many OTP requests. Please wait 60s before trying again."
                                )
                            }
                        }
                        // Do NOT reveal whether the email address is registered or not
                        otpLastSentMsByEmail[cleanEmail] = now
                        synchronized(recentRequests) { recentRequests.add(now) }
                        otpFailedAttemptsByEmail.remove(cleanEmail)
                        OtpRequestResult.Sent(
                            message = "If $cleanEmail is registered with NexusTekFusion, a 6-digit verification code has been sent.",
                            cooldownSeconds = (OTP_COOLDOWN_MS / 1000L).toInt(),
                            expiresInSeconds = OTP_EXPIRY_SECONDS
                        )
                    }
                    code == 429 -> {
                        otpLastSentMsByEmail[cleanEmail] = now
                        OtpRequestResult.RateLimited(
                            waitSeconds = 60,
                            message = "Too many OTP requests. Please wait 60s before trying again."
                        )
                    }
                    else -> {
                        OtpRequestResult.Error("Unable to send verification OTP right now (HTTP $code). Please try again.")
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: IOException) {
                OtpRequestResult.Error("Network error while sending OTP. Check your internet connection.")
            } catch (_: Throwable) {
                OtpRequestResult.Error("Unable to send OTP right now. Please try again.")
            }
        }
    }

    suspend fun verifyPilotEmailOtp(email: String, otp: String): AuthResult {
        val cleanEmail = normalizeEmail(email)
        val cleanOtp = otp.trim()
        if (!isValidEmail(cleanEmail)) {
            return AuthResult.Error("Enter a valid email address.")
        }
        if (cleanOtp.length !in 6..8 || !cleanOtp.all(Char::isDigit)) {
            return AuthResult.Error("Enter the numeric verification OTP sent to your email.")
        }

        val failedCount = otpFailedAttemptsByEmail[cleanEmail] ?: 0
        if (failedCount >= MAX_OTP_VERIFY_ATTEMPTS) {
            return AuthResult.Error("Too many incorrect OTP attempts. Please request a new OTP.")
        }

        val api = SupabaseClient.api
            ?: return AuthResult.Error("Cloud authentication is not configured.")

        return withContext(Dispatchers.IO) {
            try {
                var resp = api.authVerifyOtp(
                    SupabaseVerifyOtpRequest(
                        email = cleanEmail,
                        token = cleanOtp,
                        type = "email"
                    )
                )
                if (!resp.isSuccessful && resp.code() in setOf(400, 401, 403, 422)) {
                    // Fallback for pilots whose auth account is awaiting initial email confirmation
                    val signupResp = api.authVerifyOtp(
                        SupabaseVerifyOtpRequest(
                            email = cleanEmail,
                            token = cleanOtp,
                            type = "signup"
                        )
                    )
                    if (signupResp.isSuccessful || signupResp.code() == 429) {
                        resp = signupResp
                    }
                }
                if (!resp.isSuccessful) {
                    val code = resp.code()
                    otpFailedAttemptsByEmail[cleanEmail] = failedCount + 1
                    return@withContext if (code == 429) {
                        AuthResult.Error("Too many verification attempts. Please wait and request a new OTP.")
                    } else {
                        AuthResult.Error("Invalid or expired OTP. Please check your code or request a new one.")
                    }
                }

                val sessionBody = resp.body()
                    ?: return@withContext AuthResult.Error("Verification failed. Please request a new OTP.")
                val accessToken = sessionBody.access_token.orEmpty()
                val refreshToken = sessionBody.refresh_token.orEmpty()
                val expiresAtEpochSec = sessionBody.expires_at
                    ?: sessionBody.expires_in?.let { (System.currentTimeMillis() / 1000L) + it }
                    ?: ((System.currentTimeMillis() / 1000L) + 3600L)

                if (accessToken.isBlank()) {
                    return@withContext AuthResult.Error("Authentication session could not be established. Please try again.")
                }

                otpFailedAttemptsByEmail.remove(cleanEmail)
                val hydrated = hydratePilotAfterOtpVerification(
                    api = api,
                    cleanEmail = cleanEmail,
                    authUser = sessionBody.user
                )
                when (hydrated) {
                    is AuthResult.Success -> {
                        runCatching { syncPilotStateFromSupabase(hydrated.user.id) }
                        AuthResult.Success(
                            user = hydrated.user,
                            accessToken = accessToken,
                            refreshToken = refreshToken,
                            expiresAtEpochSec = expiresAtEpochSec
                        )
                    }
                    is AuthResult.Error -> hydrated
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: IOException) {
                AuthResult.Error("Unable to reach authentication server. Please check your internet connection.")
            } catch (_: Throwable) {
                AuthResult.Error("OTP verification failed. Please request a new OTP.")
            }
        }
    }

    private suspend fun hydratePilotAfterOtpVerification(
        api: SupabasePostgrestApi,
        cleanEmail: String,
        authUser: SupabaseAuthUserDto?
    ): AuthResult {
        val meta = authUser?.user_metadata
        val candidateIds = buildList {
            meta?.pilot_user_id?.takeIf { isSafeIdentifier(it) }?.let { add(it) }
            authUser?.id?.takeIf { isSafeIdentifier(it) }?.let { add(it) }
        }.distinct()

        var remoteUser: SupabaseUserDto? = null
        var remoteEmail: String = cleanEmail

        for (cid in candidateIds) {
            val byId = runCatching { api.getUsersById("eq.$cid").body()?.firstOrNull() }.getOrNull()
            if (byId != null) {
                remoteUser = byId
                break
            }
        }

        if (remoteUser == null) {
            val byEmail = runCatching { findRemoteUserByEmail(api, cleanEmail) }.getOrNull()
            if (byEmail != null) {
                remoteUser = SupabaseUserDto(
                    id = byEmail.id,
                    phone = byEmail.phone,
                    name = byEmail.name,
                    role = byEmail.role,
                    created_at = byEmail.created_at
                )
                remoteEmail = byEmail.email?.takeIf { it.isNotBlank() } ?: cleanEmail
            }
        }

        val metaPhone = meta?.phone?.let { normalizePhoneOrNull(it) }
        if (remoteUser == null && metaPhone != null) {
            remoteUser = runCatching { findRemoteUserByPhone(api, metaPhone, UserRole.PILOT) }.getOrNull()
        }

        val resolvedUser: UserEntity = if (remoteUser != null) {
            if (!remoteUser.role.equals(UserRole.PILOT.apiValue, ignoreCase = true)) {
                return AuthResult.Error("This email is not registered as a NexusTekFusion Drone Pilot.")
            }
            UserEntity(
                id = remoteUser.id,
                phone = remoteUser.phone,
                email = remoteEmail,
                name = remoteUser.name,
                role = remoteUser.role,
                createdAt = remoteUser.created_at
            )
        } else {
            val localUser = dao.getUserByEmail(cleanEmail)
                ?: candidateIds.firstNotNullOfOrNull { dao.getUserById(it) }
            if (localUser != null) {
                if (!localUser.role.equals(UserRole.PILOT.apiValue, ignoreCase = true)) {
                    return AuthResult.Error("This email is not registered as a NexusTekFusion Drone Pilot.")
                }
                localUser.copy(email = cleanEmail)
            } else {
                val resolvedId = candidateIds.firstOrNull() ?: UUID.randomUUID().toString()
                UserEntity(
                    id = resolvedId,
                    phone = metaPhone ?: "",
                    email = cleanEmail,
                    name = meta?.full_name?.takeIf { it.isNotBlank() } ?: cleanEmail.substringBefore("@"),
                    role = UserRole.PILOT.apiValue
                )
            }
        }

        dao.insertUser(resolvedUser)
        if (remoteUser == null) {
            runCatching { pushUserToSupabase(resolvedUser) }
        } else if (remoteEmail.isNotBlank() && remoteUsersHasEmailColumn) {
            runCatching { pushUserToSupabase(resolvedUser) }
        }

        val remotePilot = runCatching { api.getPilotProfile("eq.${resolvedUser.id}").body()?.firstOrNull() }.getOrNull()
        if (remotePilot != null) {
            dao.insertPilotProfile(remotePilot.toEntity())
        } else if (dao.getPilotProfile(resolvedUser.id) == null) {
            val createdProfile = PilotProfileEntity(
                userId = resolvedUser.id,
                droneType = meta?.drone_type ?: DroneType.QUADCOPTER.apiValue,
                tankCapacity = meta?.tank_capacity ?: 10.0,
                licenseDocumentUri = meta?.license_document_uri.orEmpty(),
                bankUpiId = meta?.bank_upi_id.orEmpty()
            )
            dao.insertPilotProfile(createdProfile)
            runCatching { pushPilotProfileToSupabase(createdProfile) }
        }

        return AuthResult.Success(resolvedUser)
    }

    suspend fun validateOrRefreshSession(session: PersistedAuthSession): SessionValidationResult {
        val localUser = dao.getUserById(session.userId)
            ?: dao.getUserByEmail(normalizeEmail(session.email))

        val api = SupabaseClient.api
            ?: return if (localUser != null) {
                SessionValidationResult.OfflineKeepLocal
            } else {
                SessionValidationResult.ExpiredOrRevoked("Please sign in with your email OTP.")
            }

        return withContext(Dispatchers.IO) {
            val nowSec = System.currentTimeMillis() / 1000L
            try {
                // Case 1: Freshly registered session before OTP login where tokens may be absent
                if (session.accessToken.isBlank() && session.refreshToken.isBlank()) {
                    val remoteExists = runCatching { api.getUsersById("eq.${session.userId}") }.getOrNull()
                    if (remoteExists != null && remoteExists.isSuccessful && remoteExists.body()?.isEmpty() == true) {
                        return@withContext SessionValidationResult.ExpiredOrRevoked(
                            "Account session is no longer active. Please sign in again."
                        )
                    }
                    return@withContext if (localUser != null) {
                        SessionValidationResult.Valid(
                            user = localUser,
                            accessToken = "",
                            refreshToken = "",
                            expiresAtEpochSec = 0L
                        )
                    } else {
                        SessionValidationResult.OfflineKeepLocal
                    }
                }

                // Case 2: Access token nearing expiry -> proactively refresh via refresh_token
                val needsRefresh = session.expiresAtEpochSec > 0L && (session.expiresAtEpochSec - nowSec) <= 300L
                if (needsRefresh && session.refreshToken.isNotBlank()) {
                    val refreshResp = api.authRefreshSession(SupabaseRefreshTokenRequest(session.refreshToken))
                    if (refreshResp.isSuccessful) {
                        val body = refreshResp.body()
                        val newAccess = body?.access_token.orEmpty()
                        val newRefresh = body?.refresh_token?.takeIf { it.isNotBlank() } ?: session.refreshToken
                        val newExp = body?.expires_at
                            ?: body?.expires_in?.let { nowSec + it }
                            ?: (nowSec + 3600L)
                        if (newAccess.isNotBlank()) {
                            val hydrated = (hydratePilotAfterOtpVerification(
                                api = api,
                                cleanEmail = normalizeEmail(session.email),
                                authUser = body?.user
                            ) as? AuthResult.Success)?.user ?: localUser
                            if (hydrated != null) {
                                return@withContext SessionValidationResult.Valid(
                                    user = hydrated,
                                    accessToken = newAccess,
                                    refreshToken = newRefresh,
                                    expiresAtEpochSec = newExp
                                )
                            }
                        }
                    } else if (refreshResp.code() in listOf(400, 401, 403)) {
                        return@withContext SessionValidationResult.ExpiredOrRevoked(
                            "Your session expired. Please sign in again with Email OTP."
                        )
                    }
                }

                // Case 3: Verify active access token against Supabase /auth/v1/user
                if (session.accessToken.isNotBlank()) {
                    val userResp = api.authGetUser("Bearer ${session.accessToken}")
                    if (userResp.isSuccessful) {
                        val authUser = userResp.body()
                        val hydrated = (hydratePilotAfterOtpVerification(
                            api = api,
                            cleanEmail = normalizeEmail(authUser?.email ?: session.email),
                            authUser = authUser
                        ) as? AuthResult.Success)?.user ?: localUser
                        if (hydrated != null) {
                            return@withContext SessionValidationResult.Valid(
                                user = hydrated,
                                accessToken = session.accessToken,
                                refreshToken = session.refreshToken,
                                expiresAtEpochSec = session.expiresAtEpochSec
                            )
                        }
                    } else if (userResp.code() == 401 || userResp.code() == 403) {
                        // Attempt one refresh before invalidating session
                        if (session.refreshToken.isNotBlank()) {
                            val retryRefresh = api.authRefreshSession(SupabaseRefreshTokenRequest(session.refreshToken))
                            if (retryRefresh.isSuccessful) {
                                val body = retryRefresh.body()
                                val newAccess = body?.access_token.orEmpty()
                                val newRefresh = body?.refresh_token?.takeIf { it.isNotBlank() } ?: session.refreshToken
                                val newExp = body?.expires_at
                                    ?: body?.expires_in?.let { nowSec + it }
                                    ?: (nowSec + 3600L)
                                val hydrated = (hydratePilotAfterOtpVerification(
                                    api = api,
                                    cleanEmail = normalizeEmail(session.email),
                                    authUser = body?.user
                                ) as? AuthResult.Success)?.user ?: localUser
                                if (hydrated != null && newAccess.isNotBlank()) {
                                    return@withContext SessionValidationResult.Valid(
                                        user = hydrated,
                                        accessToken = newAccess,
                                        refreshToken = newRefresh,
                                        expiresAtEpochSec = newExp
                                    )
                                }
                            } else if (retryRefresh.code() in listOf(400, 401, 403)) {
                                return@withContext SessionValidationResult.ExpiredOrRevoked(
                                    "Your session expired. Please sign in again with Email OTP."
                                )
                            }
                        } else {
                            return@withContext SessionValidationResult.ExpiredOrRevoked(
                                "Your session expired. Please sign in again with Email OTP."
                            )
                        }
                    }
                }

                SessionValidationResult.OfflineKeepLocal
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: IOException) {
                SessionValidationResult.OfflineKeepLocal
            } catch (_: Throwable) {
                SessionValidationResult.OfflineKeepLocal
            }
        }
    }

    suspend fun logoutFromBackend(accessToken: String?) {
        if (accessToken.isNullOrBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                SupabaseClient.api?.authLogout("Bearer $accessToken")
            }
        }
    }

    private suspend fun findRemoteUserByEmail(
        api: SupabasePostgrestApi,
        cleanEmail: String
    ): SupabaseUserWithEmailDto? {
        if (!remoteUsersHasEmailColumn) return null
        val resp = api.getUsersByEmail("ilike.$cleanEmail")
        if (resp.isSuccessful) {
            return resp.body()?.firstOrNull()
        }
        val errTxt = resp.errorBody()?.string().orEmpty()
        if (resp.code() in 400..404 &&
            (errTxt.contains("email", ignoreCase = true) ||
                errTxt.contains("42703") ||
                errTxt.contains("PGRST204"))
        ) {
            remoteUsersHasEmailColumn = false
            return null
        }
        return null
    }

    private suspend fun findRemoteUserByPhone(
        api: SupabasePostgrestApi,
        cleanPhone: String,
        preferredRole: UserRole
    ): SupabaseUserDto? {
        val exactResp = api.getUsersByPhone("eq.$cleanPhone")
        if (!exactResp.isSuccessful) {
            throw IllegalStateException("Cloud database error (HTTP ${exactResp.code()})")
        }
        exactResp.body()?.firstOrNull()?.let { return it }

        if (cleanPhone.length >= 10) {
            val last10 = cleanPhone.takeLast(10)
            val fuzzyResp = api.getUsersByPhone("like.*$last10")
            if (!fuzzyResp.isSuccessful) {
                throw IllegalStateException("Cloud database error (HTTP ${fuzzyResp.code()})")
            }
            val candidates = fuzzyResp.body().orEmpty().filter {
                it.phone.filter(Char::isDigit).takeLast(10) == last10
            }
            return candidates.firstOrNull {
                it.role.equals(preferredRole.apiValue, ignoreCase = true)
            } ?: candidates.firstOrNull()
        }
        return null
    }

    suspend fun loginByRole(phone: String, expectedRole: UserRole): AuthResult {
        if (expectedRole == UserRole.PILOT) {
            return AuthResult.Error("Please sign in using Email OTP verification.")
        }
        val cleanPhone = normalizePhoneOrNull(phone)
            ?: return AuthResult.Error("Enter a valid 10 to 15-digit mobile number.")
        var user = dao.getUserByPhone(cleanPhone)
        var cloudLookupError: String? = null
        if (user == null) {
            // Hydrate from Supabase if user exists on cloud DB (e.g. fresh app install)
            val api = SupabaseClient.api
            if (api == null) {
                cloudLookupError = "Cloud database is not configured (missing SUPABASE_ANON_KEY in Secrets). Sign up locally or configure Supabase to sign in with an existing number."
            } else {
                user = withContext(Dispatchers.IO) {
                    try {
                        val remoteUser = findRemoteUserByPhone(api, cleanPhone, expectedRole)
                            ?: return@withContext null
                        if (!isSafeIdentifier(remoteUser.id)) return@withContext null
                        val entity = UserEntity(
                            id = remoteUser.id,
                            phone = remoteUser.phone,
                            name = remoteUser.name,
                            role = remoteUser.role,
                            createdAt = remoteUser.created_at
                        )
                        dao.insertUser(entity)
                        if (entity.role.equals(UserRole.FARMER.apiValue, ignoreCase = true)) {
                            val profResp = api.getFarmerProfile("eq.${entity.id}")
                            profResp.body()?.firstOrNull()?.let { fp ->
                                dao.insertFarmerProfile(
                                    FarmerProfileEntity(
                                        userId = fp.user_id,
                                        farmerIdNumber = fp.farmer_id_number,
                                        coins = fp.coins
                                    )
                                )
                            }
                        }
                        entity
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        cloudLookupError = "Unable to connect to cloud database (${t.localizedMessage ?: "network error"}). Please check your internet connection."
                        null
                    }
                }
            }
        }
        if (user == null) {
            return AuthResult.Error(
                cloudLookupError ?: "No ${expectedRole.apiValue} account found for this number. Please sign up."
            )
        }
        if (!user.role.equals(expectedRole.apiValue, ignoreCase = true)) {
            return AuthResult.Error("This number is registered under a different role.")
        }
        return AuthResult.Success(user)
    }

    suspend fun getUserById(userId: String): UserEntity? = dao.getUserById(userId)

    suspend fun loginAdmin(phone: String, passcode: String): AuthResult {
        return AuthResult.Error("Operations admin access is disabled in the public Pilot app.")
    }

    fun observeFarmerProfile(farmerId: String): Flow<FarmerProfileEntity?> =
        dao.observeFarmerProfile(farmerId)

    fun observePilotProfile(pilotId: String): Flow<PilotProfileEntity?> =
        dao.observePilotProfile(pilotId)

    private fun resolveFieldCenter(order: OrderEntity, points: List<LatLngPoint>): LatLngPoint {
        if (order.fieldCenterLat != 0.0 || order.fieldCenterLng != 0.0) {
            return LatLngPoint(order.fieldCenterLat, order.fieldCenterLng)
        }
        return GeoPolygonCalculator.calculatePolygonCenter(points)
            ?: points.firstOrNull()
            ?: LatLngPoint(20.0059, 73.7897)
    }

    private fun mapOrderToDetails(
        order: OrderEntity,
        userMap: Map<String, UserEntity>,
        pilotMap: Map<String, PilotProfileEntity>
    ): OrderWithDetails {
        val farmer = userMap[order.farmerId]
        val pilotUser = order.assignedPilotId?.let { userMap[it] }
        val pilotProfile = order.assignedPilotId?.let { pilotMap[it] }
        val points = GeoPolygonCalculator.decodePolygon(order.fieldPolygonCoordinatesJson)
        val computedDistance = if (
            pilotProfile?.currentLat != null &&
            pilotProfile.currentLng != null &&
            (order.fieldCenterLat != 0.0 || order.fieldCenterLng != 0.0 || points.isNotEmpty())
        ) {
            val fieldCenter = resolveFieldCenter(order, points)
            GeoPolygonCalculator.calculateDistanceKm(
                LatLngPoint(pilotProfile.currentLat, pilotProfile.currentLng),
                fieldCenter
            )
        } else {
            order.distanceKm
        }
        return OrderWithDetails(
            order = order,
            farmerName = farmer?.name ?: "Farmer",
            farmerPhone = farmer?.phone ?: "",
            pilotName = pilotUser?.name,
            pilotPhone = pilotUser?.phone,
            pilotDroneType = pilotProfile?.droneType,
            pilotRatingAverage = pilotProfile?.ratingAverage,
            polygonPoints = points,
            pilotLat = pilotProfile?.currentLat,
            pilotLng = pilotProfile?.currentLng,
            liveDistanceKm = computedDistance ?: order.distanceKm
        )
    }

    fun observeAllOrdersWithDetails(): Flow<List<OrderWithDetails>> =
        combine(
            dao.observeAllOrders(),
            dao.observeAllUsers(),
            dao.observeAllPilots()
        ) { orders, users, pilots ->
            val userMap = users.associateBy { it.id }
            val pilotMap = pilots.associateBy { it.userId }
            orders.map { order -> mapOrderToDetails(order, userMap, pilotMap) }
        }

    fun observeOrdersForFarmer(farmerId: String): Flow<List<OrderWithDetails>> =
        combine(
            dao.observeOrdersByFarmer(farmerId),
            dao.observeAllUsers(),
            dao.observeAllPilots()
        ) { orders, users, pilots ->
            val userMap = users.associateBy { it.id }
            val pilotMap = pilots.associateBy { it.userId }
            orders.map { order -> mapOrderToDetails(order, userMap, pilotMap) }
        }

    /**
     * Priority Dispatch Queue for a Pilot:
     * Only visible when Pilot is Online. Orders are prioritized by pilot tier & order recency,
     * excluding orders this pilot rejected.
     */
    fun observePilotQueue(pilotId: String): Flow<List<OrderWithDetails>> =
        combine(
            observeAllOrdersWithDetails(),
            dao.observeRejectedOrderIds(pilotId),
            dao.observePilotProfile(pilotId)
        ) { allOrders, rejectedIds, pilotProfile ->
            if (pilotProfile == null || !pilotProfile.isOnline) {
                emptyList()
            } else {
                val rejectedSet = rejectedIds.toSet()
                val priorityScore = GeoPolygonCalculator.calculateDispatchPriorityScore(pilotProfile)
                val pilotLoc = if (pilotProfile.currentLat != null && pilotProfile.currentLng != null) {
                    LatLngPoint(pilotProfile.currentLat, pilotProfile.currentLng)
                } else {
                    null
                }
                allOrders
                    .filter { it.order.orderStatus == OrderStatus.PENDING.apiValue && it.order.id !in rejectedSet }
                    .map { item ->
                        val dist = if (pilotLoc != null) {
                            val center = resolveFieldCenter(item.order, item.polygonPoints)
                            GeoPolygonCalculator.calculateDistanceKm(pilotLoc, center)
                        } else {
                            item.liveDistanceKm
                        }
                        item.copy(
                            pilotLat = pilotProfile.currentLat,
                            pilotLng = pilotProfile.currentLng,
                            liveDistanceKm = dist
                        )
                    }
                    .sortedWith(
                        compareByDescending<OrderWithDetails> {
                            // High-acreage orders prioritized for high-priority pilots (Hexacopter + high rating)
                            if (priorityScore >= 115.0) it.order.acresToSpray else 0.0
                        }.thenByDescending { it.order.createdAt }
                    )
            }
        }

    fun observePilotAssignedOrders(pilotId: String): Flow<List<OrderWithDetails>> =
        combine(
            dao.observeOrdersByPilot(pilotId),
            dao.observeAllUsers(),
            dao.observeAllPilots()
        ) { orders, users, pilots ->
            val userMap = users.associateBy { it.id }
            val pilotMap = pilots.associateBy { it.userId }
            orders.map { order -> mapOrderToDetails(order, userMap, pilotMap) }
        }

    suspend fun placeOrder(
        farmerId: String,
        locationAddress: String,
        polygonPoints: List<LatLngPoint>,
        paymentMethod: PaymentMethod,
        razorpayPaymentId: String? = null
    ): OrderEntity {
        val computedAcres = GeoPolygonCalculator.calculatePolygonAreaAcres(polygonPoints)
        val tariff = GeoPolygonCalculator.calculateTariff(computedAcres)
        val center = GeoPolygonCalculator.calculatePolygonCenter(polygonPoints)
            ?: polygonPoints.firstOrNull()
            ?: LatLngPoint(20.0059, 73.7897)
        val order = OrderEntity(
            id = UUID.randomUUID().toString(),
            farmerId = farmerId,
            assignedPilotId = null,
            fieldPolygonCoordinatesJson = GeoPolygonCalculator.encodePolygon(polygonPoints),
            acresToSpray = tariff.acres,
            locationAddress = locationAddress.trim(),
            totalAmount = tariff.totalAmount,
            adminCommission = tariff.adminCommission,
            pilotEarnings = tariff.pilotEarnings,
            paymentMethod = paymentMethod.apiValue,
            paymentStatus = if (paymentMethod == PaymentMethod.ONLINE) {
                PaymentStatus.PAID.apiValue
            } else {
                PaymentStatus.PENDING.apiValue
            },
            orderStatus = OrderStatus.PENDING.apiValue,
            pilotEtaMinutes = 15,
            initialEtaMinutes = 15,
            distanceKm = null,
            fieldCenterLat = center.lat,
            fieldCenterLng = center.lng,
            acceptedAt = null,
            razorpayPaymentId = razorpayPaymentId
        )
        dao.insertOrder(order)
        withContext(Dispatchers.IO) {
            runCatching {
                SupabaseClient.api?.upsertOrder(
                    SupabaseOrderDto(
                        id = order.id,
                        farmer_id = order.farmerId,
                        assigned_pilot_id = order.assignedPilotId,
                        field_polygon_coordinates = polygonPoints.map { SupabaseCoordDto(it.lat, it.lng) },
                        acres_to_spray = order.acresToSpray,
                        location_address = order.locationAddress,
                        total_amount = order.totalAmount,
                        admin_commission = order.adminCommission,
                        pilot_earnings = order.pilotEarnings,
                        payment_method = order.paymentMethod,
                        payment_status = order.paymentStatus,
                        order_status = order.orderStatus,
                        pilot_eta_minutes = order.pilotEtaMinutes,
                        initial_eta_minutes = order.initialEtaMinutes,
                        distance_km = order.distanceKm,
                        field_center_lat = order.fieldCenterLat,
                        field_center_lng = order.fieldCenterLng,
                        accepted_at = order.acceptedAt,
                        pilot_rating = order.pilotRating,
                        razorpay_payment_id = order.razorpayPaymentId,
                        created_at = order.createdAt
                    )
                )
            }
        }
        return order
    }

    suspend fun syncFarmerStateFromSupabase(farmerId: String) = withContext(Dispatchers.IO) {
        val api = SupabaseClient.api ?: return@withContext
        runCatching {
            api.getFarmerProfile("eq.$farmerId").body()?.firstOrNull()?.let { fp ->
                dao.insertFarmerProfile(
                    FarmerProfileEntity(
                        userId = fp.user_id,
                        farmerIdNumber = fp.farmer_id_number,
                        coins = fp.coins
                    )
                )
            }
        }
        runCatching {
            val orders = api.getOrdersForFarmer("eq.$farmerId").body().orEmpty()
            orders.forEach { dto ->
                val poly = dto.field_polygon_coordinates.map { LatLngPoint(it.lat, it.lng) }
                dao.insertOrder(
                    OrderEntity(
                        id = dto.id,
                        farmerId = dto.farmer_id,
                        assignedPilotId = dto.assigned_pilot_id,
                        fieldPolygonCoordinatesJson = GeoPolygonCalculator.encodePolygon(poly),
                        acresToSpray = dto.acres_to_spray,
                        locationAddress = dto.location_address,
                        totalAmount = dto.total_amount,
                        adminCommission = dto.admin_commission,
                        pilotEarnings = dto.pilot_earnings,
                        paymentMethod = dto.payment_method,
                        paymentStatus = dto.payment_status,
                        orderStatus = dto.order_status,
                        pilotEtaMinutes = dto.pilot_eta_minutes,
                        initialEtaMinutes = dto.initial_eta_minutes,
                        distanceKm = dto.distance_km,
                        fieldCenterLat = dto.field_center_lat,
                        fieldCenterLng = dto.field_center_lng,
                        acceptedAt = dto.accepted_at,
                        pilotRating = dto.pilot_rating,
                        razorpayPaymentId = dto.razorpay_payment_id,
                        createdAt = dto.created_at
                    )
                )
                val pilotId = dto.assigned_pilot_id
                if (!pilotId.isNullOrBlank()) {
                    api.getUsersById("eq.$pilotId").body()?.firstOrNull()?.let { pu ->
                        dao.insertUser(
                            UserEntity(
                                id = pu.id,
                                phone = pu.phone,
                                name = pu.name,
                                role = pu.role,
                                createdAt = pu.created_at
                            )
                        )
                    }
                    api.getPilotProfile("eq.$pilotId").body()?.firstOrNull()?.let { pp ->
                        dao.insertPilotProfile(
                            PilotProfileEntity(
                                userId = pp.user_id,
                                droneType = pp.drone_type,
                                tankCapacity = pp.tank_capacity,
                                licenseDocumentUri = pp.license_document_uri,
                                isLicenseVerified = pp.is_license_verified,
                                bankUpiId = pp.bank_upi_id,
                                walletBalance = pp.wallet_balance,
                                isOnline = pp.is_online,
                                ratingAverage = pp.rating_average,
                                ratingCount = pp.rating_count,
                                currentLat = pp.current_lat,
                                currentLng = pp.current_lng,
                                locationUpdatedAt = pp.location_updated_at
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun syncPilotStateFromSupabase(pilotId: String): CloudSyncResult = withContext(Dispatchers.IO) {
        val api = SupabaseClient.api ?: return@withContext CloudSyncResult.NotConfigured
        flushPendingCloudWrites(pilotId)
        try {
            val profileResp = api.getPilotProfile("eq.$pilotId")
            if (!profileResp.isSuccessful) {
                return@withContext CloudSyncResult.Error("Sync temporarily unavailable")
            }
            val remoteProfile = profileResp.body()?.firstOrNull()
            if (remoteProfile == null) {
                var targetUserId = pilotId
                val localUser = dao.getUserById(pilotId)
                if (localUser != null) {
                    val userOk = pushUserToSupabase(localUser)
                    if (!userOk) {
                        val existingRemote = api.getUsersByPhone("eq.${localUser.phone}")
                            .body()?.firstOrNull()
                        if (existingRemote != null && existingRemote.role.equals(UserRole.PILOT.apiValue, ignoreCase = true)) {
                            targetUserId = existingRemote.id
                        }
                    }
                }
                dao.getPilotProfile(pilotId)?.let { local ->
                    pushPilotProfileToSupabase(local.copy(userId = targetUserId))
                }
            } else {
                val local = dao.getPilotProfile(pilotId)
                if (local == null) {
                    dao.insertPilotProfile(remoteProfile.toEntity())
                } else if (pilotId !in dirtyProfiles) {
                    dao.updatePilotProfile(
                        local.copy(
                            isLicenseVerified = remoteProfile.is_license_verified,
                            ratingAverage = remoteProfile.rating_average,
                            ratingCount = remoteProfile.rating_count,
                            walletBalance = remoteProfile.wallet_balance,
                            bankUpiId = remoteProfile.bank_upi_id.takeIf { it.isNotBlank() } ?: local.bankUpiId
                        )
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            return@withContext CloudSyncResult.Error("Sync temporarily unavailable")
        }

        return@withContext try {
            val pilotOrdersResp = api.getOrdersForPilot("eq.$pilotId")
            val recentOrdersResp = api.getRecentOrders()
            if (!pilotOrdersResp.isSuccessful && !recentOrdersResp.isSuccessful) {
                return@withContext CloudSyncResult.Error("Sync temporarily unavailable")
            }
            val mergedOrders = LinkedHashMap<String, SupabaseOrderDto>()
            pilotOrdersResp.body().orEmpty().forEach { mergedOrders[it.id] = it }
            recentOrdersResp.body().orEmpty().forEach { mergedOrders[it.id] = it }
            val orders = mergedOrders.values.toList()
            orders.forEach { dto ->
                if (dto.id !in dirtyOrders) {
                    val poly = dto.field_polygon_coordinates.map { LatLngPoint(it.lat, it.lng) }
                    dao.insertOrder(
                        OrderEntity(
                            id = dto.id,
                            farmerId = dto.farmer_id,
                            assignedPilotId = dto.assigned_pilot_id,
                            fieldPolygonCoordinatesJson = GeoPolygonCalculator.encodePolygon(poly),
                            acresToSpray = dto.acres_to_spray,
                            locationAddress = dto.location_address,
                            totalAmount = dto.total_amount,
                            adminCommission = dto.admin_commission,
                            pilotEarnings = dto.pilot_earnings,
                            paymentMethod = dto.payment_method,
                            paymentStatus = dto.payment_status,
                            orderStatus = dto.order_status,
                            pilotEtaMinutes = dto.pilot_eta_minutes,
                            initialEtaMinutes = dto.initial_eta_minutes,
                            distanceKm = dto.distance_km,
                            fieldCenterLat = dto.field_center_lat,
                            fieldCenterLng = dto.field_center_lng,
                            acceptedAt = dto.accepted_at,
                            pilotRating = dto.pilot_rating,
                            razorpayPaymentId = dto.razorpay_payment_id,
                            createdAt = dto.created_at
                        )
                    )
                }
                if (dao.getUserById(dto.farmer_id) == null) {
                    api.getUsersById("eq.${dto.farmer_id}").body()?.firstOrNull()?.let { fu ->
                        dao.insertUser(
                            UserEntity(
                                id = fu.id,
                                phone = fu.phone,
                                name = fu.name,
                                role = fu.role,
                                createdAt = fu.created_at
                            )
                        )
                    }
                }
            }
            // Best-effort sync of pilot withdrawal records if table exists in Supabase
            runCatching {
                api.getPilotWithdrawals("eq.$pilotId").body()?.forEach { wd ->
                    if (wd.id !in dirtyWithdrawals) {
                        dao.insertPilotWithdrawal(
                            PilotWithdrawalEntity(
                                id = wd.id,
                                pilotId = wd.pilot_id,
                                amount = wd.amount,
                                method = wd.method,
                                accountHolderName = wd.account_holder_name,
                                bankAccountNumber = wd.bank_account_last4,
                                ifscCode = wd.ifsc_code,
                                bankName = wd.bank_name,
                                upiId = wd.upi_id,
                                referenceId = wd.reference_id,
                                status = wd.status,
                                createdAt = wd.created_at
                            )
                        )
                    }
                }
            }
            CloudSyncResult.Success(orders.size)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            CloudSyncResult.Error("Sync temporarily unavailable")
        }
    }

    suspend fun togglePilotOnline(pilotId: String, isOnline: Boolean) {
        val profile = dao.getPilotProfile(pilotId) ?: return
        val updated = profile.copy(isOnline = isOnline)
        dao.updatePilotProfile(updated)
        markDirty(KEY_DIRTY_PROFILES, dirtyProfiles, updated.userId)
        pushPilotProfileToSupabase(updated)
    }

    suspend fun updatePilotAccountDetails(
        pilotId: String,
        name: String,
        droneType: DroneType,
        tankCapacity: Double,
        bankUpiId: String,
        licenseDocumentUri: String? = null
    ): AuthResult {
        val cleanName = name.trim()
        val cleanUpi = bankUpiId.trim()
        if (cleanName.isBlank()) {
            return AuthResult.Error("Pilot name cannot be empty.")
        }
        if (tankCapacity <= 0.0) {
            return AuthResult.Error("Enter a valid spray tank capacity in liters.")
        }
        if (cleanUpi.isBlank() || !cleanUpi.contains("@")) {
            return AuthResult.Error("Enter a valid UPI ID (e.g. pilot@okaxis).")
        }

        val existingUser = dao.getUserById(pilotId)
            ?: return AuthResult.Error("Pilot account not found.")
        val existingProfile = dao.getPilotProfile(pilotId)
            ?: return AuthResult.Error("Pilot profile not found.")

        val updatedUser = existingUser.copy(name = cleanName)
        val cleanLicense = licenseDocumentUri?.trim()?.takeIf { it.isNotBlank() }
        val updatedProfile = existingProfile.copy(
            droneType = droneType.apiValue,
            tankCapacity = tankCapacity,
            bankUpiId = cleanUpi,
            licenseDocumentUri = cleanLicense ?: existingProfile.licenseDocumentUri
        )

        database.withTransaction {
            dao.insertUser(updatedUser)
            dao.updatePilotProfile(updatedProfile)
        }
        markDirty(KEY_DIRTY_USERS, dirtyUsers, updatedUser.id)
        markDirty(KEY_DIRTY_PROFILES, dirtyProfiles, updatedProfile.userId)
        pushUserToSupabase(updatedUser)
        pushPilotProfileToSupabase(updatedProfile)

        return AuthResult.Success(updatedUser)
    }

    suspend fun updatePilotLiveLocation(pilotId: String, lat: Double, lng: Double) {
        var updatedPilot: PilotProfileEntity? = null
        val updatedOrders = mutableListOf<OrderEntity>()
        database.withTransaction {
            val pilot = dao.getPilotProfile(pilotId) ?: return@withTransaction
            val now = System.currentTimeMillis()
            val modifiedPilot = pilot.copy(
                currentLat = lat,
                currentLng = lng,
                locationUpdatedAt = now
            )
            dao.updatePilotProfile(modifiedPilot)
            updatedPilot = modifiedPilot

            val droneType = DroneType.fromApi(pilot.droneType)
            val activeOrders = dao.getActiveOrdersByPilot(pilotId)
            activeOrders.forEach { order ->
                val points = GeoPolygonCalculator.decodePolygon(order.fieldPolygonCoordinatesJson)
                val fieldCenter = resolveFieldCenter(order, points)
                val distKm = GeoPolygonCalculator.calculateDistanceKm(LatLngPoint(lat, lng), fieldCenter)
                val updatedEta = if (order.orderStatus == OrderStatus.ACCEPTED.apiValue) {
                    GeoPolygonCalculator.estimateArrivalMinutes(distKm, droneType)
                } else {
                    0
                }
                val modifiedOrder = order.copy(
                    distanceKm = distKm,
                    pilotEtaMinutes = updatedEta,
                    fieldCenterLat = fieldCenter.lat,
                    fieldCenterLng = fieldCenter.lng
                )
                dao.updateOrder(modifiedOrder)
                updatedOrders.add(modifiedOrder)
            }
        }

        val now = System.currentTimeMillis()
        if (now - lastPilotLocationPushMs >= 8_000L) {
            lastPilotLocationPushMs = now
            withContext(Dispatchers.IO) {
                runCatching {
                    val api = SupabaseClient.api ?: return@runCatching
                    updatedPilot?.let { api.upsertPilotProfile(it.toSupabaseDto()) }
                    updatedOrders.forEach { api.upsertOrder(it.toSupabaseDto()) }
                }
            }
        }
    }

    suspend fun topUpPilotWallet(pilotId: String, amount: Double) {
        if (amount !in 1.0..100_000.0) return
        var updatedProfile: PilotProfileEntity? = null
        database.withTransaction {
            val profile = dao.getPilotProfile(pilotId) ?: return@withTransaction
            val updatedBalance = ((profile.walletBalance + amount) * 100.0).roundToInt() / 100.0
            val next = profile.copy(walletBalance = updatedBalance)
            dao.updatePilotProfile(next)
            updatedProfile = next
        }
        updatedProfile?.let { prof ->
            markDirty(KEY_DIRTY_PROFILES, dirtyProfiles, prof.userId)
            pushPilotProfileToSupabase(prof)
        }
    }

    fun observePilotBankAccount(pilotId: String): Flow<PilotBankAccountEntity?> =
        dao.observePilotBankAccount(pilotId)

    fun observePilotWithdrawals(pilotId: String): Flow<List<PilotWithdrawalEntity>> =
        dao.observePilotWithdrawals(pilotId)

    suspend fun savePilotBankAccount(
        pilotId: String,
        accountHolderName: String,
        accountNumber: String,
        ifscCode: String,
        bankName: String
    ): AuthResult {
        val cleanHolder = accountHolderName.trim()
        val cleanAcct = accountNumber.trim().filter(Char::isDigit)
        val cleanIfsc = ifscCode.trim().uppercase(Locale.US)
        val cleanBank = bankName.trim()

        if (cleanHolder.isBlank()) {
            return AuthResult.Error("Enter the bank account holder's full name.")
        }
        if (!VALID_BANK_ACCOUNT_REGEX.matches(cleanAcct)) {
            return AuthResult.Error("Enter a valid 9 to 18-digit bank account number.")
        }
        if (!VALID_IFSC_REGEX.matches(cleanIfsc)) {
            return AuthResult.Error("Enter a valid 11-character IFSC code (e.g. HDFC0001234).")
        }

        val user = dao.getUserById(pilotId)
            ?: return AuthResult.Error("Pilot account not found.")

        dao.upsertPilotBankAccount(
            PilotBankAccountEntity(
                pilotId = pilotId,
                accountHolderName = cleanHolder,
                accountNumber = cleanAcct,
                ifscCode = cleanIfsc,
                bankName = cleanBank,
                updatedAt = System.currentTimeMillis()
            )
        )
        return AuthResult.Success(user)
    }

    suspend fun withdrawPilotEarnings(
        pilotId: String,
        amount: Double,
        method: WithdrawalMethod,
        accountHolderName: String = "",
        bankAccountNumber: String = "",
        ifscCode: String = "",
        bankName: String = "",
        upiId: String = ""
    ): WithdrawResult {
        val roundedAmount = (amount * 100.0).roundToInt() / 100.0
        if (roundedAmount < 1.0) {
            return WithdrawResult.Error("Minimum withdrawal amount is ₹1.")
        }

        val cleanHolder = accountHolderName.trim()
        val cleanAcct = bankAccountNumber.trim().filter(Char::isDigit)
        val cleanIfsc = ifscCode.trim().uppercase(Locale.US)
        val cleanBank = bankName.trim()
        val cleanUpi = upiId.trim()

        if (method == WithdrawalMethod.BANK_TRANSFER) {
            if (cleanHolder.isBlank()) {
                return WithdrawResult.Error("Enter the bank account holder's name.")
            }
            if (!VALID_BANK_ACCOUNT_REGEX.matches(cleanAcct)) {
                return WithdrawResult.Error("Enter a valid 9 to 18-digit bank account number.")
            }
            if (!VALID_IFSC_REGEX.matches(cleanIfsc)) {
                return WithdrawResult.Error("Enter a valid 11-character IFSC code (e.g. SBIN0001234).")
            }
        } else {
            val parts = cleanUpi.split("@")
            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                return WithdrawResult.Error("Enter a valid payout UPI ID (e.g. name@okaxis).")
            }
        }

        var updatedProfileForSync: PilotProfileEntity? = null
        val txResult = database.withTransaction {
            val profile = dao.getPilotProfile(pilotId)
                ?: return@withTransaction WithdrawResult.Error("Pilot profile not found.")
            val completedOrders = dao.getCompletedOrdersByPilot(pilotId)
            val onlineEarnings = completedOrders
                .filter { it.paymentMethod == PaymentMethod.ONLINE.apiValue }
                .sumOf { it.pilotEarnings }
            val alreadyWithdrawn = dao.getPilotWithdrawals(pilotId)
                .filter { it.status != WithdrawalStatus.FAILED.apiValue }
                .sumOf { it.amount }
            val available = ((onlineEarnings - alreadyWithdrawn) * 100.0).roundToInt() / 100.0

            if (available < 1.0) {
                return@withTransaction WithdrawResult.Error(
                    "No withdrawable online earnings available yet."
                )
            }
            if (roundedAmount > available + 0.01) {
                return@withTransaction WithdrawResult.Error(
                    "Requested ₹${String.format(Locale.US, "%.2f", roundedAmount)} exceeds available ₹${String.format(Locale.US, "%.2f", available)}."
                )
            }
            val finalAmount = roundedAmount.coerceAtMost(available)

            val reference = "KVWD-${UUID.randomUUID().toString().take(8).uppercase(Locale.US)}"
            val withdrawal = PilotWithdrawalEntity(
                id = UUID.randomUUID().toString(),
                pilotId = pilotId,
                amount = finalAmount,
                method = method.apiValue,
                accountHolderName = if (method == WithdrawalMethod.BANK_TRANSFER) cleanHolder else "",
                bankAccountNumber = if (method == WithdrawalMethod.BANK_TRANSFER) cleanAcct else "",
                ifscCode = if (method == WithdrawalMethod.BANK_TRANSFER) cleanIfsc else "",
                bankName = if (method == WithdrawalMethod.BANK_TRANSFER) cleanBank else "",
                upiId = if (method == WithdrawalMethod.UPI) cleanUpi else "",
                referenceId = reference,
                status = WithdrawalStatus.COMPLETED.apiValue,
                createdAt = System.currentTimeMillis()
            )
            dao.insertPilotWithdrawal(withdrawal)

            if (method == WithdrawalMethod.BANK_TRANSFER) {
                dao.upsertPilotBankAccount(
                    PilotBankAccountEntity(
                        pilotId = pilotId,
                        accountHolderName = cleanHolder,
                        accountNumber = cleanAcct,
                        ifscCode = cleanIfsc,
                        bankName = cleanBank,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } else if (cleanUpi != profile.bankUpiId) {
                val updated = profile.copy(bankUpiId = cleanUpi)
                dao.updatePilotProfile(updated)
                updatedProfileForSync = updated
            }

            WithdrawResult.Success(withdrawal)
        }

        if (txResult is WithdrawResult.Success) {
            val w = txResult.withdrawal
            updatedProfileForSync?.let {
                markDirty(KEY_DIRTY_PROFILES, dirtyProfiles, it.userId)
                pushPilotProfileToSupabase(it)
            }
            markDirty(KEY_DIRTY_WITHDRAWALS, dirtyWithdrawals, w.id)
            pushWithdrawalToSupabase(w)
        }
        return txResult
    }

    /**
     * Enforces the Cash Order Wallet Rule:
     * If payment_method == 'cash', pilot can only accept if wallet_balance >= 10% admin_commission.
     * Accepting immediately deducts that 10% commission.
     * Also calculates distance from the pilot's live GPS location to the farmer's field center
     * and sets initial real-time ETA.
     */
    suspend fun acceptOrder(
        pilotId: String,
        orderId: String,
        pilotLocation: LatLngPoint? = null
    ): AcceptOrderResult {
        return database.withTransaction {
            val order = dao.getOrderById(orderId)
                ?: return@withTransaction AcceptOrderResult.Error("Order not found.")
            if (order.orderStatus != OrderStatus.PENDING.apiValue) {
                return@withTransaction AcceptOrderResult.Error("Order is no longer available.")
            }
            val existingPilot = dao.getPilotProfile(pilotId)
                ?: return@withTransaction AcceptOrderResult.Error("Pilot profile not found.")

            val now = System.currentTimeMillis()
            val pilotWithLocation = if (pilotLocation != null) {
                existingPilot.copy(
                    currentLat = pilotLocation.lat,
                    currentLng = pilotLocation.lng,
                    locationUpdatedAt = now
                )
            } else {
                existingPilot
            }

            val isCashOrder = order.paymentMethod == PaymentMethod.CASH.apiValue
            if (isCashOrder) {
                if (pilotWithLocation.walletBalance < order.adminCommission) {
                    return@withTransaction AcceptOrderResult.InsufficientWallet(
                        requiredCommission = order.adminCommission,
                        currentBalance = pilotWithLocation.walletBalance
                    )
                }
                val newWallet = ((pilotWithLocation.walletBalance - order.adminCommission) * 100.0).roundToInt() / 100.0
                dao.updatePilotProfile(pilotWithLocation.copy(walletBalance = newWallet))
                dao.insertCommissionLog(
                    CommissionLogEntity(
                        orderId = order.id,
                        pilotId = pilotId,
                        amount = order.adminCommission,
                        paymentMethod = order.paymentMethod,
                        note = "10% Cash Order Commission deducted on acceptance"
                    )
                )
            } else if (pilotLocation != null) {
                dao.updatePilotProfile(pilotWithLocation)
            }

            val effectiveLoc = pilotLocation ?: if (
                pilotWithLocation.currentLat != null && pilotWithLocation.currentLng != null
            ) {
                LatLngPoint(pilotWithLocation.currentLat, pilotWithLocation.currentLng)
            } else {
                null
            }
            val fieldCenter = resolveFieldCenter(
                order,
                GeoPolygonCalculator.decodePolygon(order.fieldPolygonCoordinatesJson)
            )
            val distKm = effectiveLoc?.let { GeoPolygonCalculator.calculateDistanceKm(it, fieldCenter) }
            val droneType = DroneType.fromApi(pilotWithLocation.droneType)
            val eta = if (distKm != null) {
                GeoPolygonCalculator.estimateArrivalMinutes(distKm, droneType)
            } else if (droneType == DroneType.HEXACOPTER) {
                12
            } else {
                16
            }

            val acceptedOrder = order.copy(
                assignedPilotId = pilotId,
                orderStatus = OrderStatus.ACCEPTED.apiValue,
                pilotEtaMinutes = eta,
                initialEtaMinutes = eta,
                distanceKm = distKm,
                fieldCenterLat = fieldCenter.lat,
                fieldCenterLng = fieldCenter.lng,
                acceptedAt = now
            )
            dao.updateOrder(acceptedOrder)
            AcceptOrderResult.Success
        }.also { result ->
            if (result is AcceptOrderResult.Success) {
                markDirty(KEY_DIRTY_ORDERS, dirtyOrders, orderId)
                markDirty(KEY_DIRTY_PROFILES, dirtyProfiles, pilotId)
                dao.getOrderById(orderId)?.let { pushOrderToSupabase(it) }
                dao.getPilotProfile(pilotId)?.let { pushPilotProfileToSupabase(it) }
            }
        }
    }

    suspend fun rejectOrder(pilotId: String, orderId: String) {
        dao.insertRejectedOrder(RejectedOrderEntity(orderId = orderId, pilotId = pilotId))
    }

    suspend fun startSpraying(orderId: String) {
        val order = dao.getOrderById(orderId) ?: return
        if (order.orderStatus == OrderStatus.ACCEPTED.apiValue) {
            val updated = order.copy(
                orderStatus = OrderStatus.IN_PROGRESS.apiValue,
                pilotEtaMinutes = 0
            )
            dao.updateOrder(updated)
            markDirty(KEY_DIRTY_ORDERS, dirtyOrders, updated.id)
            pushOrderToSupabase(updated)
        }
    }

    /**
     * Completes an order:
     * - Marks order_status = 'completed' and payment_status = 'paid'
     * - Awards +1 Harvest Coin to the Farmer (1 Order = 1 Coin)
     * - Logs 10% platform commission for Online orders (Cash orders were deducted on accept)
     */
    suspend fun completeOrder(orderId: String) {
        var completedOrder: OrderEntity? = null
        var updatedFarmer: FarmerProfileEntity? = null
        database.withTransaction {
            val order = dao.getOrderById(orderId) ?: return@withTransaction
            if (order.orderStatus == OrderStatus.COMPLETED.apiValue) return@withTransaction

            val nextOrder = order.copy(
                orderStatus = OrderStatus.COMPLETED.apiValue,
                paymentStatus = PaymentStatus.PAID.apiValue,
                pilotEtaMinutes = 0
            )
            dao.updateOrder(nextOrder)
            completedOrder = nextOrder

            val farmer = dao.getFarmerProfile(order.farmerId)
            if (farmer != null) {
                val nextFarmer = farmer.copy(coins = farmer.coins + 1)
                dao.updateFarmerProfile(nextFarmer)
                updatedFarmer = nextFarmer
            }

            if (order.paymentMethod == PaymentMethod.ONLINE.apiValue && order.assignedPilotId != null) {
                dao.insertCommissionLog(
                    CommissionLogEntity(
                        orderId = order.id,
                        pilotId = order.assignedPilotId,
                        amount = order.adminCommission,
                        paymentMethod = order.paymentMethod,
                        note = "10% Online Razorpay Commission settled on completion"
                    )
                )
            }
        }
        completedOrder?.let {
            markDirty(KEY_DIRTY_ORDERS, dirtyOrders, it.id)
            pushOrderToSupabase(it)
        }
        withContext(Dispatchers.IO) {
            runCatching {
                val api = SupabaseClient.api ?: return@runCatching
                val farmerId = completedOrder?.farmerId
                if (updatedFarmer != null) {
                    val uf = updatedFarmer!!
                    api.upsertFarmerProfile(
                        SupabaseFarmerProfileDto(
                            user_id = uf.userId,
                            farmer_id_number = uf.farmerIdNumber,
                            coins = uf.coins
                        )
                    )
                } else if (farmerId != null) {
                    api.getFarmerProfile("eq.$farmerId").body()?.firstOrNull()?.let { fp ->
                        api.upsertFarmerProfile(fp.copy(coins = fp.coins + 1))
                    }
                }
            }
        }
    }

    suspend fun ratePilotForOrder(orderId: String, rating: Int) {
        val clamped = rating.coerceIn(1, 5)
        database.withTransaction {
            val order = dao.getOrderById(orderId) ?: return@withTransaction
            val pilotId = order.assignedPilotId ?: return@withTransaction
            if (order.pilotRating != null) return@withTransaction

            dao.updateOrder(order.copy(pilotRating = clamped))
            val pilot = dao.getPilotProfile(pilotId) ?: return@withTransaction
            val newCount = pilot.ratingCount + 1
            val currentTotal = if (pilot.ratingCount == 0) 0.0 else pilot.ratingAverage * pilot.ratingCount
            val newAverage = (((currentTotal + clamped) / newCount) * 100.0).roundToInt() / 100.0
            dao.updatePilotProfile(
                pilot.copy(
                    ratingAverage = newAverage,
                    ratingCount = newCount
                )
            )
        }
        withContext(Dispatchers.IO) {
            runCatching {
                SupabaseClient.api?.patchOrderRating(
                    orderIdFilter = "eq.$orderId",
                    body = SupabaseOrderRatingPatchDto(pilot_rating = clamped)
                )
            }
        }
    }

    // --- Admin Flows ---

    fun observeAllPilotsWithUsers(): Flow<List<PilotWithUser>> =
        combine(dao.observeAllUsers(), dao.observeAllPilots()) { users, pilots ->
            val userMap = users.associateBy { it.id }
            pilots.mapNotNull { profile ->
                userMap[profile.userId]?.let { user -> PilotWithUser(user, profile) }
            }
        }

    fun observeAllFarmersWithUsers(): Flow<List<FarmerWithUser>> =
        combine(dao.observeAllUsers(), dao.observeAllFarmers()) { users, farmers ->
            val userMap = users.associateBy { it.id }
            farmers.mapNotNull { profile ->
                userMap[profile.userId]?.let { user -> FarmerWithUser(user, profile) }
            }
        }

    fun observePilotPerformanceStats(): Flow<List<PilotPerformanceStat>> =
        combine(observeAllPilotsWithUsers(), dao.observeAllOrders()) { pilots, orders ->
            pilots.map { (user, profile) ->
                val completedOrders = orders.filter {
                    it.assignedPilotId == user.id && it.orderStatus == OrderStatus.COMPLETED.apiValue
                }
                PilotPerformanceStat(
                    pilotId = user.id,
                    pilotName = user.name,
                    phone = user.phone,
                    droneType = DroneType.fromApi(profile.droneType),
                    tankCapacity = profile.tankCapacity,
                    isLicenseVerified = profile.isLicenseVerified,
                    licenseDocumentUri = profile.licenseDocumentUri,
                    ratingAverage = profile.ratingAverage,
                    walletBalance = profile.walletBalance,
                    completedOrdersCount = completedOrders.size,
                    totalEarnings = completedOrders.sumOf { it.pilotEarnings }
                )
            }.sortedByDescending { it.completedOrdersCount }
        }

    fun observeDailyOrderVolumes(): Flow<List<DailyOrderVolume>> =
        combine(dao.observeAllOrders()) { (orders) ->
            val fmt = SimpleDateFormat("MMM dd", Locale.getDefault())
            orders.groupBy { fmt.format(Date(it.createdAt)) }
                .map { (day, dayOrders) ->
                    DailyOrderVolume(
                        dayLabel = day,
                        count = dayOrders.size,
                        revenue = dayOrders.sumOf { it.totalAmount }
                    )
                }
                .take(7)
        }

    fun observeCommissionLogs(): Flow<List<CommissionLogEntity>> =
        dao.observeCommissionLogs()

    suspend fun setPilotLicenseVerified(pilotId: String, verified: Boolean) {
        val profile = dao.getPilotProfile(pilotId) ?: return
        val updated = profile.copy(isLicenseVerified = verified)
        dao.updatePilotProfile(updated)
        withContext(Dispatchers.IO) {
            runCatching { SupabaseClient.api?.upsertPilotProfile(updated.toSupabaseDto()) }
        }
    }

    private fun PilotProfileEntity.toSupabaseDto(): SupabasePilotProfileDto =
        SupabasePilotProfileDto(
            user_id = userId,
            drone_type = droneType,
            tank_capacity = tankCapacity,
            license_document_uri = licenseDocumentUri,
            is_license_verified = isLicenseVerified,
            bank_upi_id = bankUpiId,
            wallet_balance = walletBalance,
            is_online = isOnline,
            rating_average = ratingAverage,
            rating_count = ratingCount,
            current_lat = currentLat,
            current_lng = currentLng,
            location_updated_at = locationUpdatedAt
        )

    private fun SupabasePilotProfileDto.toEntity(): PilotProfileEntity =
        PilotProfileEntity(
            userId = user_id,
            droneType = drone_type,
            tankCapacity = tank_capacity,
            licenseDocumentUri = license_document_uri,
            isLicenseVerified = is_license_verified,
            bankUpiId = bank_upi_id,
            walletBalance = wallet_balance,
            isOnline = is_online,
            ratingAverage = rating_average,
            ratingCount = rating_count,
            currentLat = current_lat,
            currentLng = current_lng,
            locationUpdatedAt = location_updated_at
        )

    private fun OrderEntity.toSupabaseDto(): SupabaseOrderDto {
        val polygon = GeoPolygonCalculator.decodePolygon(fieldPolygonCoordinatesJson)
        return SupabaseOrderDto(
            id = id,
            farmer_id = farmerId,
            assigned_pilot_id = assignedPilotId,
            field_polygon_coordinates = polygon.map { SupabaseCoordDto(it.lat, it.lng) },
            acres_to_spray = acresToSpray,
            location_address = locationAddress,
            total_amount = totalAmount,
            admin_commission = adminCommission,
            pilot_earnings = pilotEarnings,
            payment_method = paymentMethod,
            payment_status = paymentStatus,
            order_status = orderStatus,
            pilot_eta_minutes = pilotEtaMinutes,
            initial_eta_minutes = initialEtaMinutes,
            distance_km = distanceKm,
            field_center_lat = fieldCenterLat,
            field_center_lng = fieldCenterLng,
            accepted_at = acceptedAt,
            pilot_rating = pilotRating,
            razorpay_payment_id = razorpayPaymentId,
            created_at = createdAt
        )
    }
}
