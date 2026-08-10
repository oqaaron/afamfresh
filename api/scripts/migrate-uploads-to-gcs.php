<?php
// =============================================================
// Copy everything under uploads/ into the GCS bucket.
// =============================================================
// Run once, before the first Cloud Run deploy, from a machine that has
// both the local uploads/ directory and the service account:
//
//   GCS_BUCKET=afamfresh-uploads \
//   FIREBASE_CREDENTIALS=/path/to/service-account.json \
//   /opt/lampp/bin/php scripts/migrate-uploads-to-gcs.php [--dry-run]
//
// Idempotent: an object already present is skipped, so re-running after a
// partial failure costs only the existence checks.
//
// Note this uploads what is ON DISK. The database references many images
// whose files were lost in an earlier move; those rows cannot be repaired
// from here and are reported at the end so the gap is visible rather than
// discovered later by a customer looking at a blank catalogue.
// =============================================================

$dryRun = in_array('--dry-run', $argv, true);

require_once __DIR__ . '/../includes/storage.php';

if (!storageIsRemote()) {
    fwrite(STDERR, "GCS_BUCKET is not set — nothing to migrate to.\n");
    exit(1);
}
if (getFirebaseServiceAccount() === null) {
    fwrite(STDERR, "No service account — set FIREBASE_CREDENTIALS or FIREBASE_CREDENTIALS_JSON.\n");
    exit(1);
}

$root = dirname(__DIR__) . '/uploads';
if (!is_dir($root)) {
    fwrite(STDERR, "No uploads directory at $root\n");
    exit(1);
}

echo "bucket : " . storageBucket() . "\n";
echo "source : $root\n";
echo $dryRun ? "mode   : DRY RUN (nothing will be written)\n\n" : "mode   : live\n\n";

$mimes = [
    'jpg' => 'image/jpeg', 'jpeg' => 'image/jpeg',
    'png' => 'image/png',  'webp' => 'image/webp',
    'gif' => 'image/gif',
];

$uploaded = $skipped = $failed = 0;

$it = new RecursiveIteratorIterator(
    new RecursiveDirectoryIterator($root, FilesystemIterator::SKIP_DOTS)
);
foreach ($it as $file) {
    if (!$file->isFile()) continue;

    $object = ltrim(str_replace('\\', '/', substr($file->getPathname(), strlen($root))), '/');

    // .htaccess and .gitkeep are local plumbing; a bucket has neither.
    $base = $file->getFilename();
    if ($base === '.htaccess' || $base === '.gitkeep') continue;

    $ext  = strtolower($file->getExtension());
    $mime = $mimes[$ext] ?? 'application/octet-stream';

    if (!storagePathIsSafe($object)) {
        printf("  REFUSED  %s (unsafe name)\n", $object);
        $failed++;
        continue;
    }

    if (storageExists($object)) {
        printf("  skip     %s (already in bucket)\n", $object);
        $skipped++;
        continue;
    }

    if ($dryRun) {
        printf("  would    %s (%s, %d bytes)\n", $object, $mime, $file->getSize());
        $uploaded++;
        continue;
    }

    // $isUpload = false: these are ordinary files on disk, not PHP upload
    // temp files, so move_uploaded_file's origin check must not apply.
    if (storagePut($file->getPathname(), $object, $mime, false)) {
        printf("  copied   %s (%s, %d bytes)\n", $object, $mime, $file->getSize());
        $uploaded++;
    } else {
        printf("  FAILED   %s\n", $object);
        $failed++;
    }
}

echo "\n" . ($dryRun ? "would copy" : "copied") . ": $uploaded   skipped: $skipped   failed: $failed\n";

// Report rows whose file is missing, so the damage is stated rather than
// silently carried into production.
try {
    $dbh_ = new PDO(
        'mysql:host=' . DB_HOST . ';dbname=' . DB_NAME . ';charset=utf8mb4',
        DB_USER, DB_PASS
    );
    $missing = 0; $total = 0;
    foreach ($dbh_->query('SELECT image FROM items') as $r) {
        if (empty($r['image'])) continue;
        $total++;
        if (!is_file("$root/products/{$r['image']}") && !is_file("$root/{$r['image']}")) $missing++;
    }
    if ($missing) {
        echo "\nWARNING: $missing of $total items reference an image file that does not\n";
        echo "exist on this machine. Those products will show a placeholder.\n";
    }
} catch (Exception $e) {
    // Reporting only — never fail the migration over it.
}

exit($failed === 0 ? 0 : 1);
