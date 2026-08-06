<?php
// =============================================================
// includes/image_upload.php
//
// The single hardened image-upload path. Product images, avatars
// and proof-of-delivery photos all land here, so the checks cannot
// drift apart between callers.
//
// The defects this exists to prevent, all of which were live in
// admin/add-product.php before it was rewritten:
//
//   1. Taking the stored extension from the client-supplied
//      filename, which lets a .php reach a web-served directory.
//   2. Discarding move_uploaded_file()'s return value. Apache runs
//      as `daemon` and uploads/ is owned by the login user, so the
//      write failed while the caller reported success and committed
//      a filename pointing at nothing.
//   3. time().$ext filenames, which collide within one second.
// =============================================================

/**
 * Validates and stores one uploaded image.
 *
 * Returns ['ok' => bool, 'filename' => ?string, 'error' => ?string].
 * On failure the caller MUST NOT commit the row — reporting success
 * after a failed write is the bug this function exists to prevent.
 *
 * @param array       $file   One entry from $_FILES.
 * @param string      $absDir Absolute target directory. Must be writable
 *                            by Apache's user; see uploads/avatars for the
 *                            no-root 1777 + sticky-bit pattern.
 * @param string      $prefix Filename prefix, e.g. 'product' or 'proof'.
 * @param string|null $old    Previous filename, unlinked only when it matches
 *                            a name this function generated.
 * @param int         $maxBytes Size cap, default 5 MB.
 */
function saveUploadedImage($file, $absDir, $prefix, $old = null, $maxBytes = 5242880) {
    $bad = function ($message) {
        return ['ok' => false, 'filename' => null, 'error' => $message];
    };

    if (!is_array($file) || !isset($file['error'])) {
        return $bad('No image was received.');
    }

    $err = $file['error'];
    if ($err === UPLOAD_ERR_INI_SIZE || $err === UPLOAD_ERR_FORM_SIZE) {
        return $bad('That image is too large. Please choose a smaller one.');
    }
    if ($err !== UPLOAD_ERR_OK) {
        error_log("Image upload error code $err for prefix $prefix");
        return $bad('Upload failed. Please try again.');
    }
    if ($file['size'] > $maxBytes) {
        $mb = round($maxBytes / 1048576, 1);
        return $bad("That image is too large. Please choose one under {$mb} MB.");
    }

    $tmp = $file['tmp_name'];
    if (!is_uploaded_file($tmp)) {
        // Guards against a caller passing an arbitrary path in place of a
        // real upload.
        return $bad('Upload failed. Please try again.');
    }

    // Type comes from sniffing the bytes, never from the submitted filename.
    // getimagesize() is a second opinion: it rejects files carrying a valid
    // image header that are not actually decodable images.
    $allowed = ['image/jpeg' => 'jpg', 'image/png' => 'png', 'image/webp' => 'webp'];
    $finfo = finfo_open(FILEINFO_MIME_TYPE);
    $mime  = finfo_file($finfo, $tmp);
    finfo_close($finfo);

    if (!isset($allowed[$mime]) || getimagesize($tmp) === false) {
        return $bad('Only JPG, PNG or WebP images are supported.');
    }
    $ext = $allowed[$mime];

    if (!is_dir($absDir) && !@mkdir($absDir, 0775, true)) {
        error_log("Upload dir missing and not creatable: $absDir");
        return $bad("Couldn't save the image. Please try again.");
    }

    $filename = $prefix . '_' . bin2hex(random_bytes(8)) . '.' . $ext;

    if (!move_uploaded_file($tmp, $absDir . '/' . $filename)) {
        // Nearly always permissions: the directory has to be writable by
        // Apache's user (`daemon`), which is why these are created 1777
        // rather than inheriting the login user's 755.
        error_log("move_uploaded_file failed to $absDir/$filename (permissions?)");
        return $bad("Couldn't save the image — the server could not write the file.");
    }

    // Only ever unlink a name this code generated. The database also holds
    // legacy hand-placed filenames, and deleting one of those on the strength
    // of a database string would be unrecoverable.
    if ($old && preg_match('/^' . preg_quote($prefix, '/') . '_[0-9a-f]{16}\.(jpg|png|webp)$/', $old)) {
        @unlink($absDir . '/' . $old);
    }

    return ['ok' => true, 'filename' => $filename, 'error' => null];
}
