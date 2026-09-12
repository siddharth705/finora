package com.finora.integrations.setu;

/** What Setu reports about a linked FI account once consent is approved -- enough to build a
 *  ProductIdentity and run it through the same resolution ProductIdentityResolver already applies
 *  to manual re-import. fullAccountNumber is nullable: whether Setu's consent-detail response ever
 *  carries it (as opposed to only the masked form) is unverified against a real sandbox -- see the
 *  design spec's open items. Callers must handle null. */
public record SetuConsentDetail(String fipId, String ifscCode, String maskedAccountNumber,
                                 String fullAccountNumber, String accountHolderName) {}
