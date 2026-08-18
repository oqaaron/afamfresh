<?php
/**
 * Pesapal API v3 client.
 *
 * WHY THIS EXISTS
 *
 * api/payment.php previously contained no Pesapal code at all — the integration
 * was a comment ("copy your full Pesapal code here") followed by a hardcoded
 * fake response. `verify` returned {"success":true,"status":"completed"}
 * unconditionally, so any order could be marked paid without money moving.
 *
 * THE ONE RULE THIS FILE ENFORCES
 *
 * Payment status comes from GetTransactionStatus and nowhere else.
 *
 * Pesapal's IPN body carries only OrderTrackingId, OrderMerchantReference and
 * OrderNotificationType — it does NOT contain the payment status. The old
 * handlers read $ipn_data['payment_status'], a key Pesapal never sends, so the
 * update never fired and nothing was ever marked paid. And because those
 * handlers trusted whatever the caller supplied, anyone who POSTed
 * {"order_tracking_id":"…","payment_status":"Completed"} to the public IPN URL
 * could mark an arbitrary order paid for free.
 *
 * So: callers hand this class a tracking id, and it asks Pesapal.
 */

require_once __DIR__ . '/../admin/includes/config.php';

class PesapalException extends Exception {}

class PesapalClient
{
    /** Where the access token is cached between requests. */
    private const TOKEN_CACHE_FILE = 'afamfresh_pesapal_token.json';

    /**
     * Pesapal tokens last 5 minutes. Refresh a little early so a token cannot
     * expire in flight between our check and Pesapal receiving the request.
     */
    private const TOKEN_SAFETY_MARGIN_SECONDS = 60;

    private const HTTP_TIMEOUT_SECONDS = 30;

    /** @var string|null In-process cache, so one request never fetches twice. */
    private static $token = null;
    private static $tokenExpiresAt = 0;

    // ------------------------------------------------------------------
    // Authentication
    // ------------------------------------------------------------------

    /**
     * Returns a valid bearer token, reusing a cached one when possible.
     *
     * Cached on disk as well as in-process: without that, every single API call
     * across every request would burn a token request, and Pesapal rate-limits
     * that endpoint.
     */
    public function getToken(): string
    {
        $now = time();

        if (self::$token !== null && self::$tokenExpiresAt > $now + self::TOKEN_SAFETY_MARGIN_SECONDS) {
            return self::$token;
        }

        $cached = $this->readCachedToken();
        if ($cached !== null) {
            self::$token = $cached['token'];
            self::$tokenExpiresAt = $cached['expires_at'];
            return self::$token;
        }

        if (PESAPAL_KEY === '' || PESAPAL_SECRET === '') {
            throw new PesapalException(
                PESAPAL_IS_SANDBOX
                    ? 'Pesapal sandbox credentials are not set. Provide '
                      . 'PESAPAL_SANDBOX_CONSUMER_KEY and PESAPAL_SANDBOX_CONSUMER_SECRET.'
                    : 'Pesapal live credentials are not set.'
            );
        }

        $response = $this->post(PESAPAL_URL_TOKEN, [
            'consumer_key'    => PESAPAL_KEY,
            'consumer_secret' => PESAPAL_SECRET,
        ], false);

        // Pesapal replies 200 with an error object rather than an HTTP error,
        // so a missing token is the real signal, not the status code.
        if (empty($response['token'])) {
            throw new PesapalException(
                'Pesapal did not return a token: ' . $this->describeError($response)
            );
        }

        self::$token = $response['token'];
        // expiryDate is ISO-8601; fall back to the documented 5 minutes.
        self::$tokenExpiresAt = !empty($response['expiryDate'])
            ? (strtotime($response['expiryDate']) ?: $now + 300)
            : $now + 300;

        $this->writeCachedToken(self::$token, self::$tokenExpiresAt);

        return self::$token;
    }

    private function tokenCachePath(): string
    {
        return rtrim(sys_get_temp_dir(), '/\\') . DIRECTORY_SEPARATOR
            . (PESAPAL_IS_SANDBOX ? 'sandbox_' : 'live_') . self::TOKEN_CACHE_FILE;
    }

    private function readCachedToken(): ?array
    {
        $path = $this->tokenCachePath();
        if (!is_readable($path)) return null;

        $data = json_decode((string)@file_get_contents($path), true);
        if (!is_array($data) || empty($data['token']) || empty($data['expires_at'])) return null;

        if ((int)$data['expires_at'] <= time() + self::TOKEN_SAFETY_MARGIN_SECONDS) return null;

        return ['token' => $data['token'], 'expires_at' => (int)$data['expires_at']];
    }

