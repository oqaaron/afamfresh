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

    public function __construct(int $userId, string $type, string $title, string $body, array $data = []) {
        $this->userId = $userId;
        $this->type = $type;
        $this->title = $title;
        $this->body = $body;
        $this->data = $data;
    }
}