<?php
// =============================================================
// includes/product_image.php
//
// One place that knows how a product image gets onto disk and how
// its URL is built. Both admin forms and the products API go
// through here, so the hardening cannot drift between them.
//
// This replaces the original inline handling in add-product.php and
// edit-product.php, which had three defects worth naming because
// they are easy to reintroduce:
//
//   1. The stored extension came from the client-supplied filename,
//      so a .php could be written into a web-served directory.
//   2. move_uploaded_file()'s return value was discarded. Apache
//      runs as `daemon` and uploads/ is owned by the login user, so
//      the write always failed — yet the UPDATE still ran with the
//      new filename and the admin was told it had succeeded. The
//      product silently lost its image.
//   3. time().$ext collides for two uploads in the same second.
//
// Mirrors the avatar handling in api/profile.php, which got this
// right first.
// =============================================================

/** Absolute path of the directory new product images are written to. */
function productImageDir() {
    return dirname(__DIR__) . '/uploads/products';
}

/**
 * Stores an uploaded product image.
 *
 * Returns ['ok' => bool, 'filename' => ?string, 'error' => ?string].
 * On failure the caller MUST leave the existing image alone and must
 * not commit the row — reporting success after a failed write is the
 * bug this function exists to prevent.
 *
 * @param array       $file     One entry from $_FILES.
 * @param string|null $oldImage Current items.image, unlinked only if
 *                              this function generated the name.
 */
function saveProductImage($file, $oldImage = null) {
    require_once __DIR__ . '/image_upload.php';
    return saveUploadedImage($file, productImageDir(), 'product', $oldImage);
}


/**
 * Web path for a stored image, relative to the app root, or null when
 * the file does not exist on disk.
 *
 * Two locations because most rows predate this code: names generated
 * here live in uploads/products/, while legacy values sit directly in
 * uploads/. Returning null for a missing file is deliberate — the app
 * then draws its placeholder instead of retrying a 404 on every scroll.
 */
function productImageRelPath($image) {
    if (!$image) return null;

    // Refuse anything with path syntax in it; items.image is a bare name.
    if (strpbrk($image, "/\\") !== false || strpos($image, '..') !== false) {
        return null;
    }

    $root = dirname(__DIR__);
    if (is_file($root . '/uploads/products/' . $image)) {
        return '/uploads/products/' . $image;
    }
    if (is_file($root . '/uploads/' . $image)) {
        return '/uploads/' . $image;
    }
    return null;
}

/**
 * Absolute, client-usable URL for a stored image, or null.
 *
 * Product images have never rendered in the Android app because the API
 * returned the bare filename ("apple.png") and Coil cannot resolve a
 * relative name. Reuses appBaseUrl() from includes/user_payload.php,
 * the same helper that fixed avatars.
 */
function productImageUrl($image) {
    $rel = productImageRelPath($image);
    if ($rel === null) return null;

    require_once __DIR__ . '/user_payload.php';
    return appBaseUrl() . $rel;
}