import json
from datetime import timedelta
from unittest.mock import patch

from django.core import mail
from django.test import TestCase
from django.utils import timezone
from rest_framework.authtoken.models import Token
from rest_framework.test import APIClient

from .models import CustomUser, EmailOTP, PilotProfile
from .services import OTP_COOLDOWN_SECONDS


class EmailOTPAuthenticationTests(TestCase):
    def setUp(self):
        self.client = APIClient()

    def test_pilot_registration_requires_valid_unique_email(self):
        bad_resp = self.client.post(
            "/api/v1/auth/pilot/register/",
            {
                "name": "Aarav Pilot",
                "email": "not-an-email",
                "phone": "9876543210",
                "drone_type": "quadcopter",
                "tank_capacity": "10",
                "bank_upi_id": "aarav@upi",
            },
            format="json",
        )
        self.assertEqual(bad_resp.status_code, 400)

        ok_resp = self.client.post(
            "/api/v1/auth/pilot/register/",
            {
                "name": "Aarav Pilot",
                "email": "Aarav@NexusTekFusion.com",
                "phone": "9876543210",
                "drone_type": "quadcopter",
                "tank_capacity": "12",
                "bank_upi_id": "aarav@upi",
            },
            format="json",
        )
        self.assertEqual(ok_resp.status_code, 201)
        data = ok_resp.json()
        self.assertEqual(data["email"], "aarav@nexustekfusion.com")
        self.assertTrue(data["token"])
        self.assertTrue(PilotProfile.objects.filter(user__email="aarav@nexustekfusion.com").exists())

        dup_resp = self.client.post(
            "/api/v1/auth/pilot/register/",
            {
                "name": "Other Pilot",
                "email": "aarav@nexustekfusion.com",
                "phone": "9876543999",
                "drone_type": "hexacopter",
                "tank_capacity": "16",
                "bank_upi_id": "other@upi",
            },
            format="json",
        )
        self.assertEqual(dup_resp.status_code, 400)

    def test_otp_request_verify_cooldown_expiry_and_logout(self):
        user = CustomUser.objects.create_user(
            phone="9876543211",
            email="pilot1@nexustekfusion.com",
            name="Vikram Pilot",
            role="pilot",
        )
        PilotProfile.objects.create(
            user=user,
            drone_type="quadcopter",
            tank_capacity="10.00",
            bank_upi_id="vikram@upi",
        )

        # Unregistered email gets identical 200 without revealing account existence
        unreg_resp = self.client.post(
            "/api/v1/auth/otp/request/",
            {"email": "unknown@nexustekfusion.com"},
            format="json",
        )
        self.assertEqual(unreg_resp.status_code, 200)
        self.assertEqual(len(mail.outbox), 0)

        # Capture OTP sent cleanly via Django email outbox (never stored plaintext in DB)
        with patch("marketplace.services.generate_secure_otp", return_value="482913"):
            otp_resp = self.client.post(
                "/api/v1/auth/otp/request/",
                {"email": "pilot1@nexustekfusion.com"},
                format="json",
            )
        self.assertEqual(otp_resp.status_code, 200)
        self.assertEqual(len(mail.outbox), 1)
        self.assertIn("482913", mail.outbox[0].body)

        otp_record = EmailOTP.objects.get(email="pilot1@nexustekfusion.com")
        self.assertNotEqual(otp_record.otp_hash, "482913")
        self.assertNotIn("482913", otp_record.otp_hash)

        # Immediate repeat within cooldown is rate-limited (429)
        cooldown_resp = self.client.post(
            "/api/v1/auth/otp/request/",
            {"email": "pilot1@nexustekfusion.com"},
            format="json",
        )
        self.assertEqual(cooldown_resp.status_code, 429)

        # Wrong OTP fails without revealing details
        bad_verify = self.client.post(
            "/api/v1/auth/otp/verify/",
            {"email": "pilot1@nexustekfusion.com", "otp": "000000", "role": "pilot"},
            format="json",
        )
        self.assertEqual(bad_verify.status_code, 401)

        # Valid OTP authenticates and returns session token
        good_verify = self.client.post(
            "/api/v1/auth/otp/verify/",
            {"email": "pilot1@nexustekfusion.com", "otp": "482913", "role": "pilot"},
            format="json",
        )
        self.assertEqual(good_verify.status_code, 200)
        token_key = good_verify.json()["token"]
        self.assertTrue(token_key)

        # Authenticated session validation succeeds
        self.client.credentials(HTTP_AUTHORIZATION=f"Token {token_key}")
        session_resp = self.client.get("/api/v1/auth/session/")
        self.assertEqual(session_resp.status_code, 200)
        self.assertEqual(session_resp.json()["email"], "pilot1@nexustekfusion.com")

        # Logout invalidates backend token
        logout_resp = self.client.post("/api/v1/auth/logout/")
        self.assertEqual(logout_resp.status_code, 200)
        self.assertFalse(Token.objects.filter(key=token_key).exists())
        session_after = self.client.get("/api/v1/auth/session/")
        self.assertEqual(session_after.status_code, 401)

    def test_expired_otp_is_rejected(self):
        user = CustomUser.objects.create_user(
            phone="9876543212",
            email="expired@nexustekfusion.com",
            name="Expired Pilot",
            role="pilot",
        )
        PilotProfile.objects.create(
            user=user,
            drone_type="quadcopter",
            tank_capacity="10.00",
            bank_upi_id="exp@upi",
        )
        with patch("marketplace.services.generate_secure_otp", return_value="112233"):
            self.client.post(
                "/api/v1/auth/otp/request/",
                {"email": "expired@nexustekfusion.com"},
                format="json",
            )
        EmailOTP.objects.filter(email="expired@nexustekfusion.com").update(
            expires_at=timezone.now() - timedelta(minutes=6)
        )
        resp = self.client.post(
            "/api/v1/auth/otp/verify/",
            {"email": "expired@nexustekfusion.com", "otp": "112233", "role": "pilot"},
            format="json",
        )
        self.assertEqual(resp.status_code, 401)
