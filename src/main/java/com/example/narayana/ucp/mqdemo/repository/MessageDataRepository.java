package com.example.narayana.ucp.mqdemo.repository;

import com.example.narayana.ucp.mqdemo.model.MessageData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for MessageData entities.
 */
@Repository
public interface MessageDataRepository extends JpaRepository<MessageData, Long> {
    
    Optional<MessageData> findByMessageId(String messageId);
    
    boolean existsByMessageId(String messageId);
}
