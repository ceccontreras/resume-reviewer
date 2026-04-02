package com.resumecoach.controller;

import com.resumecoach.model.Resume;
import com.resumecoach.service.ResumeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ResumeController {

    private static final Logger log = LoggerFactory.getLogger(ResumeController.class);

    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    /**
     * POST /api/analyze
     *
     * Accepts a multipart PDF resume and a job description string.
     * Extracts the resume text, computes a match score, generates suggestions
     * and a cover letter, persists the result, and returns the saved entity.
     *
     * Form params:
     *   file           — the PDF resume (multipart/form-data)
     *   jobDescription — plain-text job description
     */
    @PostMapping(value = "/analyze", consumes = "multipart/form-data")
    public ResponseEntity<Resume> analyze(
            @RequestParam MultipartFile file,
            @RequestParam String jobDescription) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            Resume saved = resumeService.analyze(file, jobDescription);
            return ResponseEntity.ok(saved);
        } catch (IOException e) {
            log.error("Analysis failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/resumes")
    public ResponseEntity<List<Resume>> getAllResumes() {
        return ResponseEntity.ok(resumeService.getAllResumes());
    }

    @GetMapping("/resumes/{id}")
    public ResponseEntity<Resume> getResumeById(@PathVariable @NonNull Long id) {
        return resumeService.getResumeById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/resumes/{id}")
    public ResponseEntity<Void> deleteResume(@PathVariable @NonNull Long id) {
        resumeService.deleteResume(id);
        return ResponseEntity.noContent().build();
    }
}
