package com.finora.integrations.setu;

/** Which kind of financial information this link covers. See the design spec's "Scope" section --
 *  CREDIT_CARD coverage across issuers is unverified and gated behind its own sandbox-validation
 *  task before it ships; DEPOSIT (savings/current) is the safer first path. */
public enum FiType { DEPOSIT, CREDIT_CARD }