    private function writeCachedToken(string $token, int $expiresAt): void
    {
        // Not fatal if this fails — we simply fetch a fresh token next time.
        @file_put_contents(
            $this->tokenCachePath(),
            json_encode(['token' => $token, 'expires_at' => $expiresAt]),
            LOCK_EX
        );
        @chmod($this->tokenCachePath(), 0600);
    }

    // ------------------------------------------------------------------
    // IPN registration
    // ------------------------------------------------------------------

    /**
     * Registers the IPN URL and returns the notification id.
     *
     * Run once per environment; the id then goes in PESAPAL_IPN_ID. Re-running
     * creates a duplicate registration rather than failing, so do not call this
     * on every request.
     */
    public function registerIpn(?string $url = null): array
    {
        $response = $this->post(PESAPAL_URL_REGISTER_IPN, [
            'url' => $url ?: PESAPAL_IPN_NOTIFICATION_URL,
            'ipn_notification_type' => 'POST',
        ]);

        if (empty($response['ipn_id'])) {
            throw new PesapalException('IPN registration failed: ' . $this->describeError($response));
        }

        return $response;
    }

    // ------------------------------------------------------------------
    // Submitting an order
    // ------------------------------------------------------------------

    /**
     * Submits an order and returns Pesapal's response, which carries
     * order_tracking_id and redirect_url.
     *
     * @param string $merchantReference Our own order id. Must be unique per
     *        attempt from Pesapal's perspective; reusing one for a genuinely new
     *        payment can return the earlier transaction.
     * @param float  $amount            Computed server-side by the caller. Never
     *        take this from the client — see api/payment.php.
     */
    public function submitOrder(
        string $merchantReference,
        float $amount,
        string $description,
        array $billing,
        ?string $callbackUrl = null
    ): array {
        if ($amount <= 0) {
            throw new PesapalException('Refusing to submit a non-positive amount.');
        }
        if (PESAPAL_IPN_ID === '') {
            throw new PesapalException(
                'PESAPAL_IPN_ID is empty. Register an IPN URL for the '
                . PESAPAL_ENV . ' environment first (PesapalClient::registerIpn).'
            );
        }

        $payload = [
            'id'              => $merchantReference,
            // Pesapal rejects fractional minor units on some channels, and UGX
            // has no subunit in practice, so send whole shillings.
            'currency'        => CURRENCY,
            'amount'          => round($amount, 2),
            'description'     => $this->truncate($description, 100),
            'callback_url'    => $callbackUrl ?: PESAPAL_CALLBACK_URL,
            'notification_id' => PESAPAL_IPN_ID,
            'billing_address' => $this->buildBillingAddress($billing),
        ];

        $response = $this->post(PESAPAL_URL_SUBMIT_ORDER, $payload);

        if (empty($response['order_tracking_id']) || empty($response['redirect_url'])) {
            throw new PesapalException(
                'SubmitOrderRequest did not return a tracking id and redirect URL: '
                . $this->describeError($response)
            );
        }

        return $response;
    }

    /**
     * Pesapal requires at least one contact field and validates the phone
     * shape. Uganda numbers are normalised to 2547.../2567... style E.164
     * without the +, which is what the mobile-money channels expect.
     */
    private function buildBillingAddress(array $billing): array
    {
        $email = trim($billing['email'] ?? '');
        $phone = $this->normalisePhone($billing['phone'] ?? '');

        if ($email === '' && $phone === '') {
            throw new PesapalException('Pesapal needs either an email address or a phone number.');
        }

        return [
            'email_address' => $email,
            'phone_number'  => $phone,
            'country_code'  => 'UG',
            'first_name'    => $this->truncate(trim($billing['first_name'] ?? ''), 50),
            'middle_name'   => '',
            'last_name'     => $this->truncate(trim($billing['last_name'] ?? ''), 50),
            'line_1'        => $this->truncate(trim($billing['address'] ?? ''), 100),
            'line_2'        => '',
            'city'          => $this->truncate(trim($billing['city'] ?? ''), 50),
            'state'         => '',
            'postal_code'   => '',
            'zip_code'      => '',
        ];
    }

