package com.resumecoach.service;

import com.resumecoach.exception.UsageLimitException;
import com.resumecoach.model.Resume;
import com.resumecoach.repository.ResumeRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Service
public class ResumeService {

    private final ResumeRepository resumeRepository;
    private final GeminiService geminiService;

    public ResumeService(ResumeRepository resumeRepository, GeminiService geminiService) {
        this.resumeRepository = resumeRepository;
        this.geminiService = geminiService;
    }

    public static final int FREE_MONTHLY_LIMIT = 3;

    public Resume analyze(MultipartFile file, String jobDescription, com.resumecoach.model.User user) throws IOException {
        if (user.getRole() == com.resumecoach.model.User.Role.FREE) {
            LocalDateTime startOfMonth = LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
            long used = resumeRepository.countByUserAndCreatedAtAfter(user, startOfMonth);
            if (used >= FREE_MONTHLY_LIMIT) {
                throw new UsageLimitException("You've used all " + FREE_MONTHLY_LIMIT + " free analyses this month.");
            }
        }

        String resumeText = extractTextFromPdf(file);

        // Run all three Gemini calls in parallel — cuts wait time ~3x
        CompletableFuture<GeminiService.MatchResult> matchFuture =
            CompletableFuture.supplyAsync(() -> {
                try { return geminiService.getMatchScore(resumeText, jobDescription); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
        CompletableFuture<String> suggestionsFuture =
            CompletableFuture.supplyAsync(() -> {
                try { return geminiService.getSuggestions(resumeText, jobDescription); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
        CompletableFuture<String> coverLetterFuture =
            CompletableFuture.supplyAsync(() -> {
                try { return geminiService.generateCoverLetter(resumeText, jobDescription); }
                catch (IOException e) { throw new RuntimeException(e); }
            });

        GeminiService.MatchResult matchResult;
        String suggestions;
        String coverLetter;
        try {
            matchResult  = matchFuture.get();
            suggestions  = suggestionsFuture.get();
            coverLetter  = coverLetterFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Analysis interrupted", e);
        } catch (ExecutionException e) {
            throw new IOException("Analysis failed: " + e.getCause().getMessage(), e.getCause());
        }

        Resume resume = new Resume();
        resume.setUser(user);
        resume.setResumeText(resumeText);
        resume.setJobDescription(jobDescription);
        resume.setMatchScore(matchResult.score());
        resume.setSuggestions("Match reason: " + matchResult.reason() + "\n\n" + suggestions);
        resume.setCoverLetter(coverLetter);

        return resumeRepository.save(resume);
    }

    public long getMonthlyUsageCount(com.resumecoach.model.User user) {
        LocalDateTime startOfMonth = LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        return resumeRepository.countByUserAndCreatedAtAfter(user, startOfMonth);
    }

    public List<Resume> getAllResumes() {
        return resumeRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<Resume> getResumesByUser(com.resumecoach.model.User user) {
        return resumeRepository.findByUserOrderByCreatedAtDesc(user);
    }

    public Optional<Resume> getResumeById(@NonNull Long id) {
        return resumeRepository.findById(id);
    }

    public void deleteResume(@NonNull Long id) {
        resumeRepository.deleteById(id);
    }

    public boolean deleteResumeForUser(@NonNull Long id, com.resumecoach.model.User user) {
        return resumeRepository.findById(id)
            .filter(r -> r.getUser() != null && r.getUser().getId().equals(user.getId()))
            .map(r -> { resumeRepository.delete(r); return true; })
            .orElse(false);
    }

    private String extractTextFromPdf(MultipartFile file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            return new PDFTextStripper().getText(document);
        }
    }
}
