package com.finora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    private String secret;
    private long expirationMs;
    private long refreshExpirationMs;
    private long idleTimeoutMs;
    private long absoluteSessionMs;

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    public long getExpirationMs() { return expirationMs; }
    public void setExpirationMs(long expirationMs) { this.expirationMs = expirationMs; }

    public long getRefreshExpirationMs() { return refreshExpirationMs; }

    public long getIdleTimeoutMs() { return idleTimeoutMs; }
    public void setIdleTimeoutMs(long idleTimeoutMs) { this.idleTimeoutMs = idleTimeoutMs; }

    public long getAbsoluteSessionMs() { return absoluteSessionMs; }
    public void setAbsoluteSessionMs(long absoluteSessionMs) { this.absoluteSessionMs = absoluteSessionMs; }
    public void setRefreshExpirationMs(long refreshExpirationMs) { this.refreshExpirationMs = refreshExpirationMs; }
}
