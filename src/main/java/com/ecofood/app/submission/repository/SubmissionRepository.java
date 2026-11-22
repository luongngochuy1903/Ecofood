package com.ecofood.app.submission.repository;

import com.ecofood.app.submission.entity.Submission;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SubmissionRepository extends MongoRepository<Submission, String> {
  Optional<Submission> findByIdAndTenantId(String id, String tenantId);
}
