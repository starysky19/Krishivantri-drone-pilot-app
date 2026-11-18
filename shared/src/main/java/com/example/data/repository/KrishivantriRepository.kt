package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.KrishivantriDatabase
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
import com.example.data.model.PilotPerformanceStat
import com.example.data.model.PilotProfileEntity
import com.example.data.model.PilotWithUser
import com.example.data.model.RejectedOrderEntity
import com.example.data.model.UserEntity
import com.example.data.model.UserRole
import com.example.data.remote.SupabaseClient
import com.example.data.remote.SupabaseCoordDto
import com.example.data.remote.SupabaseFarmerProfileDto
import com.example.data.remote.SupabaseOrderDto
import com.example.data.remote.SupabaseOrderRatingPatchDto
import com.example.data.remote.SupabasePilotProfileDto
import com.example.data.remote.SupabaseUserDto
import com.example.domain.GeoPolygonCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

sealed class AuthResult {
    data class Success(val user: UserEntity) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

sealed class AcceptOrderResult {
    data object Success : AcceptOrderResult()
    data class InsufficientWallet(
        val requiredCommission: Double,
        val currentBalance: Double
    ) : AcceptOrderResult()
    data class Error(val message: String) : AcceptOrderResult()
}

class KrishivantriRepository(private val database: KrishivantriDatabase) {

    private val dao = database.dao()

    @Volatile
    private var lastPilotLocationPushMs: Long = 0L

    companion object {
        const val ADMIN_PHONE = "9999000000"
        const val ADMIN_PASSCODE = "KRISHI-ADMIN-2026"
    }

    suspend fun ensureAdminSeeded() {
        val existing = dao.getUserByPhone(ADMIN_PHONE)
        if (existing == null) {
            dao.insertUser(
                UserEntity(
                    id = "admin-root-001",
                    phone = ADMIN_PHONE,
                    name = "Krishivantri Operations Admin",
                    role = UserRole.ADMIN.apiValue
                )
            )
        }
    }

