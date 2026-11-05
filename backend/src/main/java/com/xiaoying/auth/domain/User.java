package com.xiaoying.auth.domain;

import java.time.Instant;

/** {@code users} 表行。{@code passwordHash} 永远不进入 DTO（architecture §3.1）。 */
public class User {

    private Long id;
    private String username;
    private String email;
    private String passwordHash;
    private Instant createdAt;

    public User() {
    }

    public User(String username, String email, String passwordHash, Instant createdAt) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
