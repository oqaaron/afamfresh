package com.techaus.afamfresh.models

import com.google.gson.annotations.SerializedName
import com.techaus.afamfresh.BuildConfig

// Verified against the real backend: every user-returning branch of auth.php,
// plus every action of profile.php, goes through buildUserPayload() in
// includes/user_payload.php, so this shape is guaranteed identical whichever
// endpoint produced it.
//
// ⚠️ EVERY field added here must be nullable with a null default.
// Gson populates fields reflectively and never runs the Kotlin constructor, so
// a non-null type whose JSON key is absent holds a genuine null at runtime and
// the declared default never applies. That is not theoretical: `roles` did
// exactly this and crashed login on every sign-in. Nullable + null default
// makes "server didn't send it" and "old cached value" the same safe state.

data class User(
    @SerializedName("id") val id: String,
    // The server still sends `name` (fname + lname). Kept because this is the
    // only name the app had before, and the prefs cache stores it.
    @SerializedName("name") val name: String,
    @SerializedName("email") val email: String,
    @SerializedName("mobile") val mobile: String? = null,
    @SerializedName("fname") val fname: String? = null,
    @SerializedName("lname") val lname: String? = null,
    @SerializedName("area") val area: String? = null,
    @SerializedName("address") val address: String? = null,
    /** Absolute URL — uploaded picture, else the Google photo, else null. */
    @SerializedName("avatar_url") val avatarUrl: String? = null,
    /** False for Google accounts that never set a password. */
    @SerializedName("has_password") val hasPassword: Boolean? = null,
    @SerializedName("is_google_account") val isGoogleAccount: Boolean? = null,
    @SerializedName("loyalty_points") val loyaltyPoints: Int? = null,
    @SerializedName("notification_preferences") val notificationPreferences: NotificationPrefs? = null,
    @SerializedName("roles") val roles: List<String> = listOf("user"),
    @SerializedName("current_role") val currentRole: String = "user",
    /**
     * What this account is FOR: "customer", "rider", "vendor", "wholesaler".
     *
     * Distinct from [roles], which is what an admin has approved so far. This
     * is fixed at registration and never changes, and the server refuses an
     * account whose type does not match the app asking. Nullable because an
     * older backend does not send it — the Gson trap where an absent key
     * defeats a non-null default.
     */
    @SerializedName("account_type") val accountType: String? = null
)

data class NotificationPrefs(
    @SerializedName("email") val email: Boolean = true,
    @SerializedName("push") val push: Boolean = true
)

// ---------------------------------------------------------------------------
// Derived accessors
//
// These exist so no screen has to re-handle the awkward cases: a payload from
// an older server with no fname/lname, and the literal 'Not specified' that
// register writes into area/address (three live rows have it). A user should
// see an empty field to fill in, not a sentinel they have to delete first.
// ---------------------------------------------------------------------------

/** First name, falling back to splitting the combined `name`. */
val User.firstNameOrDerived: String
    get() = fname?.takeIf { it.isNotBlank() } ?: name.substringBefore(' ', name)

/** Last name, falling back to splitting the combined `name`. */
val User.lastNameOrDerived: String
    get() = lname?.takeIf { it.isNotBlank() } ?: name.substringAfter(' ', "")

private const val NOT_SPECIFIED = "Not specified"

val User.areaOrEmpty: String
    get() = area?.takeIf { it.isNotBlank() && it != NOT_SPECIFIED } ?: ""

val User.addressOrEmpty: String
    get() = address?.takeIf { it.isNotBlank() && it != NOT_SPECIFIED } ?: ""

/**
 * Whether to offer a change-password form.
 *
 * Defaults to true when the server didn't say (an older payload), because the
 * endpoint re-checks anyway and returns a clear message — showing the option
 * and explaining beats hiding it and leaving the user with no route at all.
 */
val User.canChangePassword: Boolean
    get() = hasPassword != false

data class UpdateProfileRequest(
    @SerializedName("fname") val fname: String,
    @SerializedName("lname") val lname: String,
    @SerializedName("email") val email: String,
    @SerializedName("mobile") val mobile: String?,
    @SerializedName("area") val area: String,
    @SerializedName("address") val address: String,
    /** Only required when the email is actually changing. */
    @SerializedName("current_password") val currentPassword: String? = null
)

/** Save state for profile/password screens, kept separate from login's. */
sealed class ProfileSaveState {
    object Idle : ProfileSaveState()
    object Saving : ProfileSaveState()
    object Saved : ProfileSaveState()
    data class Error(val message: String) : ProfileSaveState()
}

data class LoginRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
    /**
     * Which app is asking, from BuildConfig.APP_ROLE.
     *
     * The server refuses an account whose `account_type` does not match, so a
     * rider cannot sign in to the Customer app and vice versa. Defaulted here
     * rather than at every call site; the server also defaults to customer for
     * installs that predate the split and send nothing.
     */
    @SerializedName("app_role") val appRole: String = BuildConfig.APP_ROLE
)

data class LoginResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("token") val token: String? = null,
    @SerializedName("user") val user: User? = null,
    @SerializedName("error") val error: String? = null
)

/**
 * Response for verify_phone_otp — third sign-in mechanism alongside password
 * (LoginResponse above) and Google. Doesn't fit LoginResponse's shape: an
 * existing number returns token+user exactly like LoginResponse, but a new
 * number returns mobile+proof_token instead, since no account exists yet for
 * complete_phone_signup to log in to. success is non-nullable, matching every
 * other response class here — every action.php path always includes it.
 * Everything else stays nullable with a null default: token/user are only
 * ever present on an existing-number response, mobile/proof_token only ever
 * on a new-number one, and Gson leaves an absent JSON key as a genuine null
 * regardless of what default is declared (see the warning above User).
 */
data class PhoneVerifyResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("is_new_user") val isNewUser: Boolean? = null,
    @SerializedName("token") val token: String? = null,
    @SerializedName("user") val user: User? = null,
    @SerializedName("mobile") val mobile: String? = null,
    @SerializedName("proof_token") val proofToken: String? = null,
    @SerializedName("error") val error: String? = null
)

data class RegisterRequest(
    @SerializedName("name") val name: String,
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
    @SerializedName("mobile") val mobile: String? = null,
    /**
     * The app doing the registering, from BuildConfig.APP_ROLE.
     *
     * This decides `users.account_type`, which is fixed for the life of the
     * account: registering in the Rider app creates a rider account that
     * cannot shop. The server used to read this field and then ignore it,
     * which is how every account ended up being a customer as well.
     */
    @SerializedName("role") val role: String = BuildConfig.APP_ROLE
)

data class RegisterResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("user") val user: User? = null,
    @SerializedName("error") val error: String? = null
)

data class RiderRegistrationRequest(
    @SerializedName("fname") val firstName: String,
    @SerializedName("lname") val lastName: String,
    @SerializedName("email") val email: String,
    @SerializedName("phone") val phone: String,
    @SerializedName("password") val password: String,
    @SerializedName("vehicle_type") val vehicleType: String,
    @SerializedName("vehicle_plate") val vehiclePlate: String
)

data class UserResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("user") val user: User? = null,
    @SerializedName("error") val error: String? = null
)

data class BaseResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("error") val error: String? = null
)

data class RoleSwitchResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("current_role") val currentRole: String? = null,
    @SerializedName("error") val error: String? = null
)

// Drives LoginScreen's LaunchedEffect(loginState) block
sealed class LoginUiState {
    object Idle : LoginUiState()
    object Loading : LoginUiState()
    data class Success(val user: User) : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}
