package com.resumecoach.repository;

import com.resumecoach.model.Resume;
import com.resumecoach.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ResumeRepository extends JpaRepository<Resume, Long> {

    List<Resume> findAllByOrderByCreatedAtDesc();

    List<Resume> findByUserOrderByCreatedAtDesc(User user);

    long countByUserAndCreatedAtAfter(User user, LocalDateTime after);
}
