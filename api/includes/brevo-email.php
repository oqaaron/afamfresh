<?php
/**
 * includes/brevo-email.php
 * Email service using Brevo API v3 - Seamlessly integrated with AfamFresh
 */

// Prevent direct access
if (basename($_SERVER['PHP_SELF']) == 'brevo-email.php') {
    header('HTTP/1.0 403 Forbidden');
    exit('Direct access forbidden.');
}

class BrevoEmailService {
    private $apiKey;
    private $fromEmail;
    private $fromName;
    private $apiUrl = 'https://api.brevo.com/v3/smtp/email';
    private $isConfigured = false;
    private $lastError = null;
    
    /**
     * Constructor - Initialize with credentials from environment
     */
    public function __construct() {
        // Try to load from .env via getenv()
        $this->apiKey = getenv('BREVO_API_KEY');
        $this->fromEmail = getenv('BREVO_FROM_EMAIL');
        $this->fromName = getenv('BREVO_FROM_NAME') ?: 'AfamFresh';
        
        // If not found via getenv, check constants
        if (empty($this->apiKey) && defined('BREVO_API_KEY')) {
            $this->apiKey = BREVO_API_KEY;
        }
        if (empty($this->fromEmail) && defined('BREVO_FROM_EMAIL')) {
            $this->fromEmail = BREVO_FROM_EMAIL;
        }
        if (empty($this->fromName) && defined('BREVO_FROM_NAME')) {
            $this->fromName = BREVO_FROM_NAME;
        }
        
        // Check if configured properly
        if (!empty($this->apiKey) && !empty($this->fromEmail)) {
            $this->isConfigured = true;
        } else {
            error_log("[BrevoEmailService] NOT CONFIGURED - API Key: " . (!empty($this->apiKey) ? 'Set' : 'Missing') . ", From Email: " . (!empty($this->fromEmail) ? 'Set' : 'Missing'));
        }
    }
    
    /**
     * Check if email service is properly configured
     */
    public function isAvailable() {
        return $this->isConfigured;
    }
    
    /**
     * Get last error message
     */
    public function getLastError() {
        return $this->lastError;
    }
    
    /**
     * Send a transactional email using Brevo API
     */
    public function send($to, $subject, $htmlContent, $toName = '') {
        if (!$this->isConfigured) {
            $this->lastError = 'Email service not configured';
            return ['success' => false, 'error' => $this->lastError];
        }
        
        if (empty($to) || empty($subject) || empty($htmlContent)) {
            $this->lastError = 'Missing required parameters';
            return ['success' => false, 'error' => $this->lastError];
        }
        
        // Prepare email data
        $data = [
            'sender' => [
                'name' => $this->fromName,
                'email' => $this->fromEmail
            ],
            'to' => [
                [
                    'email' => $to,
                    'name' => $toName ?: explode('@', $to)[0]
                ]
            ],
            'subject' => $subject,
            'htmlContent' => $this->wrapEmailTemplate($htmlContent),
            'headers' => [
                'X-Mailer' => 'AfamFresh/' . date('Y-m-d'),
                'X-Transaction-Type' => 'transactional'
            ]
        ];
        
        // Initialize cURL
        $ch = curl_init($this->apiUrl);
        curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
        curl_setopt($ch, CURLOPT_POST, true);
        curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($data));
        curl_setopt($ch, CURLOPT_HTTPHEADER, [
            'accept: application/json',
            'api-key: ' . $this->apiKey,
            'content-type: application/json'
        ]);
        curl_setopt($ch, CURLOPT_SSL_VERIFYPEER, false);
        curl_setopt($ch, CURLOPT_TIMEOUT, 30);
        
        $response = curl_exec($ch);
        $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $curlError = curl_error($ch);
        curl_close($ch);
        
        if ($curlError) {
            $this->lastError = 'cURL error: ' . $curlError;
            error_log("[BrevoEmailService] " . $this->lastError);
            return ['success' => false, 'error' => $this->lastError];
        }
        
