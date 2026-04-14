package com.resumecoach.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String clerkId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.FREE;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public enum Role { FREE, PRO }

    public Long getId() { return id; }

    public String getClerkId() { return clerkId; }
    public void setClerkId(String clerkId) { this.clerkId = clerkId; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
