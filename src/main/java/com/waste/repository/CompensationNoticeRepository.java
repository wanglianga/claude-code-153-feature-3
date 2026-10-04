package com.waste.repository;

import com.waste.entity.CompensationNotice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompensationNoticeRepository extends JpaRepository<CompensationNotice, Long> {
    List<CompensationNotice> findByCompensationIdOrderByIdAsc(Long compensationId);
    List<CompensationNotice> findByCompensationIdAndAudience(Long compensationId, String audience);
}
