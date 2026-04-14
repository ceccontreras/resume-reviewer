package com.resumecoach.controller;

import com.resumecoach.model.User;
import com.resumecoach.repository.UserRepository;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class StripeController {

    private static final Logger log = LoggerFactory.getLogger(StripeController.class);

    private final UserRepository userRepository;

    @Value("${stripe.secret-key}")
    private String stripeSecretKey;

    @Value("${stripe.price-id}")
    private String stripePriceId;

    @Value("${stripe.webhook-secret:}")
    private String stripeWebhookSecret;

    public StripeController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @PostMapping("/api/stripe/checkout")
    public ResponseEntity<Map<String, String>> createCheckout(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody Map<String, String> body) {

        Stripe.apiKey = stripeSecretKey;

        String clerkId = jwt.getSubject();
        User user = userRepository.findByClerkId(clerkId).orElseGet(() -> {
            User u = new User();
            u.setClerkId(clerkId);
            return userRepository.save(u);
        });

        if (user.getRole() == User.Role.PRO) {
            return ResponseEntity.badRequest().body(Map.of("error", "Already a PRO member"));
        }

        String successUrl = body.getOrDefault("successUrl", "http://localhost:8081/?upgrade=success");
        String cancelUrl  = body.getOrDefault("cancelUrl",  "http://localhost:8081/");

        try {
            SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .addLineItem(SessionCreateParams.LineItem.builder()
                    .setPrice(stripePriceId)
                    .setQuantity(1L)
                    .build())
                .putMetadata("clerkId", clerkId)
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .build();

            Session session = Session.create(params);
            return ResponseEntity.ok(Map.of("url", session.getUrl()));
        } catch (Exception e) {
            log.error("Stripe checkout creation failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to create checkout session"));
        }
    }

    @PostMapping("/api/webhook/stripe")
    public ResponseEntity<String> handleWebhook(
            HttpServletRequest request,
            @RequestHeader("Stripe-Signature") String sigHeader) {

        if (stripeWebhookSecret.isBlank()) {
            log.error("STRIPE_WEBHOOK_SECRET is not configured — refusing webhook");
            return ResponseEntity.status(503).body("Webhook secret not configured");
        }

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (IOException e) {
            log.error("Failed to read webhook body: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Could not read body");
        }

        String payload = new String(rawBody, StandardCharsets.UTF_8);
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, stripeWebhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Invalid Stripe webhook signature: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        if ("checkout.session.completed".equals(event.getType())) {
            event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
                Session session = (Session) obj;
                String clerkId = session.getMetadata() != null ? session.getMetadata().get("clerkId") : null;
                if (clerkId == null) {
                    log.warn("Webhook: checkout.session.completed missing clerkId metadata");
                    return;
                }
                userRepository.findByClerkId(clerkId).ifPresentOrElse(
                    user -> {
                        user.setRole(User.Role.PRO);
                        userRepository.save(user);
                        log.info("Upgraded user {} to PRO", clerkId);
                    },
                    () -> log.warn("Webhook: no user found for clerkId {}", clerkId)
                );
            });
        }

        return ResponseEntity.ok("OK");
    }
}
