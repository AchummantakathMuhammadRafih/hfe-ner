package com.example.demo.repository;

import com.example.demo.model.BatchFileEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface BatchFileRepository extends JpaRepository<BatchFileEntity, Long> {

    Optional<BatchFileEntity> findByRelativePath(String relativePath);

    List<BatchFileEntity> findByStatusOrderByIdAsc(String status, Pageable pageable);

    long countByStatus(String status);

    @Modifying
    @Transactional
    @Query("UPDATE BatchFileEntity b SET b.status = 'PENDING' WHERE b.status = 'PROCESSING'")
    int resetProcessingToPending();
}

