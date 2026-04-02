package com.resumecoach.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Service
public class GeminiService {

    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=";

    @Value("${gemini.api.key}")
    private String apiKey;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Public methods
    // -------------------------------------------------------------------------

    /**
     * Returns a score (0-100) and a one-sentence reason explaining it.
     */
    public MatchResult getMatchScore(String resumeText, String jobDescription) throws IOException {
        String prompt = """
                You are a resume screening expert.
                Given the resume and job description below, respond with ONLY a valid JSON object — \
                no markdown, no code fences, no extra text.
                Use this exact format:
                {"score": <integer 0-100>, "reason": "<one sentence explaining the score>"}

                Resume:
                %s

                Job Description:
                %s
                """.formatted(resumeText, jobDescription);

        String raw = callGemini(prompt);

        try {
            JsonNode json = objectMapper.readTree(raw);
            double score = json.get("score").asDouble();
            String reason = json.get("reason").asText();
            return new MatchResult(score, reason);
        } catch (Exception e) {
            throw new IOException("Failed to parse Gemini match score response: " + raw, e);
        }
    }

    /**
     * Returns 5 bullet point suggestions to improve the resume for the given job.
     */
    public String getSuggestions(String resumeText, String jobDescription) throws IOException {
        String prompt = """
                You are a career coach. Given the resume and job description below, \
                provide exactly 5 concise bullet point suggestions to improve the resume \
                for this specific job. Start each bullet with "• ".

                Resume:
                %s

                Job Description:
                %s
                """.formatted(resumeText, jobDescription);

        return callGemini(prompt);
    }

    /**
     * Returns a professional cover letter tailored to the resume and job description.
     */
    public String generateCoverLetter(String resumeText, String jobDescription) throws IOException {
        String prompt = """
                You are a professional cover letter writer. \
                Given the resume and job description below, write a compelling, \
                professional cover letter in 3-4 paragraphs. \
                Do not use any placeholder brackets like [Your Name] — \
                infer names and details from the resume where possible.

                Resume:
                %s

                Job Description:
                %s
                """.formatted(resumeText, jobDescription);

        return callGemini(prompt);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String callGemini(String prompt) throws IOException {
        String requestBody = buildRequestBody(prompt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GEMINI_URL + apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Gemini API request interrupted", e);
        }

        if (response.statusCode() != 200) {
            throw new IOException("Gemini API returned " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        return root.path("candidates")
                .get(0)
                .path("content")
                .path("parts")
                .get(0)
                .path("text")
                .asText();
    }

    /**
     * Builds the Gemini request body JSON, safely escaping the prompt via Jackson.
     */
    private String buildRequestBody(String prompt) throws IOException {
        String escapedPrompt = objectMapper.writeValueAsString(prompt);
        return """
                {
                  "contents": [{
                    "parts": [{
                      "text": %s
                    }]
                  }]
                }
                """.formatted(escapedPrompt);
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    public record MatchResult(double score, String reason) {}
}
