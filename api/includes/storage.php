<?php
// =============================================================
// AfamFresh - uploaded file storage
// =============================================================
// One seam over two backends, chosen by whether GCS_BUCKET is set:
//
//   unset  -> local disk under uploads/, exactly as before. Dev on XAMPP
//             keeps working with no bucket and no service account.
//   set    -> Google Cloud Storage.
//
// The reason this exists: Cloud Run's filesystem is ephemeral and per
// instance. A product image written by one request lands on one container,
// is invisible to every other container, and is destroyed on the next
// deploy. Uploads survive locally and vanish in production, which is the
// worst way for a bug to behave.
//
// Objects are addressed by a path relative to the uploads root, e.g.
// "products/apple.webp" or "proof/proof_12_ab.jpg" — never an absolute
// filesystem path, so the same string works against either backend.
//
// Talks to the GCS JSON API directly with the service account already used
// for FCM. That avoids pulling in composer and the Google Cloud SDK for
// three calls, and reuses googleAccessToken() rather than re-implementing
// JWT signing.
// =============================================================

require_once __DIR__ . '/../admin/includes/config.php';
// appBaseUrl() lives here and is what the local backend builds URLs from.
// user_payload.php requires nothing itself, so this cannot cycle.
require_once __DIR__ . '/user_payload.php';

define('STORAGE_SCOPE', 'https://www.googleapis.com/auth/devstorage.read_write');

/** Bucket name, or '' when running on local disk. */
function storageBucket() {
    return (string)env('GCS_BUCKET', '');
}

/** True when uploads go to GCS rather than the local uploads/ directory. */
function storageIsRemote() {
    return storageBucket() !== '';
}

/** Absolute path for a local object. Only meaningful on the disk backend. */
function storageLocalPath($objectPath) {
    return dirname(__DIR__) . '/uploads/' . $objectPath;
}

/**
 * Rejects anything that could climb out of the uploads root.
 *
 * Callers build these paths from filenames this app generated, but the
 * product image helper also reads names straight out of items.image, which
 * is old data of uncertain provenance.
 */
function storagePathIsSafe($objectPath) {
    if ($objectPath === '' || $objectPath === null) return false;
    if (strpos($objectPath, '..') !== false) return false;
    if ($objectPath[0] === '/' || strpos($objectPath, '\\') !== false) return false;
    return (bool)preg_match('#^[A-Za-z0-9._/-]+$#', $objectPath);
}

/**
 * Stores an uploaded file.
 *
 * $tmpPath is a PHP upload temp file when $isUpload is true, in which case
 * move_uploaded_file() is used on the disk backend so the upload-origin
 * check is not bypassed.
 *
 * Returns true on success; logs and returns false otherwise.
 */
function storagePut($tmpPath, $objectPath, $contentType = 'application/octet-stream', $isUpload = true) {
    if (!storagePathIsSafe($objectPath)) {
        error_log("[storage] refused unsafe object path: $objectPath");
        return false;
    }

    if (!storageIsRemote()) {
        $dest = storageLocalPath($objectPath);
        $dir  = dirname($dest);
        if (!is_dir($dir) && !@mkdir($dir, 0775, true)) {
            error_log("[storage] cannot create $dir");
            return false;
        }
        $ok = $isUpload ? move_uploaded_file($tmpPath, $dest) : @copy($tmpPath, $dest);
        if (!$ok) {
            error_log("[storage] write failed to $dest (permissions?)");
        }
        return $ok;
    }

    $token = googleAccessToken(STORAGE_SCOPE);
    if ($token === null) return false;

    $body = @file_get_contents($tmpPath);
    if ($body === false) {
        error_log("[storage] could not read $tmpPath");
        return false;
    }

    $url = 'https://storage.googleapis.com/upload/storage/v1/b/' . rawurlencode(storageBucket())
        . '/o?uploadType=media&name=' . rawurlencode($objectPath);

    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_POST           => true,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT        => 60,
        CURLOPT_POSTFIELDS     => $body,
        CURLOPT_HTTPHEADER     => [
            'Authorization: Bearer ' . $token,
            'Content-Type: ' . $contentType,
            'Content-Length: ' . strlen($body),
            // Uploads are immutable: every filename is random and a replaced
            // image gets a new name, so a long cache is safe and keeps the
            // catalogue cheap to scroll.
            'Cache-Control: public, max-age=31536000, immutable',
        ],
    ]);
    $res  = curl_exec($ch);
    $code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    if ($res === false) {
        error_log('[storage] upload failed: ' . curl_error($ch));
        curl_close($ch);
        return false;
    }
    curl_close($ch);

    if ($code < 200 || $code >= 300) {
        error_log("[storage] upload of $objectPath rejected ($code): " . substr($res, 0, 250));
        return false;
    }

    // On the disk backend move_uploaded_file consumes the temp file; keep
    // the two backends behaving the same so callers cannot depend on it.
    if ($isUpload) @unlink($tmpPath);
    return true;
}