        if ($httpCode === 201 || $httpCode === 200) {
            $result = json_decode($response, true);
            $messageId = $result['messageId'] ?? 'unknown';
            error_log("[BrevoEmailService] Email sent to: $to - MessageID: $messageId");
            return ['success' => true, 'messageId' => $messageId];
        } else {
            $result = json_decode($response, true);
            $errorMsg = $result['message'] ?? 'HTTP ' . $httpCode;
            $this->lastError = $errorMsg;
            error_log("[BrevoEmailService] API error: $errorMsg");
            return ['success' => false, 'error' => $errorMsg, 'httpCode' => $httpCode];
        }
    }
    
    /**
     * Wrap email content in a consistent template
     */
    private function wrapEmailTemplate($content) {
        return '<!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial, sans-serif; margin: 0; padding: 0; background-color: #f5f5f5; }
                .email-container { max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 12px; overflow: hidden; }
                .email-header { background: linear-gradient(135deg, #1a6b2f 0%, #145724 100%); padding: 30px 20px; text-align: center; }
                .email-header h1 { margin: 0; font-size: 24px; color: #ffffff; }
                .email-header p { margin: 10px 0 0; color: rgba(255,255,255,0.8); }
                .email-body { padding: 30px 25px; }
                .email-footer { background-color: #f8f9fa; padding: 20px; text-align: center; font-size: 12px; color: #666666; border-top: 1px solid #e0e0e0; }
                .email-footer a { color: #1a6b2f; text-decoration: none; }
            </style>
        </head>
        <body style="margin: 0; padding: 20px; background-color: #f5f5f5;">
            <div class="email-container">
                <div class="email-header">
                    <h1>AfamFresh</h1>
                    <p>Fresh Food Delivered to Your Doorstep</p>
                </div>
                <div class="email-body">
                    ' . $content . '
                </div>
                <div class="email-footer">
                    <p>&copy; ' . date('Y') . ' AfamFresh. All rights reserved.</p>
                    <p>Questions? <a href="mailto:support@afam.techaus.online">support@afam.techaus.online</a></p>
                </div>
            </div>
        </body>
        </html>';
    }
    
    /**
     * Send test email (for debugging)
     */
    public function sendTestEmail($to) {
        $content = '
            <h2 style="color: #1a6b2f; margin-top: 0;">✅ Test Email Successful!</h2>
            <p>This is a test email from your AfamFresh installation.</p>
            <p>If you received this, your email configuration is working correctly.</p>
            <p><strong>Time:</strong> ' . date('Y-m-d H:i:s') . '</p>
            <p><strong>API Key:</strong> ' . substr($this->apiKey, 0, 20) . '...</p>
            <p><strong>From Email:</strong> ' . $this->fromEmail . '</p>
        ';
        
        return $this->send($to, 'Test Email - AfamFresh', $content);
    }
    
    /**
     * Send welcome email after signup
     */
    public function sendWelcomeEmail($to, $name) {
        $content = '
            <h2 style="color: #1a6b2f; margin-top: 0;">Welcome to AfamFresh! 🎉</h2>
            <p>Dear ' . htmlspecialchars($name) . ',</p>
            <p>Thank you for joining AfamFresh! We\'re excited to have you as part of our community.</p>
            <p>With your account, you can:</p>
            <ul>
                <li>Order fresh groceries delivered to your doorstep</li>
                <li>Track your orders in real-time</li>
                <li>Earn loyalty points on every purchase</li>
                <li>Get exclusive offers and discounts</li>
            </ul>
            <p>Start shopping today and experience the freshest produce delivered right to your home.</p>
            <hr style="margin: 20px 0;">
            <p style="text-align: center;">
                <a href="https://afam.techaus.online/products.php" style="display: inline-block; background: #1a6b2f; color: #ffffff; padding: 12px 25px; text-decoration: none; border-radius: 25px;">Start Shopping →</a>
            </p>';
        
        return $this->send($to, 'Welcome to AfamFresh!', $content, $name);
    }
    
    /**
     * Send order confirmation email
     */
    public function sendOrderConfirmation($to, $orderId, $total, $items, $customerName = '') {
        // Build items table
        $itemsHtml = '';
        foreach ($items as $item) {
            $itemTotal = $item['quantity'] * $item['price'];
            $itemsHtml .= '<tr>
                <td style="padding: 10px; border-bottom: 1px solid #e0e0e0;">' . htmlspecialchars($item['name']) . '</td>
                <td style="padding: 10px; border-bottom: 1px solid #e0e0e0; text-align: center;">' . $item['quantity'] . '</td>
                <td style="padding: 10px; border-bottom: 1px solid #e0e0e0; text-align: right;">UGX ' . number_format($item['price']) . '</td>
                <td style="padding: 10px; border-bottom: 1px solid #e0e0e0; text-align: right;">UGX ' . number_format($itemTotal) . '</td>
            </tr>';
        }
        
        $content = '
            <h2 style="color: #1a6b2f; margin-top: 0;">Order Confirmed! ✅</h2>
            <p>Dear ' . htmlspecialchars($customerName ?: $to) . ',</p>
            <p>Thank you for your order!</p>
            
            <div style="background: #f8f9fa; padding: 15px; border-radius: 8px; margin: 20px 0;">
                <strong>Order #:</strong> ' . $orderId . '<br>
                <strong>Date:</strong> ' . date('F j, Y, g:i a') . '<br>
                <strong>Status:</strong> Confirmed
            </div>
            
            <table style="width: 100%; border-collapse: collapse; margin: 20px 0;">
                <thead>
                    <tr style="background: #f0fdf4;">
                        <th style="padding: 10px; text-align: left;">Item</th>
                        <th style="padding: 10px; text-align: center;">Qty</th>
                        <th style="padding: 10px; text-align: right;">Price</th>
                        <th style="padding: 10px; text-align: right;">Total</th>
                    </tr>
                </thead>
                <tbody>
                    ' . $itemsHtml . '
                    <tr style="border-top: 2px solid #1a6b2f;">
                        <td colspan="3" style="padding: 15px; text-align: right;"><strong>Total:</strong></td>
                        <td style="padding: 15px; text-align: right;"><strong>UGX ' . number_format($total) . '</strong></td>
                    </tr>
                </tbody>
            </table>
            
            <p>We\'ll notify you when your order is out for delivery.</p>
            <p><a href="https://afam.techaus.online/user-dashboard.php" style="color: #1a6b2f;">Track your order →</a></p>';
        
        return $this->send($to, "Order #$orderId Confirmed - AfamFresh", $content, $customerName);
    }
}

// Global helper function
function getEmailService() {
    static $instance = null;
    if ($instance === null) {
        $instance = new BrevoEmailService();
    }
    return $instance;
}
?>