    /**
     * "0785463210" and "+256785463210" and "256785463210" are all the same
     * number; Pesapal only accepts the last form.
     */
    public function normalisePhone(string $phone): string
    {
        $digits = preg_replace('/\D+/', '', $phone);
        if ($digits === '') return '';

        // Local format: drop the leading 0 and prefix the country code.
        if (strlen($digits) === 10 && $digits[0] === '0') {
            return '256' . substr($digits, 1);
        }
        // Already country-coded.
        if (strpos($digits, '256') === 0) {
            return $digits;
        }
        // 9 digits, no trunk prefix.
        if (strlen($digits) === 9) {
            return '256' . $digits;
        }

        return $digits;
    }

    // ------------------------------------------------------------------
    // Reading the real status
    // ------------------------------------------------------------------

    /**
     * THE authoritative status check. Everything that decides whether an order
     * is paid must go through here.
     *
     * Pesapal's status_code is numeric:
     *   0 INVALID   1 COMPLETED   2 FAILED   3 REVERSED
     */
    public function getTransactionStatus(string $orderTrackingId): array
    {
        if (trim($orderTrackingId) === '') {
            throw new PesapalException('An order tracking id is required.');
        }

        $url = PESAPAL_URL_GET_STATUS . '?orderTrackingId=' . urlencode($orderTrackingId);
        $response = $this->get($url);

        if (!isset($response['status_code']) && !isset($response['payment_status_description'])) {
            throw new PesapalException(
                'GetTransactionStatus returned an unrecognised body: ' . $this->describeError($response)
            );
        }

        return $response;
    }

    /**
     * Maps a GetTransactionStatus response onto the values stored in
     * orders.payment_status.
     *
     * Anything not explicitly COMPLETED is treated as not-paid. An unfamiliar
     * status must never fall through to 'paid'.
     */
    public function mapStatus(array $status): string
    {
        $code = isset($status['status_code']) ? (int)$status['status_code'] : -1;
        $description = strtolower(trim((string)($status['payment_status_description'] ?? '')));

        if ($code === 1 || $description === 'completed') return 'paid';
        if ($code === 2 || $description === 'failed')    return 'failed';
        if ($code === 3 || $description === 'reversed')  return 'reversed';
        if ($code === 0 || $description === 'invalid')   return 'invalid';

        return 'pending';
    }

    /**
     * Does the money Pesapal reports actually match what the order costs?
     *
     * Returns null when there is no objection, or a human-readable reason when
     * there is one.
     *
     * Deliberately fails OPEN on a missing or unusable amount. Pesapal is not
     * contractually obliged to return this field on every channel, and refusing
     * to settle whenever it is absent would strand real payments -- customers
     * charged, orders never confirmed. The job here is to catch a figure that
     * is present and wrong, not to insist one exists.
     */
    public function amountObjection(array $status, float $expected): ?string
    {
        if (!isset($status['amount']) || !is_numeric($status['amount'])) {
            return null;
        }

        $paid = round((float)$status['amount'], 2);
        $want = round($expected, 2);

        if ($paid <= 0 || $want <= 0) {
            return null;
        }

        // Tolerance, not equality: the amount makes a float round-trip through
        // JSON on the way out and back, and an exact === would occasionally
        // reject a correct payment for a rounding artefact.
        if (abs($paid - $want) > 0.01) {
            return sprintf('amount mismatch — Pesapal reports %.2f, order total is %.2f', $paid, $want);
        }

        $expectedCurrency = defined('CURRENCY') ? CURRENCY : 'UGX';
        $currency = strtoupper(trim((string)($status['currency'] ?? '')));
        if ($currency !== '' && $currency !== $expectedCurrency) {
            return sprintf('currency mismatch — Pesapal reports %s, expected %s', $currency, $expectedCurrency);
        }

        return null;
    }

    /**
     * mapStatus(), plus the question mapStatus cannot answer: was the right
     * amount paid?
     *
     * mapStatus() reads only status_code/payment_status_description, so an
     * order settled for a fraction of its total still came back 'paid' and was
     * written to the database as paid in full. Nothing compared the figure in
     * the very same response against what the order actually costs.
     *
     * On an objection this returns 'pending' rather than 'failed'. 'pending' is
     * the one value every caller already treats as "leave the row alone": the
     * order does not auto-complete, and the customer is not told the payment
     * failed -- which would invite them to pay a second time for something that
     * may well have gone through. It lands in the log for a human instead.
     *
     * Anything that settles an order must call this, not mapStatus().
     */
    public function mapStatusForOrder(array $status, float $expected, string $context): string
    {
        $mapped = $this->mapStatus($status);
        if ($mapped !== 'paid') {
            return $mapped;
        }

        $objection = $this->amountObjection($status, $expected);
        if ($objection === null) {
            return 'paid';
        }

        error_log("Pesapal settlement REFUSED for $context: $objection. Needs manual review.");
        return 'pending';
    }

