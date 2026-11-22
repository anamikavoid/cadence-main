package com.hrms.backend.repositories;

import com.hrms.backend.models.AiConversation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AiConversationRepository extends MongoRepository<AiConversation, String> {
    Optional<AiConversation> findByUserId(String userId);

    void deleteByUserId(String userId);
}
