package com.resumecoach.controller;

import com.resumecoach.dto.AuthRequest;
import com.resumecoach.dto.AuthResponse;
import com.resumecoach.model.User;
import com.resumecoach.repository.UserRepository;
import com.resumecoach.security.JwtUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthController(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody AuthRequest request) {
        if (request.email() == null || request.email().isBlank()
                || request.password() == null || request.password().length() < 6) {
            return ResponseEntity.badRequest().body("Email and password (min 6 chars) are required.");
        }

        if (userRepository.existsByEmail(request.email())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Email already registered.");
        }

        User user = new User();
        user.setEmail(request.email().toLowerCase().trim());
        user.setPassword(passwordEncoder.encode(request.password()));
        userRepository.save(user);

        String token = jwtUtil.generateToken(user.getEmail());
        return ResponseEntity.status(HttpStatus.CREATED).body(new AuthResponse(token, user.getEmail()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthRequest request) {
        User user = userRepository.findByEmail(request.email().toLowerCase().trim())
                .orElse(null);

        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid email or password.");
        }

        String token = jwtUtil.generateToken(user.getEmail());
        return ResponseEntity.ok(new AuthResponse(token, user.getEmail()));
    }
}
