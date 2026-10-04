package com.waste.repository;

import com.waste.entity.CompensationCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompensationCaseRepository extends JpaRepository<CompensationCase, Long> {
    Optional<CompensationCase> findByComplaintId(Long complaintId);
}
