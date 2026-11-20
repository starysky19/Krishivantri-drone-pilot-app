import hashlib
import hmac
import math
import secrets
from datetime import timedelta
from decimal import Decimal, ROUND_HALF_UP
import razorpay
from django.conf import settings
from django.core.mail import EmailMultiAlternatives, get_connection
from django.core.validators import validate_email
from django.core.exceptions import ValidationError
from django.db import connection, transaction
from django.db.models import F
from django.utils import timezone
from rest_framework.authtoken.models import Token

from .models import CommissionLog, CustomUser, EmailOTP, Order, PilotProfile

OTP_EXPIRY_SECONDS = 300
OTP_COOLDOWN_SECONDS = 60
OTP_MAX_REQUESTS_PER_15M = 5
OTP_MAX_VERIFY_ATTEMPTS = 5

TARIFF_PER_ACRE = Decimal("450.00")
COMMISSION_RATE = Decimal("0.10")
EARTH_RADIUS_METERS = 6371000.0
SQ_METERS_PER_ACRE = 4046.8564224


def normalize_email(raw_email: str) -> str:
    return (raw_email or "").strip().lower()


def is_valid_email_format(email: str) -> bool:
    try:
        validate_email(email)
        return True
    except ValidationError:
        return False


def generate_secure_otp(length: int = 6) -> str:
    upper = 10 ** length
    return f"{secrets.randbelow(upper):0{length}d}"


def hash_otp(email: str, otp: str, salt: str) -> str:
    payload = f"{normalize_email(email)}:{otp.strip()}:{salt}".encode("utf-8")
    digest = hashlib.pbkdf2_hmac("sha256", payload, settings.SECRET_KEY.encode("utf-8"), 120_000)
    return digest.hex()


def load_existing_smtp_config() -> dict:
    """
    Reuses existing SMTP settings stored in the database (if an existing SMTP config table is present)
    or Django settings loaded from environment variables. Never exposes credentials to clients.
    """
    candidate_tables = ("smtp_settings", "email_config", "email_settings", "app_smtp_config")
    try:
        existing_tables = set(connection.introspection.table_names())
        for table in candidate_tables:
            if table in existing_tables:
                with connection.cursor() as cursor:
                    cursor.execute(f'SELECT * FROM "{table}" LIMIT 1')
                    row = cursor.fetchone()
                    if row and cursor.description:
                        cols = [col[0].lower() for col in cursor.description]
                        data = dict(zip(cols, row))
                        host = data.get("smtp_host") or data.get("host") or ""
                        user = data.get("host_email") or data.get("smtp_user") or data.get("email") or ""
                        password = data.get("smtp_password") or data.get("email_password") or data.get("password") or ""
                        if host and user:
                            return {
                                "host": host,
                                "port": int(data.get("smtp_port") or data.get("port") or 587),
                                "username": user,
                                "password": password,
                                "use_tls": bool(data.get("use_tls", True)),
                                "use_ssl": bool(data.get("use_ssl", False)),
                                "from_email": data.get("from_email") or user,
                            }
    except Exception:
        pass

    return {
        "host": getattr(settings, "EMAIL_HOST", ""),
        "port": int(getattr(settings, "EMAIL_PORT", 587)),
        "username": getattr(settings, "EMAIL_HOST_USER", ""),
        "password": getattr(settings, "EMAIL_HOST_PASSWORD", ""),
        "use_tls": bool(getattr(settings, "EMAIL_USE_TLS", True)),
        "use_ssl": bool(getattr(settings, "EMAIL_USE_SSL", False)),
        "from_email": getattr(settings, "DEFAULT_FROM_EMAIL", "") or getattr(settings, "EMAIL_HOST_USER", ""),
    }


def send_otp_email(recipient_email: str, otp_code: str) -> None:
    smtp_cfg = load_existing_smtp_config()
    from_email = smtp_cfg.get("from_email") or smtp_cfg.get("username")
    subject = "NexusTekFusion Pilot Verification Code"
    text_body = (
        f"Your NexusTekFusion Krishivantri Pilot one-time verification code is: {otp_code}\n\n"
        f"This code expires in 5 minutes. If you did not request this login, please ignore this email."
    )
    html_body = (
        f"<p>Your <strong>NexusTekFusion Krishivantri Pilot</strong> one-time verification code is:</p>"
        f"<h2 style='letter-spacing:4px;color:#1B4332;'>{otp_code}</h2>"
        f"<p>This code expires in 5 minutes. Never share this code with anyone.</p>"
    )
    connection_kwargs = {}
    if smtp_cfg.get("host"):
        connection_kwargs = {
            "host": smtp_cfg["host"],
            "port": smtp_cfg["port"],
            "username": smtp_cfg["username"],
            "password": smtp_cfg["password"],
            "use_tls": smtp_cfg["use_tls"],
            "use_ssl": smtp_cfg["use_ssl"],
        }
    with get_connection(fail_silently=False, **connection_kwargs) as mail_conn:
        msg = EmailMultiAlternatives(
            subject=subject,
            body=text_body,
            from_email=from_email,
            to=[recipient_email],
            connection=mail_conn,
        )
        msg.attach_alternative(html_body, "text/html")
        msg.send(fail_silently=False)