    // ------------------------------------------------------------------
    // Refunds
    // ------------------------------------------------------------------

    /**
     * Requests a refund against an already-COMPLETED payment.
     *
     * $confirmationCode comes from GetTransactionStatus, not from anything
     * stored locally -- this codebase never stores it, so callers fetch it
     * fresh immediately before calling this.
     *
     * Pesapal rules (confirmed against their v3 docs): only a COMPLETED
     * payment can be refunded, a mobile-money payment can only be refunded
     * IN FULL (this app has no card channel), and only one refund request is
     * allowed per payment -- a second attempt is rejected by Pesapal, not by
     * this method.
     *
     * The response carries the real result in its body even on HTTP 200
     * ("status": "200" success / "500" rejected) -- the same
     * body-carries-the-error shape every other Pesapal endpoint in this file
     * already has to handle.
     */
    public function submitRefundRequest(
        string $confirmationCode, float $amount, string $remarks, string $username
    ): array {
        if ($confirmationCode === '') {
            throw new PesapalException('No confirmation code to refund against.');
        }
        if ($amount <= 0) {
            throw new PesapalException('Refusing to refund a non-positive amount.');
        }

        $response = $this->post(PESAPAL_URL_REFUND, [
            'confirmation_code' => $confirmationCode,
            'amount'            => round($amount, 2),
            'username'          => $this->truncate($username, 100),
            'remarks'           => $this->truncate($remarks, 250),
        ]);

        if ((string)($response['status'] ?? '') !== '200') {
            throw new PesapalException('Pesapal rejected the refund: ' . $this->describeError($response));
        }

        return $response;
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private function post(string $url, array $body, bool $withAuth = true): array
    {
        return $this->request('POST', $url, $body, $withAuth);
    }

    private function get(string $url): array
    {
        return $this->request('GET', $url, null, true);
    }

    private function request(string $method, string $url, ?array $body, bool $withAuth): array
    {
        $headers = ['Accept: application/json', 'Content-Type: application/json'];
        if ($withAuth) {
            $headers[] = 'Authorization: Bearer ' . $this->getToken();
        }

        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT        => self::HTTP_TIMEOUT_SECONDS,
            CURLOPT_CONNECTTIMEOUT => 15,
            CURLOPT_HTTPHEADER     => $headers,
            // Certificate verification stays ON. Disabling it on a payments
            // integration would allow a man in the middle to rewrite amounts.
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
        ]);

        if ($method === 'POST') {
            curl_setopt($ch, CURLOPT_POST, true);
            curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($body ?? []));
        }

        $raw = curl_exec($ch);
        $httpCode = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $curlError = curl_error($ch);
        curl_close($ch);

        if ($raw === false) {
            throw new PesapalException("Could not reach Pesapal ($url): $curlError");
        }

        $decoded = json_decode($raw, true);
        if (!is_array($decoded)) {
            // Log the body but do not surface it — it can contain an error page.
            error_log("Pesapal non-JSON response from $url (HTTP $httpCode): "
                . substr((string)$raw, 0, 500));
            throw new PesapalException("Pesapal returned a non-JSON response (HTTP $httpCode).");
        }

        if ($httpCode >= 400) {
            throw new PesapalException(
                "Pesapal returned HTTP $httpCode: " . $this->describeError($decoded)
            );
        }

        return $decoded;
    }

    /**
     * Pesapal reports failures in an `error` object, sometimes alongside HTTP
     * 200. Flattens it into something loggable without leaking credentials.
     */
    private function describeError(array $response): string
    {
        if (!empty($response['error'])) {
            $err = $response['error'];
            if (is_array($err)) {
                $parts = array_filter([
                    $err['code'] ?? null,
                    $err['error_type'] ?? null,
                    $err['message'] ?? null,
                ]);
                if ($parts) return implode(' / ', $parts);
            }
            if (is_string($err) && $err !== '') return $err;
        }

        if (!empty($response['message'])) return (string)$response['message'];

        $copy = $response;
        unset($copy['consumer_key'], $copy['consumer_secret'], $copy['token']);
        return substr(json_encode($copy), 0, 300);
    }

    private function truncate(string $value, int $max): string
    {
        return function_exists('mb_substr') ? mb_substr($value, 0, $max) : substr($value, 0, $max);
    }
}
