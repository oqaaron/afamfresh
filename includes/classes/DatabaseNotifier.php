<?php
// includes/classes/DatabaseNotifier.php

class DatabaseNotifier {
    private PDO $dbh;

    public function __construct() {
        global $dbh; // your PDO connection from config.php
        $this->dbh = $dbh;
    }

    /**
     * Insert a notification record into the user_notifications table.
     * Returns the new ID.
     */
    public function store(NotificationEvent $event): int {
        $sql = "INSERT INTO user_notifications 
                (user_id, title, message, type, link, created_at) 
                VALUES (:user_id, :title, :message, :type, :link, NOW())";
        
        $stmt = $this->dbh->prepare($sql);
        $stmt->execute([
            ':user_id' => $event->userId,
            ':title' => $event->title,
            ':message' => $event->body,
            ':type' => $event->type,
            ':link' => $event->data['link'] ?? null,
        ]);

        return (int) $this->dbh->lastInsertId();
    }
}