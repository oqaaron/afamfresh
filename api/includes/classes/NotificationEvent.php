<?php
// includes/classes/NotificationEvent.php

class NotificationEvent {
    public int $userId;
    public string $type;           // e.g., 'order_confirmation', 'promo', 'system'
    public string $title;
    public string $body;
    public array $data = [];       // extra payload (order_id, etc.)
    public ?string $emailTo = null; // optional override
    public ?string $deviceToken = null; // optional override

    /**
     * Channels to attempt beyond the in-app record, which is always written.
     *
     * Any of 'push', 'email', 'sms'. Push by default; the other two only where
     * the caller asks. If every notification became an email, a low-stock sweep
     * across a vendor's catalogue would arrive as a dozen separate messages --
     * the fastest way to train someone to ignore all of them. SMS is narrower
     * still, since it also costs money per message. The user's own preferences
     * apply on top of this and can only narrow it further.
     */
    public array $channels = ['push'];

    public function __construct(
        int $userId,
        string $type,
        string $title,
        string $body,
        array $data = [],
        array $channels = ['push']
    ) {
        $this->userId = $userId;
        $this->type = $type;
        $this->title = $title;
        $this->body = $body;
        $this->data = $data;
        $this->channels = $channels;
    }
}