# AGENTS

This repo is a multi-platform AfamFresh app with a shared Android codebase and a PHP backend. Keep changes consistent with the existing Google/Firebase, Maps, and role-based architecture.

## Project map

- Android app: [app](app)
- Shared app code and build config: [app/build.gradle.kts](app/build.gradle.kts)
- PHP API: [api](api)
- Deployment notes for Cloud Run + Cloud SQL + Firebase: [api/DEPLOY.md](api/DEPLOY.md)
- iOS app: [afamfresh-ios](afamfresh-ios)
- Release/build metadata: [gradle.properties](gradle.properties), [settings.gradle.kts](settings.gradle.kts), [build.gradle.kts](build.gradle.kts)

## What agents need to know

### 1) One Android app, three role flavors

The Android project uses product flavors: `customer`, `rider`, and `vendor` in [app/build.gradle.kts](app/build.gradle.kts). Shared logic lives under [app/src/main](app/src/main), while flavor-specific screens and logic live under [app/src/customer](app/src/customer), [app/src/rider](app/src/rider), and [app/src/vendor](app/src/vendor).

Do not treat the app flavors as isolated codebases. Keep shared behavior in common modules and only localize role-specific UI or permissions.

### 2) Secrets live in local.properties, not source control

The app reads machine-specific values from `local.properties` and not directly from checked-in files. The most important keys are:

- `google.maps.api.key`
- `google.maps.api.key.rider`
- `base.url.debug`
- `base.url.release`
- `release.store.file`, `release.store.password`, `release.key.alias`, `release.key.password`

See [app/build.gradle.kts](app/build.gradle.kts) for the exact contract. Do not add hard-coded Google API keys, backend URLs, or signed-release settings to source files.

### 3) Google auth is a security-sensitive flow

The Android Google sign-in flow is implemented in [app/src/main/java/com/techaus/afamfresh/repository/AuthRepository.kt](app/src/main/java/com/techaus/afamfresh/repository/AuthRepository.kt). The server validation lives in [api/api/auth.php](api/api/auth.php).

Important constraints:

- Validate the ID token on the server, not only on the client.
- Check the `aud` claim against the configured `GOOGLE_WEB_CLIENT_ID`.
- Require `email_verified` to be exactly `"true"` before linking or logging in by email.
- Preserve the app-role/account-type checks so customer/rider/vendor access stays consistent.

If a Google-auth bug appears, investigate the server-side token validation before changing client behavior.

### 4) Google Maps and routing are backend-aware

The app consumes Google Maps and geocoding keys through [app/build.gradle.kts](app/build.gradle.kts), but the backend routing logic is in [api/includes/google_routes.php](api/includes/google_routes.php) and related delivery-fee code under [api/includes](api/includes).

When changing delivery pricing or route logic, check both sides:

- Android side: maps UI, delivery map screens, geocoding usage
- Backend side: route-distance calls, OSRM fallback, and approved API keys

### 5) Backend is PHP + MySQL, not Firebase-hosted

The backend is not a Firebase Hosting app. The deployment notes explain the Cloud Run + Cloud SQL model in [api/DEPLOY.md](api/DEPLOY.md). Firebase here is mainly for:

- Google Sign-In / OAuth
- Firebase Cloud Messaging
- Google Services configuration

The backend should keep Google/Firebase config in environment variables and secret storage rather than embedding project IDs, client IDs, or service credentials in app code.

## Daily commands

Use the repo’s real commands instead of guessing:

- List tasks: `./gradlew :app:tasks --all`
- Run unit tests: `./gradlew test`
- Run debug build: `./gradlew :app:assembleDebug`
- Run release build: `./gradlew :app:assembleRelease`
- Lint the app: `./gradlew :app:lint`

For the PHP backend, prefer the project’s own scripts and deployment instructions in [api](api) rather than creating new ad hoc setup steps.

## Conventions for AI coding agents

- Prefer the smallest validated fix that matches the repo’s existing patterns.
- When working on Google auth, maps, Firebase, or deployment config, verify the project settings and env values before editing code.
- Preserve server-side validation and role checks; do not weaken them in the name of “making the client work.”
- Keep the backend and app config consistent with the existing Cloud Run + Cloud SQL deployment model described in [api/DEPLOY.md](api/DEPLOY.md).
- If a feature touches multiple layers, check the wiring between [app/src/main](app/src/main) and [api/api](api/api) rather than patching only one side.
- Do not commit secret material or generated service credentials.

## Useful references

- Android build and secret handling: [app/build.gradle.kts](app/build.gradle.kts)
- Google sign-in: [app/src/main/java/com/techaus/afamfresh/repository/AuthRepository.kt](app/src/main/java/com/techaus/afamfresh/repository/AuthRepository.kt)
- Server token verification: [api/api/auth.php](api/api/auth.php)
- Cloud deployment: [api/DEPLOY.md](api/DEPLOY.md)
- Route calculation: [api/includes/google_routes.php](api/includes/google_routes.php)

## Suggested follow-up customizations

If the repo grows more specialized, a next useful addition would be a backend-specific instruction file for the PHP/API layer or a Google/Firebase-focused skill for auth, maps, and deployment conventions.
