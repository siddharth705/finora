/**
 * Reversible encryption at rest for secrets Finora must read back — third-party OAuth refresh
 * tokens and, later, any credential-bearing integration (bank APIs, brokers, account aggregators).
 *
 * <p>Deliberately a platform capability rather than a feature-local helper. Gmail sync is simply
 * the first caller (ADR-009); building it inside that integration would guarantee the next one
 * builds its own, differently, which is how a codebase ends up with several incompatible
 * encryption schemes and no clear answer to "where are our secrets".
 *
 * <p><b>Not for passwords or Finora's own session tokens.</b> Anything that only needs to be
 * COMPARED later must be hashed instead — see {@code com.finora.util.TokenHasher}. Reversible
 * encryption where a hash belongs makes a database breach recoverable to the attacker.
 *
 * @see com.finora.security.crypto.EncryptionService
 * @see com.finora.security.crypto.KeyProvider
 */
package com.finora.security.crypto;
