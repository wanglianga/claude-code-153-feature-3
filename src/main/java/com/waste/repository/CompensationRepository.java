package com.waste.repository;

import com.waste.entity.Compensation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompensationRepository extends JpaRepository<Compensation, Long> {
    Optional<Compensation> findByComplaintId(Long complaintId);
    List<Compensation> findByRegistrationIdOrderByIdDesc(Long registrationId);
    List<Compensation> findByCompanyNameOrderByIdDesc(String companyName);
    boolean existsByTopUpBillId(Long topUpBillId);
}
