package com.moveinsync.config.repository;

import com.moveinsync.config.domain.ChangeSet;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChangeSetRepository extends JpaRepository<ChangeSet, Long> {

    Optional<ChangeSet> findFirstByOrderByIdDesc();

    List<ChangeSet> findAllByOrderByIdDesc(Pageable pageable);
}
