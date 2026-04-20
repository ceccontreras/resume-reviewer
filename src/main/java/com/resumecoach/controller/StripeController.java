package com.resumecoach.controller;

import com.resumecoach.model.User;
import com.resumecoach.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
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
import java.util.Optional;

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

        String successUrl = body.getOrDefault("successUrl", "https://www.myresumerate.com/?upgrade=success");
        String cancelUrl  = body.getOrDefault("cancelUrl",  "https://www.myresumerate.com/");

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

    @PostMapping("/api/stripe/portal")
    public ResponseEntity<Map<String, String>> createPortalSession(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody Map<String, String> body) {

        Stripe.apiKey = stripeSecretKey;

        String clerkId = jwt.getSubject();
        User user = userRepository.findByClerkId(clerkId).orElse(null);

        if (user == null || user.getStripeCustomerId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No billing account found"));
        }

        String returnUrl = body.getOrDefault("returnUrl", "https://www.myresumerate.com/");

        try {
            com.stripe.param.billingportal.SessionCreateParams params =
                com.stripe.param.billingportal.SessionCreateParams.builder()
                    .setCustomer(user.getStripeCustomerId())
                    .setReturnUrl(returnUrl)
                    .build();

            com.stripe.model.billingportal.Session portalSession =
                com.stripe.model.billingportal.Session.create(params);
            return ResponseEntity.ok(Map.of("url", portalSession.getUrl()));
        } catch (Exception e) {
            log.error("Stripe portal session creation failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to create portal session"));
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

        ObjectMapper mapper = new ObjectMapper();

        if ("checkout.session.completed".equals(event.getType())) {
            String clerkId = null;
            String stripeCustomerId = null;

            Optional<StripeObject> maybeObj = event.getDataObjectDeserializer().getObject();
            if (maybeObj.isPresent() && maybeObj.get() instanceof Session session) {
                clerkId = session.getMetadata() != null ? session.getMetadata().get("clerkId") : null;
                stripeCustomerId = session.getCustomer();
            } else {
                log.warn("Webhook: Stripe API version mismatch, falling back to raw JSON parsing");
                try {
                    String rawJson = event.getDataObjectDeserializer().getRawJson();
                    JsonNode root = mapper.readTree(rawJson);
                    JsonNode meta = root.path("metadata");
                    if (!meta.isMissingNode() && meta.hasNonNull("clerkId")) {
                        clerkId = meta.get("clerkId").asText();
                    }
                    JsonNode customer = root.path("customer");
                    if (!customer.isMissingNode() && !customer.isNull()) {
                        stripeCustomerId = customer.asText();
                    }
                } catch (Exception e) {
                    log.error("Webhook: failed to parse raw session JSON: {}", e.getMessage());
                }
            }

            if (clerkId == null) {
                log.warn("Webhook: checkout.session.completed missing clerkId metadata");
                return ResponseEntity.ok("OK");
            }

            final String finalClerkId = clerkId;
            final String finalCustomerId = stripeCustomerId;
            userRepository.findByClerkId(finalClerkId).ifPresentOrElse(
                user -> {
                    user.setRole(User.Role.PRO);
                    if (finalCustomerId != null) {
                        user.setStripeCustomerId(finalCustomerId);
                    }
                    userRepository.save(user);
                    log.info("Upgraded user {} to PRO (customerId={})", finalClerkId, finalCustomerId);
                },
                () -> log.warn("Webhook: no user found for clerkId {}", finalClerkId)
            );
        }

        if ("customer.subscription.deleted".equals(event.getType())) {
            String stripeCustomerId = null;

            Optional<StripeObject> maybeObj = event.getDataObjectDeserializer().getObject();
            if (maybeObj.isPresent()) {
                stripeCustomerId = ((com.stripe.model.Subscription) maybeObj.get()).getCustomer();
            } else {
                log.warn("Webhook: Stripe API version mismatch on subscription.deleted, falling back to raw JSON");
                try {
                    String rawJson = event.getDataObjectDeserializer().getRawJson();
                    JsonNode root = mapper.readTree(rawJson);
                    JsonNode customer = root.path("customer");
                    if (!customer.isMissingNode() && !customer.isNull()) {
                        stripeCustomerId = customer.asText();
                    }
                } catch (Exception e) {
                    log.error("Webhook: failed to parse raw subscription JSON: {}", e.getMessage());
                }
            }

            if (stripeCustomerId == null) {
                log.warn("Webhook: customer.subscription.deleted missing customer ID");
                return ResponseEntity.ok("OK");
            }

            final String finalCustomerId = stripeCustomerId;
            userRepository.findByStripeCustomerId(finalCustomerId).ifPresentOrElse(
                user -> {
                    user.setRole(User.Role.FREE);
                    userRepository.save(user);
                    log.info("Downgraded user {} to FREE (customerId={})", user.getClerkId(), finalCustomerId);
                },
                () -> log.warn("Webhook: no user found for stripeCustomerId {}", finalCustomerId)
            );
        }

        return ResponseEntity.ok("OK");
    }
}