def issue_email_otp(raw_email: str) -> tuple[bool, int, str]:
    email = normalize_email(raw_email)
    if not is_valid_email_format(email):
        return False, 400, "Enter a valid email address."

    now = timezone.now()
    latest = EmailOTP.objects.filter(email=email).first()
    if latest and (now - latest.created_at).total_seconds() < OTP_COOLDOWN_SECONDS:
        wait_s = max(1, int(OTP_COOLDOWN_SECONDS - (now - latest.created_at).total_seconds()))
        return False, 429, f"Please wait {wait_s} seconds before requesting another OTP."

    window_start = now - timedelta(minutes=15)
    recent_count = EmailOTP.objects.filter(email=email, created_at__gte=window_start).count()
    if recent_count >= OTP_MAX_REQUESTS_PER_15M:
        return False, 429, "Too many OTP requests. Please try again later."

    user = CustomUser.objects.filter(email__iexact=email, is_active=True).first()
    if user is None:
        # Non-enumerating response: enforce identical cooldown without sending an email
        EmailOTP.objects.create(
            email=email,
            otp_hash="unregistered_noop",
            salt=secrets.token_hex(8),
            expires_at=now + timedelta(seconds=OTP_COOLDOWN_SECONDS),
            is_used=True,
        )
        return True, 200, "If this email is registered with NexusTekFusion, an OTP has been sent."

    otp_code = generate_secure_otp(6)
    salt = secrets.token_hex(16)
    otp_digest = hash_otp(email, otp_code, salt)
    expires_at = now + timedelta(seconds=OTP_EXPIRY_SECONDS)

    with transaction.atomic():
        EmailOTP.objects.filter(email=email, is_used=False).update(is_used=True)
        EmailOTP.objects.create(
            email=email,
            otp_hash=otp_digest,
            salt=salt,
            expires_at=expires_at,
            max_attempts=OTP_MAX_VERIFY_ATTEMPTS,
        )

    send_otp_email(email, otp_code)
    return True, 200, "If this email is registered with NexusTekFusion, an OTP has been sent."


def verify_email_otp(raw_email: str, otp_code: str, expected_role: str = "pilot") -> tuple[bool, int, str, CustomUser | None, str | None]:
    email = normalize_email(raw_email)
    clean_otp = (otp_code or "").strip()
    if not is_valid_email_format(email) or not clean_otp.isdigit():
        return False, 400, "Invalid or expired OTP.", None, None

    now = timezone.now()
    with transaction.atomic():
        record = EmailOTP.objects.select_for_update().filter(email=email, is_used=False).first()
        if record is None:
            return False, 401, "Invalid or expired OTP.", None, None
        if record.expires_at <= now:
            record.is_used = True
            record.save(update_fields=["is_used"])
            return False, 401, "Invalid or expired OTP.", None, None
        if record.attempt_count >= record.max_attempts:
            record.is_used = True
            record.save(update_fields=["is_used"])
            return False, 429, "Too many incorrect OTP attempts. Request a new OTP.", None, None

        expected_digest = hash_otp(email, clean_otp, record.salt)
        if not hmac.compare_digest(record.otp_hash, expected_digest):
            record.attempt_count += 1
            if record.attempt_count >= record.max_attempts:
                record.is_used = True
            record.save(update_fields=["attempt_count", "is_used"])
            return False, 401, "Invalid or expired OTP.", None, None

        user = CustomUser.objects.filter(email__iexact=email, is_active=True).first()
        if user is None or (expected_role and user.role != expected_role):
            record.is_used = True
            record.save(update_fields=["is_used"])
            return False, 401, "Invalid or expired OTP.", None, None

        record.is_used = True
        record.save(update_fields=["is_used"])
        token, _ = Token.objects.get_or_create(user=user)
        return True, 200, "Authenticated.", user, token.key


