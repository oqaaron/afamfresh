# Deploying the AfamFresh backend to Cloud Run

The backend is PHP 8.2 + MySQL. Firebase cannot host it — Firebase Hosting
serves static files and Cloud Functions run Node/Python/Go/Java, not PHP. So
Firebase stays what it already is here (Cloud Messaging + Analytics, and
optionally App Distribution for test APKs) and the container runs on Cloud
Run against Cloud SQL, in the **same GCP project as Firebase**
(`afamfresh-f68c6`), so there is one account, one bill and one IAM.

Everything below marked **[you]** needs console or billing access and cannot
be scripted from the repo.

---

## 1. Enable the services **[you]**

In project `afamfresh-f68c6`, with billing enabled:

```bash
gcloud config set project afamfresh-f68c6
gcloud services enable \
  run.googleapis.com \
  sqladmin.googleapis.com \
  cloudbuild.googleapis.com \
  secretmanager.googleapis.com \
  artifactregistry.googleapis.com
```

## 2. Create the database **[you]**

Cloud SQL has no MariaDB flavour, so this moves to **MySQL 8.0**. That is
fine — `schema.sql` is exported already normalised for it, and the SQL the
app uses is portable. The one MariaDB-specific trap (`current_role` parsing
as the built-in `CURRENT_ROLE()` function rather than the column) exists in
MySQL 8.0 too, and is already fixed by backticking everywhere it is read.

```bash
gcloud sql instances create afamfresh \
  --database-version=MYSQL_8_0 \
  --tier=db-f1-micro \
  --region=europe-west3 \
  --storage-size=10GB \
  --storage-auto-increase

gcloud sql databases create kitchen \
  --instance=afamfresh \
  --charset=utf8mb4 --collation=utf8mb4_unicode_ci

gcloud sql users create afamfresh \
  --instance=afamfresh \
  --password='<pick a strong one>'
```

The instance connection name is what Cloud Run needs — note it down:

```bash
gcloud sql instances describe afamfresh \
  --format='value(connectionName)'
# afamfresh-f68c6:europe-west3:afamfresh
```

`db-f1-micro` is the cheapest tier and is enough for current traffic
(55 tables, 2.8 MB, 153 orders). Expect roughly **$9–10/month** — this is the
only meaningful running cost; Cloud Run scales to zero and will be
approximately free at this volume.

## 3. Load the schema **[you]**

`schema.sql` holds the structure of all 55 tables plus the catalogue,
pricing, delivery slots and UI copy. It deliberately contains **no customer,
order, rider or admin rows**.

```bash
gcloud sql connect afamfresh --user=afamfresh --database=kitchen < schema.sql
```

Regenerate it any time the schema changes:

```bash
./scripts/export-schema.sh      # writes schema.sql
```

If you also want the existing live data (orders, users), that is a separate
`mysqldump` of those tables — decide deliberately, since it moves personal
data into a new jurisdiction.

## 3b. Create the uploads bucket **[you]**

Cloud Run's filesystem is ephemeral and per instance: an uploaded product
image lands on one container, is invisible to the others, and is gone on the
next deploy. Uploads therefore go to a bucket.

```bash
gcloud storage buckets create gs://afamfresh-uploads \
  --location=europe-west3 --uniform-bucket-level-access

# Objects are served straight to the app, so they must be publicly readable.
gcloud storage buckets add-iam-policy-binding gs://afamfresh-uploads \
  --member=allUsers --role=roles/storage.objectViewer
```

Copy the existing files up once, from a machine that has both `uploads/` and
the service account:

```bash
GCS_BUCKET=afamfresh-uploads \
FIREBASE_CREDENTIALS=/path/to/service-account.json \
  /opt/lampp/bin/php scripts/migrate-uploads-to-gcs.php --dry-run   # preview
# then drop --dry-run
```

It is idempotent, so a partial run can simply be repeated.

> Most image rows have no file to migrate. 70 of 72 products and all 27
> delivery proof photos reference filenames that no longer exist on disk —
> lost in an earlier move, long before this migration. The script reports the
> count at the end. Those products show a placeholder until the images are
> re-uploaded through the admin console.

Leaving `GCS_BUCKET` empty keeps everything on local disk, which is what
development should do — no bucket and no service account needed.

## 4. Put the credentials in Secret Manager **[you]**

The workflow injects these with `--set-secrets`, not `--set-env-vars`,
because values passed as plain env vars are readable by anyone with viewer
access on the service.

