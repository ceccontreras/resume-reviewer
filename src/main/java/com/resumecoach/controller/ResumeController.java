package com.resumecoach.controller;

import com.resumecoach.exception.UsageLimitException;
import com.resumecoach.model.Resume;
import com.resumecoach.model.User;
import com.resumecoach.repository.UserRepository;
import com.resumecoach.service.ResumeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class ResumeController {

    private static final Logger log = LoggerFactory.getLogger(ResumeController.class);
    private static final long COOLDOWN_SECONDS = 30;

    private final ResumeService resumeService;
    private final UserRepository userRepository;
    private final ConcurrentHashMap<String, Instant> lastRequestTime = new ConcurrentHashMap<>();

    public ResumeController(ResumeService resumeService, UserRepository userRepository) {
        this.resumeService = resumeService;
        this.userRepository = userRepository;
    }

    @PostMapping(value = "/analyze", consumes = "multipart/form-data")
    public ResponseEntity<Resume> analyze(
            @RequestParam MultipartFile file,
            @RequestParam String jobDescription,
            @AuthenticationPrincipal Jwt jwt) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        // Validate file type
        String contentType = file.getContentType();
        if (contentType == null || !contentType.equals("application/pdf")) {
            return ResponseEntity.badRequest().build();
        }

        // Validate file size (5MB hard cap in addition to multipart config)
        if (file.getSize() > 5 * 1024 * 1024) {
            return ResponseEntity.badRequest().build();
        }

        // Validate job description length
        if (jobDescription == null || jobDescription.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (jobDescription.length() > 10_000) {
            return ResponseEntity.badRequest().build();
        }

        String clerkId = jwt.getSubject();

        // Rate limit: one analysis per COOLDOWN_SECONDS per user
        Instant now = Instant.now();
        Instant last = lastRequestTime.get(clerkId);
        if (last != null && now.isBefore(last.plusSeconds(COOLDOWN_SECONDS))) {
            return ResponseEntity.status(429).<Resume>build();
        }
        lastRequestTime.put(clerkId, now);

        // Auto-provision user on first request
        User user = userRepository.findByClerkId(clerkId).orElseGet(() -> {
            User newUser = new User();
            newUser.setClerkId(clerkId);
            return userRepository.save(newUser);
        });

        try {
            Resume saved = resumeService.analyze(file, jobDescription, user);
            return ResponseEntity.ok(saved);
        } catch (UsageLimitException e) {
            return ResponseEntity.status(402).body(null);
        } catch (IOException e) {
            log.error("Analysis failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/user/status")
    public ResponseEntity<Map<String, Object>> getUserStatus(@AuthenticationPrincipal Jwt jwt) {
        String clerkId = jwt.getSubject();
        User user = userRepository.findByClerkId(clerkId).orElseGet(() -> {
            User u = new User();
            u.setClerkId(clerkId);
            return userRepository.save(u);
        });

        long used = resumeService.getMonthlyUsageCount(user);
        Map<String, Object> status = new HashMap<>();
        status.put("role", user.getRole().name());
        status.put("analysesUsed", used);
        status.put("monthlyLimit", user.getRole() == User.Role.FREE ? ResumeService.FREE_MONTHLY_LIMIT : null);
        return ResponseEntity.ok(status);
    }

    @GetMapping("/resumes")
    public ResponseEntity<List<Resume>> getUserResumes(@AuthenticationPrincipal Jwt jwt) {
        String clerkId = jwt.getSubject();
        User user = userRepository.findByClerkId(clerkId).orElse(null);
        if (user == null) return ResponseEntity.ok(List.of());
        return ResponseEntity.ok(resumeService.getResumesByUser(user));
    }

    @DeleteMapping("/resumes/{id}")
    public ResponseEntity<Void> deleteResume(@PathVariable @NonNull Long id,
                                             @AuthenticationPrincipal Jwt jwt) {
        String clerkId = jwt.getSubject();
        User user = userRepository.findByClerkId(clerkId).orElse(null);
        if (user == null) return ResponseEntity.status(403).build();
        boolean deleted = resumeService.deleteResumeForUser(id, user);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.status(403).build();
    }
}
