package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * An admin-added bank, additive to the built-in com.finora.util.BankRegistry (see
 * V26__custom_banks.sql for why that static registry isn't being replaced). Deliberately a much
 * smaller field set than BankRegistry.BankInfo -- no supportedStatementFormats/swiftCode/logoPath,
 * since every custom bank behaves the same way on those axes today (CSV-only, no verified SWIFT
 * code, no bundled logo asset -- see BankManagementService.toDto for how these are filled in).
 */
@Entity
@Table(name = "banks")
public class Bank {

    @Id
    private String id;

    @Column(name = "official_name", nullable = false)
    private String officialName;

    @Column(name = "short_name", nullable = false)
    private String shortName;

    @Column(name = "color_hex", nullable = false)
    private String colorHex = "#64748B";

    @Column(nullable = false)
    private String initials = "";

    // Free-text mirror of BankRegistry.Category's enum names (PUBLIC_SECTOR/PRIVATE/
    // SMALL_FINANCE/FOREIGN) -- not a real @Enumerated column, since an admin-entered value has
    // no compile-time guarantee of matching that enum. BankManagementService validates it against
    // the enum at write time instead (see createCustom/updateCustom), so bad values never reach
    // this column, without coupling the schema to a Java enum's exact name set.
    private String category;

    @Column(name = "website_url")
    private String websiteUrl;

    @Column(name = "ifsc_prefix", length = 4)
    private String ifscPrefix;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOfficialName() { return officialName; }
    public void setOfficialName(String officialName) { this.officialName = officialName; }
    public String getShortName() { return shortName; }
    public void setShortName(String shortName) { this.shortName = shortName; }
    public String getColorHex() { return colorHex; }
    public void setColorHex(String colorHex) { this.colorHex = colorHex; }
    public String getInitials() { return initials; }
    public void setInitials(String initials) { this.initials = initials; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getWebsiteUrl() { return websiteUrl; }
    public void setWebsiteUrl(String websiteUrl) { this.websiteUrl = websiteUrl; }
    public String getIfscPrefix() { return ifscPrefix; }
    public void setIfscPrefix(String ifscPrefix) { this.ifscPrefix = ifscPrefix; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