```bash
for s in DB_USER DB_PASS DB_NAME PESAPAL_ENV \
         PESAPAL_CONSUMER_KEY PESAPAL_CONSUMER_SECRET \
         PESAPAL_IPN_ID PESAPAL_PUBLIC_BASE_URL \
         BREVO_API_KEY TWILIO_ACCOUNT_SID TWILIO_AUTH_TOKEN; do
  printf '%s' "<value>" | gcloud secrets create "$s" --data-file=-
done

# The Firebase service account, as JSON content rather than a file:
gcloud secrets create FIREBASE_CREDENTIALS_JSON \
  --data-file=afamfresh-f68c6-firebase-adminsdk-XXXX.json
```

Grant the Cloud Run runtime service account read access:

```bash
PROJNUM=$(gcloud projects describe afamfresh-f68c6 --format='value(projectNumber)')
for s in DB_USER DB_PASS DB_NAME PESAPAL_ENV PESAPAL_CONSUMER_KEY \
         PESAPAL_CONSUMER_SECRET PESAPAL_IPN_ID PESAPAL_PUBLIC_BASE_URL \
         BREVO_API_KEY TWILIO_ACCOUNT_SID TWILIO_AUTH_TOKEN \
         FIREBASE_CREDENTIALS_JSON; do
  gcloud secrets add-iam-policy-binding "$s" \
    --member="serviceAccount:${PROJNUM}-compute@developer.gserviceaccount.com" \
    --role=roles/secretmanager.secretAccessor
done
```

> **Reissue the Pesapal live key and secret first.** They were exposed in a
> development transcript. Moving them into Secret Manager protects them from
> here on but does not undo that exposure — generate a new pair in the
> Pesapal merchant dashboard and store the new values.

## 5. GitHub repository secrets **[you]**

Settings → Secrets and variables → Actions. The workflow checks all of these
up front and fails by name if any is missing.

| Secret | Value |
| --- | --- |
| `GCP_PROJECT_ID` | `afamfresh-f68c6` |
| `GCP_CREDENTIALS` | JSON key for a deployer service account |
| `CLOUD_SQL_INSTANCE` | `afamfresh-f68c6:europe-west3:afamfresh` |
| `DB_USER` `DB_PASS` `DB_NAME` | same values as the secrets above |
| `GCS_BUCKET` | `afamfresh-uploads` |
| `GOOGLE_WEB_CLIENT_ID` | Web client id from the new project's OAuth credentials |
| `PESAPAL_*`, `BREVO_API_KEY`, `TWILIO_*` | same values as the secrets above |

The deployer service account needs `roles/run.admin`,
`roles/cloudbuild.builds.editor`, `roles/storage.admin` and
`roles/iam.serviceAccountUser`.

## 6. Deploy

Push to `main` touching `api/**` or `Dockerfile`, or run the workflow
manually from the Actions tab. It builds, deploys with the Cloud SQL socket
attached, then smoke-tests `GET /api/products.php?action=list` and fails the
run on anything other than 200 — a green deploy serving 500s is worse than a
red one, because nobody looks twice.

## 7. Point the apps at it

Cloud Run gives the service its own HTTPS URL immediately:

```bash
gcloud run services describe afamfresh-backend \
  --region=europe-west3 --format='value(status.url)'
# https://afamfresh-backend-XXXXXX-ew.a.run.app
```

Put it in `local.properties` (not committed):

```properties
base.url.release=https://afamfresh-backend-XXXXXX-ew.a.run.app/api/
```

This sidesteps DNS entirely. `afam.techaus.online` currently has **no DNS
record** — it does not resolve — so any release build pointing there reaches
nothing. Use the Cloud Run URL until you decide to map the domain; when you
do, `gcloud run domain-mappings create` handles the certificate.

Release builds block cleartext HTTP, so the URL must be `https://`. It will
be.

## 8. Still outstanding, console-only **[you]**

- **Google Sign-In is broken in all three apps.** `google-services.json` has
  `oauth_client: []` and zero certificate hashes. Add the debug SHA-1
  `5C:DB:E2:34:4E:5B:1D:B7:D3:D4:F3:47:44:26:36:B3:25:24:AF:23` (and the
  release SHA-1 when you sign) for each of `com.techaus.afamfresh`,
  `.rider` and `.vendor`, confirm Authentication → Sign-in method → Google
  is enabled, then re-download `google-services.json`. Verify with
  `grep -c client_type app/google-services.json` — it is `0` today.
- **Push notifications are dead until `FIREBASE_CREDENTIALS_JSON` is set.**
  The send path is correct FCM v1 code, but no service account key exists
  anywhere on the current machine. Generate one at Project settings →
  Service accounts → Generate new private key.
- **Uploads do not survive a redeploy.** Cloud Run's filesystem is
  ephemeral, so `uploads/` is wiped every deployment. Product images and
  delivery proof photos need a GCS bucket before this is production-real.
  This is the one genuine code change still outstanding.
