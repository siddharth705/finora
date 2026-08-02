package com.finora.service;

/** filename + raw bytes -- EmailProvider implementations handle whatever transport encoding
 *  (e.g. base64) the real API needs. */
public record EmailAttachment(String filename, byte[] content, String contentType) {}
