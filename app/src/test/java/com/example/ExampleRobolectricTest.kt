package com.example

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Krishivantri Drone Pilot", appName)
  }

  @Test
  fun `mainActivity launches without crashing`() {
    var uncaughtError: Throwable? = null
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
      uncaughtError = throwable
    }
    try {
      val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      val activity = controller.get()
      assertNotNull(activity)
      assertNull("Unexpected startup crash: ${uncaughtError?.stackTraceToString()}", uncaughtError)
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }
  }

  @Test
  fun `acceptOrder calculates pilot-to-field distance and updates ETA in real time`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.KrishivantriDatabase::class.java
    ).allowMainThreadQueries().build()
    try {
      val repo = com.example.data.repository.KrishivantriRepository(db)
      val farmerResult = repo.registerFarmer("Ramesh Farmer", "9876543210", "FRM-101")
      val pilotResult = repo.registerPilot(
        name = "Suresh Pilot",
        email = "suresh@nexustekfusion.com",
        phone = "9876543211",
        droneType = com.example.data.model.DroneType.QUADCOPTER,
        tankCapacity = 12.0,
        licenseDocumentUri = "content://license",
        bankUpiId = "suresh@upi"
      )
      val farmer = (farmerResult as com.example.data.repository.AuthResult.Success).user
      val pilot = (pilotResult as com.example.data.repository.AuthResult.Success).user

      val fieldPolygon = listOf(
        com.example.data.model.LatLngPoint(20.0059, 73.7897),
        com.example.data.model.LatLngPoint(20.0059, 73.7917),
        com.example.data.model.LatLngPoint(20.0079, 73.7917),
        com.example.data.model.LatLngPoint(20.0079, 73.7897)
      )
      val order = repo.placeOrder(
        farmerId = farmer.id,
        locationAddress = "Nashik Plot",
        polygonPoints = fieldPolygon,
        paymentMethod = com.example.data.model.PaymentMethod.ONLINE
      )

      // Pilot accepts from ~5 km south
      val pilotStart = com.example.data.model.LatLngPoint(19.9619, 73.7907)
      val acceptResult = repo.acceptOrder(pilot.id, order.id, pilotStart)
      assertEquals(com.example.data.repository.AcceptOrderResult.Success, acceptResult)

      val acceptedOrder = db.dao().getOrderById(order.id)!!
      assertNotNull(acceptedOrder.distanceKm)
      assertNotNull(acceptedOrder.acceptedAt)
      assertTrue(
        "Expected ~5 km distance, got ${acceptedOrder.distanceKm}",
        acceptedOrder.distanceKm!! in 4.5..5.5
      )
      assertTrue(
        "Expected ETA >= 10 mins, got ${acceptedOrder.pilotEtaMinutes}",
        acceptedOrder.pilotEtaMinutes >= 10
      )

      // Pilot moves closer (~1 km south)
      repo.updatePilotLiveLocation(pilot.id, 19.9979, 73.7907)
      val updatedOrder = db.dao().getOrderById(order.id)!!
      val initialDist = acceptedOrder.distanceKm!!
      val updatedDist = updatedOrder.distanceKm!!
      assertTrue(
        "Expected updated distance < initial distance",
        updatedDist < initialDist
      )
      assertTrue(
        "Expected updated ETA < initial ETA",
        updatedOrder.pilotEtaMinutes < acceptedOrder.pilotEtaMinutes
      )
    } finally {
      db.close()
    }
  }

  @Test
  fun `cash order acceptance requires and deducts 10 percent wallet reserve`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.KrishivantriDatabase::class.java
    ).allowMainThreadQueries().build()
    try {
      val repo = com.example.data.repository.KrishivantriRepository(db)
      val farmer = (repo.registerFarmer("Ramesh Farmer", "9876543212", "FRM-102")
        as com.example.data.repository.AuthResult.Success).user
      val pilot = (repo.registerPilot(
        name = "Vikram Pilot",
        email = "vikram@nexustekfusion.com",
        phone = "9876543213",
        droneType = com.example.data.model.DroneType.HEXACOPTER,
        tankCapacity = 16.0,
        licenseDocumentUri = "content://license",
        bankUpiId = "vikram@upi"
      ) as com.example.data.repository.AuthResult.Success).user

      val fieldPolygon = listOf(
        com.example.data.model.LatLngPoint(20.0059, 73.7897),
        com.example.data.model.LatLngPoint(20.0059, 73.7917),
        com.example.data.model.LatLngPoint(20.0079, 73.7917),
        com.example.data.model.LatLngPoint(20.0079, 73.7897)
      )
      val cashOrder = repo.placeOrder(
        farmerId = farmer.id,
        locationAddress = "Sinnar Farm",
        polygonPoints = fieldPolygon,
        paymentMethod = com.example.data.model.PaymentMethod.CASH
      )

      val rejected = repo.acceptOrder(pilot.id, cashOrder.id)
      assertTrue(rejected is com.example.data.repository.AcceptOrderResult.InsufficientWallet)

      repo.topUpPilotWallet(pilot.id, 1000.0)
      val accepted = repo.acceptOrder(pilot.id, cashOrder.id)
      assertEquals(com.example.data.repository.AcceptOrderResult.Success, accepted)

      val updatedPilot = db.dao().getPilotProfile(pilot.id)!!
      assertEquals(1000.0 - cashOrder.adminCommission, updatedPilot.walletBalance, 0.01)
    } finally {
      db.close()
    }
  }

  @Test
  fun `updatePilotAccountDetails updates UPI ID name drone platform and tank capacity`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.KrishivantriDatabase::class.java
    ).allowMainThreadQueries().build()
    try {
      val repo = com.example.data.repository.KrishivantriRepository(db)
      val pilot = (repo.registerPilot(
        name = "Arjun Pilot",
        email = "arjun@nexustekfusion.com",
        phone = "9876543214",
        droneType = com.example.data.model.DroneType.QUADCOPTER,
        tankCapacity = 10.0,
        licenseDocumentUri = "content://old_license",
        bankUpiId = "arjun@upi"
      ) as com.example.data.repository.AuthResult.Success).user

      val result = repo.updatePilotAccountDetails(
        pilotId = pilot.id,
        name = "Arjun Deshmukh",
        droneType = com.example.data.model.DroneType.HEXACOPTER,
        tankCapacity = 16.0,
        bankUpiId = "arjun.deshmukh@okhdfcbank",
        licenseDocumentUri = "content://new_license"
      )

      assertTrue(result is com.example.data.repository.AuthResult.Success)
      val updatedUser = db.dao().getUserById(pilot.id)!!
      val updatedProfile = db.dao().getPilotProfile(pilot.id)!!
      assertEquals("Arjun Deshmukh", updatedUser.name)
      assertEquals("arjun.deshmukh@okhdfcbank", updatedProfile.bankUpiId)
      assertEquals(com.example.data.model.DroneType.HEXACOPTER.apiValue, updatedProfile.droneType)
      assertEquals(16.0, updatedProfile.tankCapacity, 0.01)
      assertEquals("content://new_license", updatedProfile.licenseDocumentUri)
    } finally {
      db.close()
    }
  }

  @Test
  fun `pilot can withdraw completed online earnings to bank account and cannot overdraw`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.KrishivantriDatabase::class.java
    ).allowMainThreadQueries().build()
    try {
      val repo = com.example.data.repository.KrishivantriRepository(db)
      val farmer = (repo.registerFarmer("Ramesh Farmer", "9876543215", "FRM-105")
        as com.example.data.repository.AuthResult.Success).user
      val pilot = (repo.registerPilot(
        name = "Kiran Pilot",
        email = "kiran@nexustekfusion.com",
        phone = "9876543216",
        droneType = com.example.data.model.DroneType.HEXACOPTER,
        tankCapacity = 16.0,
        licenseDocumentUri = "content://license",
        bankUpiId = "kiran@upi"
      ) as com.example.data.repository.AuthResult.Success).user

      val fieldPolygon = listOf(
        com.example.data.model.LatLngPoint(20.0059, 73.7897),
        com.example.data.model.LatLngPoint(20.0059, 73.7917),
        com.example.data.model.LatLngPoint(20.0079, 73.7917),
        com.example.data.model.LatLngPoint(20.0079, 73.7897)
      )
      val onlineOrder = repo.placeOrder(
        farmerId = farmer.id,
        locationAddress = "Dindori Vineyard",
        polygonPoints = fieldPolygon,
        paymentMethod = com.example.data.model.PaymentMethod.ONLINE
      )

      assertEquals(
        com.example.data.repository.AcceptOrderResult.Success,
        repo.acceptOrder(pilot.id, onlineOrder.id)
      )
      repo.completeOrder(onlineOrder.id)

      // Invalid IFSC should fail cleanly
      val badIfsc = repo.withdrawPilotEarnings(
        pilotId = pilot.id,
        amount = 1000.0,
        method = com.example.data.model.WithdrawalMethod.BANK_TRANSFER,
        accountHolderName = "Kiran Patil",
        bankAccountNumber = "123456789012",
        ifscCode = "INVALID",
        bankName = "HDFC Bank"
      )
      assertTrue(badIfsc is com.example.data.repository.WithdrawResult.Error)

      // Partial withdrawal to bank account succeeds and saves bank account
      val firstWithdraw = repo.withdrawPilotEarnings(
        pilotId = pilot.id,
        amount = 1500.0,
        method = com.example.data.model.WithdrawalMethod.BANK_TRANSFER,
        accountHolderName = "Kiran Patil",
        bankAccountNumber = "123456789012",
        ifscCode = "HDFC0001234",
        bankName = "HDFC Bank"
      )
      assertTrue(firstWithdraw is com.example.data.repository.WithdrawResult.Success)
      val wd = (firstWithdraw as com.example.data.repository.WithdrawResult.Success).withdrawal
      assertEquals(1500.0, wd.amount, 0.01)
      assertTrue(wd.referenceId.startsWith("KVWD-"))

      val savedBank = db.dao().getPilotBankAccount(pilot.id)
      assertNotNull(savedBank)
      assertEquals("123456789012", savedBank!!.accountNumber)
      assertEquals("HDFC0001234", savedBank.ifscCode)

      // Over-withdrawal beyond remaining earnings fails
      val overdraw = repo.withdrawPilotEarnings(
        pilotId = pilot.id,
        amount = onlineOrder.pilotEarnings,
        method = com.example.data.model.WithdrawalMethod.BANK_TRANSFER,
        accountHolderName = "Kiran Patil",
        bankAccountNumber = "123456789012",
        ifscCode = "HDFC0001234",
        bankName = "HDFC Bank"
      )
      assertTrue(overdraw is com.example.data.repository.WithdrawResult.Error)

      // Remaining balance withdrawal via UPI succeeds
      val remaining = ((onlineOrder.pilotEarnings - 1500.0) * 100.0).toInt() / 100.0
      val secondWithdraw = repo.withdrawPilotEarnings(
        pilotId = pilot.id,
        amount = remaining,
        method = com.example.data.model.WithdrawalMethod.UPI,
        upiId = "kiran.patil@okhdfcbank"
      )
      assertTrue(secondWithdraw is com.example.data.repository.WithdrawResult.Success)
      assertEquals(2, db.dao().getPilotWithdrawals(pilot.id).size)
    } finally {
      db.close()
    }
  }

  @Test
  fun `loginByRole reports cloud config message when Supabase anon key is missing on fresh install`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.KrishivantriDatabase::class.java
    ).allowMainThreadQueries().build()
    val prevKey = com.example.data.remote.KrishivantriRuntimeConfig.supabaseAnonKey
    val prevUrl = com.example.data.remote.KrishivantriRuntimeConfig.supabaseUrl
    try {
      com.example.data.remote.KrishivantriRuntimeConfig.supabaseUrl = "https://example.supabase.co"
      com.example.data.remote.KrishivantriRuntimeConfig.supabaseAnonKey = "MY_SUPABASE_ANON_KEY"
      val repo = com.example.data.repository.KrishivantriRepository(db, context)
      val roleResult = repo.loginByRole("9876543219", com.example.data.model.UserRole.FARMER)
      assertTrue(roleResult is com.example.data.repository.AuthResult.Error)
      val roleMsg = (roleResult as com.example.data.repository.AuthResult.Error).message
      assertTrue("Expected SUPABASE_ANON_KEY hint in '$roleMsg'", roleMsg.contains("SUPABASE_ANON_KEY"))

      val otpResult = repo.requestPilotEmailOtp("pilot@nexustekfusion.com")
      assertTrue(otpResult is com.example.data.repository.OtpRequestResult.Error)
      val otpMsg = (otpResult as com.example.data.repository.OtpRequestResult.Error).message
      assertTrue("Expected SUPABASE_ANON_KEY hint in '$otpMsg'", otpMsg.contains("SUPABASE_ANON_KEY"))
    } finally {
      com.example.data.remote.KrishivantriRuntimeConfig.supabaseUrl = prevUrl
      com.example.data.remote.KrishivantriRuntimeConfig.supabaseAnonKey = prevKey
      db.close()
    }
  }

  @Test
  fun `runtime config normalizes quoted Supabase URLs, project IDs, and Razorpay key`() {
    val prevUrl = com.example.data.remote.KrishivantriRuntimeConfig.supabaseUrl
    val prevKey = com.example.data.remote.KrishivantriRuntimeConfig.supabaseAnonKey
    val prevRzp = com.example.data.remote.KrishivantriRuntimeConfig.razorpayKeyId
    try {
      assertEquals(
        "https://abcxyzproject.supabase.co",
        com.example.data.remote.KrishivantriRuntimeConfig.normalizeSupabaseBaseUrl("  \"abcxyzproject\"  ")
      )
      assertEquals(
        "https://abcxyzproject.supabase.co",
        com.example.data.remote.KrishivantriRuntimeConfig.normalizeSupabaseBaseUrl("https://abcxyzproject.supabase.co/rest/v1/")
      )

      com.example.data.remote.KrishivantriRuntimeConfig.configure(
        apiBaseUrl = "https://api.krishivantri.in/api/v1",
        supabaseUrl = "\"https://abcxyzproject.supabase.co/rest/v1/\"",
        supabaseAnonKey = " 'sb_publishable_test_token' ",
        razorpayKeyId = " \"rzp_test_1234567890\" "
      )

      assertEquals("https://abcxyzproject.supabase.co", com.example.data.remote.KrishivantriRuntimeConfig.supabaseUrl)
      assertEquals("sb_publishable_test_token", com.example.data.remote.KrishivantriRuntimeConfig.supabaseAnonKey)
      assertTrue(com.example.data.remote.KrishivantriRuntimeConfig.isRazorpayConfigured())
      assertTrue(com.example.data.remote.SupabaseClient.isConfigured())

      com.example.data.remote.SupabaseClient.markConnected("Supabase Cloud Connected")
      assertEquals(
        com.example.data.remote.CloudConnectionState.CONNECTED,
        com.example.data.remote.SupabaseClient.cloudStatus.value.state
      )
      com.example.data.remote.SupabaseClient.markRealtimeState(true)
      assertTrue(com.example.data.remote.SupabaseClient.cloudStatus.value.isRealtimeLive)
    } finally {
      com.example.data.remote.KrishivantriRuntimeConfig.supabaseUrl = prevUrl
      com.example.data.remote.KrishivantriRuntimeConfig.supabaseAnonKey = prevKey
      com.example.data.remote.KrishivantriRuntimeConfig.razorpayKeyId = prevRzp
    }
  }

  @Test
  fun `pilot registration validates email and blocks duplicate email registrations`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.KrishivantriDatabase::class.java
    ).allowMainThreadQueries().build()

    val prevUrl = com.example.data.remote.KrishivantriRuntimeConfig.supabaseUrl
    val prevKey = com.example.data.remote.KrishivantriRuntimeConfig.supabaseAnonKey

    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
    val registeredEmails = mutableSetOf<String>()
    server.createContext("/") { exchange ->
      val path = exchange.requestURI.path
      val reqBody = exchange.requestBody.bufferedReader().readText()
      var code = 200
      var resp = "[]"
      when {
        path == "/rest/v1/users" && exchange.requestMethod == "GET" -> {
          code = 200
          resp = "[]"
        }
        path == "/rest/v1/users" && exchange.requestMethod == "POST" -> {
          code = 201
          resp = "[]"
        }
        path == "/rest/v1/pilot_profiles" -> {
          code = 201
          resp = "[]"
        }
        path == "/auth/v1/signup" -> {
          val obj = org.json.JSONObject(reqBody)
          val email = obj.optString("email").lowercase()
          if (email in registeredEmails) {
            code = 200
            // GoTrue obfuscated duplicate signal: empty identities list
            resp = """{"user":{"id":"pilot-dup","email":"$email","identities":[]}}"""
          } else {
            registeredEmails.add(email)
            code = 200
            resp = """{"access_token":"tok_reg_1","refresh_token":"ref_reg_1","expires_in":3600,"user":{"id":"pilot-uuid-1","email":"$email","identities":[{"id":"id-1","provider":"email"}]}}"""
          }
        }
      }
      val bytes = resp.toByteArray(Charsets.UTF_8)
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(code, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()

    try {
      com.example.data.remote.KrishivantriRuntimeConfig.configure(
        apiBaseUrl = "https://api.krishivantri.in/api/v1/",
        supabaseUrl = "http://127.0.0.1:${server.address.port}",
        supabaseAnonKey = "sb_publishable_test_key"
      )
      val repo = com.example.data.repository.KrishivantriRepository(db, context)

      val invalid = repo.registerPilot(
        name = "Test Pilot",
        email = "bad-email-format",
        phone = "9876543210",
        droneType = com.example.data.model.DroneType.QUADCOPTER,
        tankCapacity = 10.0,
        licenseDocumentUri = "content://license",
        bankUpiId = "pilot@upi"
      )
      assertTrue(invalid is com.example.data.repository.AuthResult.Error)

      val first = repo.registerPilot(
        name = "Test Pilot",
        email = "Pilot@NexusTekFusion.com",
        phone = "9876543210",
        droneType = com.example.data.model.DroneType.QUADCOPTER,
        tankCapacity = 12.0,
        licenseDocumentUri = "content://license",
        bankUpiId = "pilot@upi"
      )
      assertTrue(first is com.example.data.repository.AuthResult.Success)
      val firstSuccess = first as com.example.data.repository.AuthResult.Success
      assertEquals("pilot@nexustekfusion.com", firstSuccess.user.email)
      assertEquals("tok_reg_1", firstSuccess.accessToken)

      // Duplicate on second phone triggers remote duplicate rejection cleanly
      val dupRemote = repo.registerPilot(
        name = "Second Pilot",
        email = "pilot@nexustekfusion.com",
        phone = "9876543299",
        droneType = com.example.data.model.DroneType.HEXACOPTER,
        tankCapacity = 16.0,
        licenseDocumentUri = "content://license2",
        bankUpiId = "pilot2@upi"
      )
      assertTrue(dupRemote is com.example.data.repository.AuthResult.Error)
      assertTrue((dupRemote as com.example.data.repository.AuthResult.Error).message.contains("already registered"))
    } finally {
      server.stop(0)
      com.example.data.remote.KrishivantriRuntimeConfig.supabaseUrl = prevUrl
      com.example.data.remote.KrishivantriRuntimeConfig.supabaseAnonKey = prevKey
      db.close()
    }
  }

  @Test
  fun `email OTP login enforces non-enumeration cooldown attempt limits and backend verification`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.KrishivantriDatabase::class.java
    ).allowMainThreadQueries().build()

    val prevUrl = com.example.data.remote.KrishivantriRuntimeConfig.supabaseUrl
    val prevKey = com.example.data.remote.KrishivantriRuntimeConfig.supabaseAnonKey

    var legacyProvisioned = false
    var unregProvisioned = false

    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      val path = exchange.requestURI.path
      val query = exchange.requestURI.query.orEmpty()
      val reqBody = exchange.requestBody.bufferedReader().readText()
      var code = 200
      var resp = "{}"
      when {
        path == "/auth/v1/otp" -> {
          val obj = org.json.JSONObject(reqBody)
          val email = obj.optString("email")
          val createUser = obj.optBoolean("create_user", false)
          when {
            email == "unregistered@nexustekfusion.com" -> {
              if (createUser) unregProvisioned = true
              code = 422
              resp = """{"code":422,"error_code":"otp_disabled","msg":"Signups not allowed for otp"}"""
            }
            email == "legacy.pilot@nexustekfusion.com" && !createUser -> {
              code = 422
              resp = """{"code":422,"error_code":"otp_disabled","msg":"Signups not allowed for otp"}"""
            }
            email == "legacy.pilot@nexustekfusion.com" && createUser -> {
              legacyProvisioned = true
              code = 200
              resp = "{}"
            }
            else -> {
              code = 200
              resp = "{}"
            }
          }
        }
        path == "/auth/v1/verify" -> {
          val obj = org.json.JSONObject(reqBody)
          val token = obj.optString("token")
          val email = obj.optString("email")
          val type = obj.optString("type")
          if (token == "654321" && email == "verified.pilot@nexustekfusion.com" && type == "email") {
            code = 200
            resp = """{
              "access_token":"jwt_access_verified",
              "refresh_token":"jwt_refresh_verified",
              "expires_in":3600,
              "user":{
                "id":"pilot-cloud-99",
                "email":"$email",
                "user_metadata":{
                  "full_name":"Meera Pilot",
                  "phone":"9822001122",
                  "role":"pilot",
                  "pilot_user_id":"pilot-cloud-99",
                  "drone_type":"hexacopter",
                  "tank_capacity":16.0,
                  "bank_upi_id":"meera@okaxis"
                }
              }
            }"""
          } else if (token == "123456" && email == "legacy.pilot@nexustekfusion.com" && type == "signup") {
            code = 200
            resp = """{
              "access_token":"jwt_access_legacy",
              "refresh_token":"jwt_refresh_legacy",
              "expires_in":3600,
              "user":{
                "id":"pilot-legacy-77",
                "email":"$email",
                "user_metadata":{
                  "full_name":"Legacy Pilot",
                  "phone":"9833004455",
                  "role":"pilot",
                  "pilot_user_id":"pilot-legacy-77"
                }
              }
            }"""
          } else {
            code = 401
            resp = """{"error":"invalid_grant","error_description":"Token has expired or is invalid"}"""
          }
        }
        path == "/rest/v1/users" && exchange.requestMethod == "GET" -> {
          code = 200
          resp = when {
            query.contains("pilot-cloud-99") ->
              """[{"id":"pilot-cloud-99","phone":"9822001122","email":"verified.pilot@nexustekfusion.com","name":"Meera Pilot","role":"pilot","created_at":1700000000000}]"""
            query.contains("legacy.pilot") || query.contains("pilot-legacy-77") ->
              """[{"id":"pilot-legacy-77","phone":"9833004455","email":"legacy.pilot@nexustekfusion.com","name":"Legacy Pilot","role":"pilot","created_at":1700000000000}]"""
            else -> "[]"
          }
        }
        path == "/rest/v1/pilot_profiles" && exchange.requestMethod == "GET" -> {
          code = 200
          resp = if (query.contains("pilot-legacy-77")) {
            """[{"user_id":"pilot-legacy-77","drone_type":"quadcopter","tank_capacity":10.0,"license_document_uri":"","is_license_verified":true,"bank_upi_id":"legacy@upi","wallet_balance":400.0,"is_online":false,"rating_average":4.8,"rating_count":5,"location_updated_at":0}]"""
          } else {
            """[{"user_id":"pilot-cloud-99","drone_type":"hexacopter","tank_capacity":16.0,"license_document_uri":"https://cdn.example/lic.jpg","is_license_verified":true,"bank_upi_id":"meera@okaxis","wallet_balance":750.0,"is_online":false,"rating_average":4.9,"rating_count":12,"location_updated_at":0}]"""
          }
        }
        path.startsWith("/rest/v1/") -> {
          code = 200
          resp = "[]"
        }
      }
      val bytes = resp.toByteArray(Charsets.UTF_8)
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(code, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()

    try {
      com.example.data.remote.KrishivantriRuntimeConfig.configure(
        apiBaseUrl = "https://api.krishivantri.in/api/v1/",
        supabaseUrl = "http://127.0.0.1:${server.address.port}",
        supabaseAnonKey = "sb_publishable_test_key"
      )
      val repo = com.example.data.repository.KrishivantriRepository(db, context)

      // 1. Unregistered email must NOT disclose whether it is registered
      val unregOtp = repo.requestPilotEmailOtp("unregistered@nexustekfusion.com")
      assertTrue(unregOtp is com.example.data.repository.OtpRequestResult.Sent)
      val unregMsg = (unregOtp as com.example.data.repository.OtpRequestResult.Sent).message
      assertTrue(!unregMsg.contains("not found", ignoreCase = true) && !unregMsg.contains("disabled", ignoreCase = true))

      // 2. Valid registered email sends OTP and enforces 60s cooldown
      val sent = repo.requestPilotEmailOtp("verified.pilot@nexustekfusion.com")
      assertTrue(sent is com.example.data.repository.OtpRequestResult.Sent)

      val cooldown = repo.requestPilotEmailOtp("verified.pilot@nexustekfusion.com")
      assertTrue(cooldown is com.example.data.repository.OtpRequestResult.RateLimited)

      // 3. Invalid OTP verified on backend fails & triggers lockout after 5 wrong tries
      repeat(5) {
        val wrong = repo.verifyPilotEmailOtp("verified.pilot@nexustekfusion.com", "000000")
        assertTrue(wrong is com.example.data.repository.AuthResult.Error)
      }
      val locked = repo.verifyPilotEmailOtp("verified.pilot@nexustekfusion.com", "654321")
      assertTrue(locked is com.example.data.repository.AuthResult.Error)
      assertTrue((locked as com.example.data.repository.AuthResult.Error).message.contains("Too many"))

      // 4. Correct OTP on fresh email/repo verifies on backend & restores profile
      val repoFresh = com.example.data.repository.KrishivantriRepository(db, context)
      val ok = repoFresh.verifyPilotEmailOtp("verified.pilot@nexustekfusion.com", "654321")
      assertTrue(ok is com.example.data.repository.AuthResult.Success)
      val success = ok as com.example.data.repository.AuthResult.Success
      assertEquals("Meera Pilot", success.user.name)
      assertEquals("verified.pilot@nexustekfusion.com", success.user.email)
      assertEquals("jwt_access_verified", success.accessToken)
      assertEquals("jwt_refresh_verified", success.refreshToken)
      assertEquals(750.0, db.dao().getPilotProfile("pilot-cloud-99")!!.walletBalance, 0.01)

      // 5. Legacy pilot in public.users provisions auth entry and verifies signup OTP fallback
      val legacyOtp = repoFresh.requestPilotEmailOtp("legacy.pilot@nexustekfusion.com")
      assertTrue(legacyOtp is com.example.data.repository.OtpRequestResult.Sent)
      assertTrue("Expected legacy pilot to trigger create_user=true", legacyProvisioned)
      assertTrue("Expected unknown email NOT to trigger create_user=true", !unregProvisioned)

      val legacyVerify = repoFresh.verifyPilotEmailOtp("legacy.pilot@nexustekfusion.com", "123456")
      assertTrue(legacyVerify is com.example.data.repository.AuthResult.Success)
      assertEquals("Legacy Pilot", (legacyVerify as com.example.data.repository.AuthResult.Success).user.name)
    } finally {
      server.stop(0)
      com.example.data.remote.KrishivantriRuntimeConfig.supabaseUrl = prevUrl
      com.example.data.remote.KrishivantriRuntimeConfig.supabaseAnonKey = prevKey
      db.close()
    }
  }

  @Test
  fun `persistent session restores across app restart refreshes expired tokens and logout revokes backend session`() = kotlinx.coroutines.runBlocking {
    val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    val tokenStore = com.example.data.local.SessionTokenStore(app)
    tokenStore.clear()

    val db = com.example.data.local.KrishivantriDatabase.getInstance(app)
    val prevUrl = com.example.data.remote.KrishivantriRuntimeConfig.supabaseUrl
    val prevKey = com.example.data.remote.KrishivantriRuntimeConfig.supabaseAnonKey

    var logoutCalledWithAuthHeader: String? = null
    var refreshCalled = false

    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      val path = exchange.requestURI.path
      val authHeader = exchange.requestHeaders.getFirst("Authorization")
      var code = 200
      var resp = "{}"
      when {
        path == "/auth/v1/token" -> {
          refreshCalled = true
          code = 200
          resp = """{
            "access_token":"fresh_access_token",
            "refresh_token":"fresh_refresh_token",
            "expires_in":3600,
            "user":{
              "id":"pilot-persist-1",
              "email":"persist@nexustekfusion.com",
              "user_metadata":{"full_name":"Rohan Pilot","phone":"9811223344","role":"pilot","pilot_user_id":"pilot-persist-1"}
            }
          }"""
        }
        path == "/auth/v1/logout" -> {
          logoutCalledWithAuthHeader = authHeader
          code = 204
          resp = ""
        }
        path == "/rest/v1/users" && exchange.requestMethod == "GET" -> {
          code = 200
          resp = """[{"id":"pilot-persist-1","phone":"9811223344","email":"persist@nexustekfusion.com","name":"Rohan Pilot","role":"pilot","created_at":1700000000000}]"""
        }
        path == "/rest/v1/pilot_profiles" && exchange.requestMethod == "GET" -> {
          code = 200
          resp = """[{"user_id":"pilot-persist-1","drone_type":"quadcopter","tank_capacity":10.0,"license_document_uri":"","is_license_verified":true,"bank_upi_id":"rohan@upi","wallet_balance":250.0,"is_online":false,"rating_average":5.0,"rating_count":0,"location_updated_at":0}]"""
        }
        path.startsWith("/rest/v1/") -> {
          code = 200
          resp = "[]"
        }
      }
      val bytes = resp.toByteArray(Charsets.UTF_8)
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1L else bytes.size.toLong())
      if (bytes.isNotEmpty()) {
        exchange.responseBody.use { it.write(bytes) }
      } else {
        exchange.responseBody.close()
      }
    }
    server.start()

    try {
      com.example.data.remote.KrishivantriRuntimeConfig.configure(
        apiBaseUrl = "https://api.krishivantri.in/api/v1/",
        supabaseUrl = "http://127.0.0.1:${server.address.port}",
        supabaseAnonKey = "sb_publishable_test_key"
      )

      db.dao().insertUser(
        com.example.data.model.UserEntity(
          id = "pilot-persist-1",
          phone = "9811223344",
          email = "persist@nexustekfusion.com",
          name = "Rohan Pilot",
          role = "pilot"
        )
      )
      tokenStore.save(
        com.example.data.local.PersistedAuthSession(
          userId = "pilot-persist-1",
          email = "persist@nexustekfusion.com",
          phone = "9811223344",
          role = "pilot",
          accessToken = "stale_access_token",
          refreshToken = "stale_refresh_token",
          expiresAtEpochSec = (System.currentTimeMillis() / 1000L) - 10L, // expired -> triggers refresh
          loggedInAtMs = System.currentTimeMillis() - 7200_000L
        )
      )

      // Recreating ViewModel simulates closing & reopening the app
      val vm = com.example.ui.viewmodel.KrishivantriViewModel(app)
      val deadline = System.currentTimeMillis() + 5_000L
      while (tokenStore.load()?.accessToken != "fresh_access_token" && System.currentTimeMillis() < deadline) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        kotlinx.coroutines.delay(50)
      }
      Shadows.shadowOf(Looper.getMainLooper()).idle()

      assertNotNull("Expected user session to auto-restore", vm.currentUser.value)
      assertEquals("persist@nexustekfusion.com", vm.currentUser.value?.email)
      assertTrue("Expected expired token to refresh automatically", refreshCalled)
      assertEquals("fresh_access_token", tokenStore.load()?.accessToken)

      // Explicit Log Out clears tokens and calls backend logout endpoint
      vm.signOut()
      val logoutDeadline = System.currentTimeMillis() + 3_000L
      while ((logoutCalledWithAuthHeader == null || tokenStore.load() != null) && System.currentTimeMillis() < logoutDeadline) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        kotlinx.coroutines.delay(40)
      }
      Shadows.shadowOf(Looper.getMainLooper()).idle()

      assertNull("Expected user cleared after Log Out", vm.currentUser.value)
      assertNull("Expected stored session cleared after Log Out", tokenStore.load())
      assertEquals("Bearer fresh_access_token", logoutCalledWithAuthHeader)
    } finally {
      tokenStore.clear()
      server.stop(0)
      com.example.data.remote.KrishivantriRuntimeConfig.supabaseUrl = prevUrl
      com.example.data.remote.KrishivantriRuntimeConfig.supabaseAnonKey = prevKey
    }
  }
}