def calculate_polygon_area_acres(coordinates: list[dict]) -> Decimal:
    """
    Computes geodesic polygon area in acres from GPS coordinates [{'lat': ..., 'lng': ...}].
    """
    if not coordinates or len(coordinates) < 3:
        return Decimal("0.00")

    avg_lat_rad = sum(math.radians(float(pt["lat"])) for pt in coordinates) / len(coordinates)
    cos_lat = math.cos(avg_lat_rad)
    xy = [
        (
            EARTH_RADIUS_METERS * math.radians(float(pt["lng"])) * cos_lat,
            EARTH_RADIUS_METERS * math.radians(float(pt["lat"])),
        )
        for pt in coordinates
    ]
    area_sq_m = abs(
        sum(xy[i][0] * xy[(i + 1) % len(xy)][1] - xy[(i + 1) % len(xy)][0] * xy[i][1] for i in range(len(xy)))
    ) / 2.0
    acres = Decimal(str(area_sq_m / SQ_METERS_PER_ACRE)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    return acres


def calculate_tariff_split(acres: Decimal) -> tuple[Decimal, Decimal, Decimal]:
    total = (acres * TARIFF_PER_ACRE).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    admin_commission = (total * COMMISSION_RATE).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    pilot_earnings = (total - admin_commission).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    return total, admin_commission, pilot_earnings


def compute_pilot_priority_score(pilot: PilotProfile) -> float:
    drone_boost = 25.0 if pilot.drone_type == "hexacopter" else 10.0
    verified_boost = 15.0 if pilot.is_license_verified else 0.0
    return float(pilot.rating_average) * 20.0 + drone_boost + float(pilot.tank_capacity) * 0.5 + verified_boost


def create_razorpay_order(amount_inr: Decimal, receipt_id: str) -> dict:
    if not settings.RAZORPAY_KEY_ID or not settings.RAZORPAY_KEY_SECRET:
        return {"id": f"order_local_{receipt_id}", "amount": int(amount_inr * 100), "currency": "INR"}
    client = razorpay.Client(auth=(settings.RAZORPAY_KEY_ID, settings.RAZORPAY_KEY_SECRET))
    return client.order.create(
        {
            "amount": int(amount_inr * 100),
            "currency": "INR",
            "receipt": receipt_id,
            "payment_capture": 1,
        }
    )


def accept_order_atomic(pilot_id, order_id) -> tuple[bool, str]:
    """
    Atomically assigns an order to a pilot.
    Enforces the Cash Order Wallet Rule: wallet_balance >= 10% admin_commission.
    Deducts that 10% commission immediately upon acceptance.
    """
    with transaction.atomic():
        pilot = PilotProfile.objects.select_for_update().get(pk=pilot_id)
        order = Order.objects.select_for_update().get(pk=order_id)

        if order.order_status != "pending":
            return False, "Order is no longer pending."

        if order.payment_method == "cash":
            if pilot.wallet_balance < order.admin_commission:
                return (
                    False,
                    f"Insufficient wallet balance. Need ₹{order.admin_commission} (10% commission) to accept Cash on Field orders.",
                )
            pilot.wallet_balance = (pilot.wallet_balance - order.admin_commission).quantize(Decimal("0.01"))
            pilot.save(update_fields=["wallet_balance"])
            CommissionLog.objects.create(
                order=order,
                pilot=pilot,
                amount=order.admin_commission,
                payment_method="cash",
                note="10% Cash Order Commission deducted on acceptance",
            )

        order.assigned_pilot = pilot
        order.order_status = "accepted"
        order.pilot_eta_minutes = 12 if pilot.drone_type == "hexacopter" else 16
        order.save(update_fields=["assigned_pilot", "order_status", "pilot_eta_minutes"])
        return True, "Order accepted."


def complete_order_atomic(order_id) -> Order:
    with transaction.atomic():
        order = Order.objects.select_for_update().select_related("farmer", "assigned_pilot").get(pk=order_id)
        if order.order_status == "completed":
            return order

        order.order_status = "completed"
        order.payment_status = "paid"
        order.pilot_eta_minutes = 0
        order.save(update_fields=["order_status", "payment_status", "pilot_eta_minutes"])

        # Gamification: 1 Order = 1 Harvest Coin
        order.farmer.coins = F("coins") + 1
        order.farmer.save(update_fields=["coins"])

        if order.payment_method == "online" and order.assigned_pilot is not None:
            CommissionLog.objects.create(
                order=order,
                pilot=order.assigned_pilot,
                amount=order.admin_commission,
                payment_method="online",
                note="10% Online Razorpay Commission settled on completion",
            )
        return order
