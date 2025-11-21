package com.example.narayana.ucp.mqdemo.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity representing a message stored in the database.
 */
@Entity
@Table(name = "MESSAGE_DATA")
public class MessageData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Long id;

    @Column(name = "MESSAGE_ID", unique = true, nullable = false, length = 255)
    private String messageId;

    @Column(name = "MESSAGE_CONTENT", length = 4000)
    private String messageContent;

    @Column(name = "STATUS", length = 50)
    private String status;

    @Column(name = "CREATED_AT")
    private Instant createdAt;

    public MessageData() {
    }

    public MessageData(String messageId, String messageContent, String status) {
        this.messageId = messageId;
        this.messageContent = messageContent;
        this.status = status;
        this.createdAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // Getters and setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getMessageContent() {
        return messageContent;
    }

    public void setMessageContent(String messageContent) {
        this.messageContent = messageContent;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "MessageData{" +
                "id=" + id +
                ", messageId='" + messageId + '\'' +
                ", messageContent='" + messageContent + '\'' +
                ", status='" + status + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