    suspend fun registerFarmer(
        name: String,
        phone: String,
        farmerIdNumber: String
    ): AuthResult {
        val cleanPhone = phone.trim()
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
        phone: String,
        droneType: DroneType,
        tankCapacity: Double,
        licenseDocumentUri: String,
        bankUpiId: String
    ): AuthResult {
        val cleanPhone = phone.trim()
        val existing = dao.getUserByPhone(cleanPhone)
        if (existing != null) {
            return AuthResult.Error("Contact number already registered. Please sign in.")
        }
        val user = UserEntity(
            id = UUID.randomUUID().toString(),
            phone = cleanPhone,
            name = name.trim(),
            role = UserRole.PILOT.apiValue
        )
        val profile = PilotProfileEntity(
            userId = user.id,
            droneType = droneType.apiValue,
            tankCapacity = tankCapacity,
            licenseDocumentUri = licenseDocumentUri,
            isLicenseVerified = false,
            bankUpiId = bankUpiId.trim(),
            walletBalance = 0.0,
            isOnline = false,
            ratingAverage = 5.0,
            ratingCount = 0
        )
        database.withTransaction {
            dao.insertUser(user)
            dao.insertPilotProfile(profile)
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
                    api.upsertPilotProfile(profile.toSupabaseDto())
                }
            }
        }
        return AuthResult.Success(user)
    }

    suspend fun loginByRole(phone: String, expectedRole: UserRole): AuthResult {
        val cleanPhone = phone.trim()
        var user = dao.getUserByPhone(cleanPhone)
        if (user == null) {
            // Hydrate from Supabase if user exists on cloud DB
            user = withContext(Dispatchers.IO) {
                runCatching {
                    val api = SupabaseClient.api ?: return@runCatching null
                    val resp = api.getUsersByPhone("eq.$cleanPhone")
                    val remoteUser = resp.body()?.firstOrNull() ?: return@runCatching null
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
                    } else if (entity.role.equals(UserRole.PILOT.apiValue, ignoreCase = true)) {
                        val pilotResp = api.getPilotProfile("eq.${entity.id}")
                        pilotResp.body()?.firstOrNull()?.let { pp ->
                            dao.insertPilotProfile(pp.toEntity())
                        }
                    }
                    entity
                }.getOrNull()
            }
        }
        if (user == null) {
            return AuthResult.Error("No ${expectedRole.apiValue} account found for this number. Please sign up.")
        }
        if (!user.role.equals(expectedRole.apiValue, ignoreCase = true)) {
            return AuthResult.Error("This number is registered under a different role.")
        }
        return AuthResult.Success(user)
    }

    suspend fun loginAdmin(phone: String, passcode: String): AuthResult {
        ensureAdminSeeded()
        val cleanPhone = phone.trim()
        val user = dao.getUserByPhone(cleanPhone)
        if (user == null || user.role != UserRole.ADMIN.apiValue || passcode.trim() != ADMIN_PASSCODE) {
            return AuthResult.Error("Invalid administrator credentials.")
        }
        return AuthResult.Success(user)
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

    suspend fun syncPilotStateFromSupabase(pilotId: String) = withContext(Dispatchers.IO) {
        val api = SupabaseClient.api ?: return@withContext
        runCatching {
            api.getPilotProfile("eq.$pilotId").body()?.firstOrNull()?.let { pp ->
                val local = dao.getPilotProfile(pilotId)
                if (local == null) {
                    dao.insertPilotProfile(pp.toEntity())
                } else {
                    dao.updatePilotProfile(
                        local.copy(
                            isLicenseVerified = pp.is_license_verified,
                            ratingAverage = pp.rating_average,
                            ratingCount = pp.rating_count
                        )
                    )
                }
            }
        }
        runCatching {
            val orders = api.getRecentOrders().body().orEmpty()
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
        }
    }

    suspend fun togglePilotOnline(pilotId: String, isOnline: Boolean) {
        val profile = dao.getPilotProfile(pilotId) ?: return
        val updated = profile.copy(isOnline = isOnline)
        dao.updatePilotProfile(updated)
        withContext(Dispatchers.IO) {
            runCatching { SupabaseClient.api?.upsertPilotProfile(updated.toSupabaseDto()) }
        }
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

        withContext(Dispatchers.IO) {
            runCatching {
                SupabaseClient.api?.let { api ->
                    api.upsertUser(
                        SupabaseUserDto(
                            id = updatedUser.id,
                            phone = updatedUser.phone,
                            name = updatedUser.name,
                            role = updatedUser.role,
                            created_at = updatedUser.createdAt
                        )
                    )
                    api.upsertPilotProfile(updatedProfile.toSupabaseDto())
                }
            }
        }

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
        if (amount <= 0.0) return
        var updatedProfile: PilotProfileEntity? = null
        database.withTransaction {
            val profile = dao.getPilotProfile(pilotId) ?: return@withTransaction
            val updatedBalance = ((profile.walletBalance + amount) * 100.0).roundToInt() / 100.0
            val next = profile.copy(walletBalance = updatedBalance)
            dao.updatePilotProfile(next)
            updatedProfile = next
        }
        updatedProfile?.let { prof ->
            withContext(Dispatchers.IO) {
                runCatching { SupabaseClient.api?.upsertPilotProfile(prof.toSupabaseDto()) }
            }
        }
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
                withContext(Dispatchers.IO) {
                    runCatching {
                        val api = SupabaseClient.api ?: return@runCatching
                        dao.getOrderById(orderId)?.let { api.upsertOrder(it.toSupabaseDto()) }
                        dao.getPilotProfile(pilotId)?.let { api.upsertPilotProfile(it.toSupabaseDto()) }
                    }
                }
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
            withContext(Dispatchers.IO) {
                runCatching { SupabaseClient.api?.upsertOrder(updated.toSupabaseDto()) }
            }
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
        withContext(Dispatchers.IO) {
            runCatching {
                val api = SupabaseClient.api ?: return@runCatching
                completedOrder?.let { api.upsertOrder(it.toSupabaseDto()) }
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