/** Deletes an object. Missing objects count as success. */
function storageDelete($objectPath) {
    if (!storagePathIsSafe($objectPath)) return false;

    if (!storageIsRemote()) {
        $p = storageLocalPath($objectPath);
        return is_file($p) ? @unlink($p) : true;
    }

    $token = googleAccessToken(STORAGE_SCOPE);
    if ($token === null) return false;

    $ch = curl_init('https://storage.googleapis.com/storage/v1/b/' . rawurlencode(storageBucket())
        . '/o/' . rawurlencode($objectPath));
    curl_setopt_array($ch, [
        CURLOPT_CUSTOMREQUEST  => 'DELETE',
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT        => 30,
        CURLOPT_HTTPHEADER     => ['Authorization: Bearer ' . $token],
    ]);
    curl_exec($ch);
    $code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);

    return ($code >= 200 && $code < 300) || $code === 404;
}

/**
 * Every object name in the bucket, as a lookup set, fetched once per request.
 *
 * Returns null when the listing could not be completed, which callers must
 * treat as "unknown" rather than "empty".
 */
function storageObjectIndex() {
    static $index = false;
    if ($index !== false) return $index;

    $token = googleAccessToken(STORAGE_SCOPE);
    if ($token === null) return $index = null;

    $names = [];
    $pageToken = '';
    // Bounded so a bucket that grows past ~5k objects degrades into extra
    // requests rather than an unbounded loop inside a web request.
    for ($page = 0; $page < 5; $page++) {
        $url = 'https://storage.googleapis.com/storage/v1/b/' . rawurlencode(storageBucket())
            . '/o?fields=items(name),nextPageToken&maxResults=1000'
            . ($pageToken !== '' ? '&pageToken=' . rawurlencode($pageToken) : '');

        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT        => 20,
            CURLOPT_HTTPHEADER     => ['Authorization: Bearer ' . $token],
        ]);
        $res  = curl_exec($ch);
        $code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
        curl_close($ch);

        if ($res === false || $code !== 200) {
            error_log("[storage] object listing failed ($code)");
            return $index = null;
        }

        $body = json_decode($res, true);
        foreach (($body['items'] ?? []) as $o) {
            if (isset($o['name'])) $names[$o['name']] = true;
        }
        $pageToken = $body['nextPageToken'] ?? '';
        if ($pageToken === '') break;
    }

    return $index = $names;
}

/**
 * Does the object exist?
 *
 * Callers use this to decide between an image URL and a placeholder.
 *
 * On GCS this reads a single listing of the bucket, cached for the request,
 * rather than one metadata call per object. A catalogue page asks about ~70
 * distinct images; as individual round trips that was 70 sequential HTTPS
 * calls — slow enough in-region and, measured from outside it, slow enough to
 * exceed a two-minute timeout.
 *
 * If the listing fails we answer true rather than false. A wrong true shows a
 * broken image; a wrong false hides one that is really there, and would blank
 * the whole catalogue the moment the listing call had a bad minute.
 */
function storageExists($objectPath) {
    if (!storagePathIsSafe($objectPath)) return false;

    if (!storageIsRemote()) {
        return is_file(storageLocalPath($objectPath));
    }

    $index = storageObjectIndex();
    if ($index === null) return true;

    return isset($index[$objectPath]);
}

/**
 * Client-usable URL for an object, or null when the path is unusable.
 *
 * Does NOT check existence — that is storageExists(), which costs a round
 * trip remotely. Callers that need a placeholder decision must ask.
 */
function storageUrl($objectPath) {
    if (!storagePathIsSafe($objectPath)) return null;

    if (!storageIsRemote()) {
        return appBaseUrl() . '/uploads/' . $objectPath;
    }

    // Objects are public-read via a bucket-level IAM binding, so a plain
    // download URL works and stays cacheable by the client.
    return 'https://storage.googleapis.com/' . rawurlencode(storageBucket())
        . '/' . str_replace('%2F', '/', rawurlencode($objectPath));
}
