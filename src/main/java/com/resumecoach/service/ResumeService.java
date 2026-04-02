package com.resumecoach.service;

import com.resumecoach.model.Resume;
import com.resumecoach.repository.ResumeRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Service
public class ResumeService {

    private final ResumeRepository resumeRepository;
    private final GeminiService geminiService;

    public ResumeService(ResumeRepository resumeRepository, GeminiService geminiService) {
        this.resumeRepository = resumeRepository;
        this.geminiService = geminiService;
    }

    public Resume analyze(MultipartFile file, String jobDescription) throws IOException {
        String resumeText = extractTextFromPdf(file);

        GeminiService.MatchResult matchResult = geminiService.getMatchScore(resumeText, jobDescription);
        String suggestions = geminiService.getSuggestions(resumeText, jobDescription);
        String coverLetter = geminiService.generateCoverLetter(resumeText, jobDescription);

        Resume resume = new Resume();
        resume.setResumeText(resumeText);
        resume.setJobDescription(jobDescription);
        resume.setMatchScore(matchResult.score());
        resume.setSuggestions("Match reason: " + matchResult.reason() + "\n\n" + suggestions);
        resume.setCoverLetter(coverLetter);

        return resumeRepository.save(resume);
    }

    public List<Resume> getAllResumes() {
        return resumeRepository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<Resume> getResumeById(@NonNull Long id) {
        return resumeRepository.findById(id);
    }

    public void deleteResume(@NonNull Long id) {
        resumeRepository.deleteById(id);
    }

    private String extractTextFromPdf(MultipartFile file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            return new PDFTextStripper().getText(document);
        }
    }
}
