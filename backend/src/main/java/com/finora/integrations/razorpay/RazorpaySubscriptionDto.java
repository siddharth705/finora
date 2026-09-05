package com.finora.integrations.razorpay;

/** Minimal projection of Razorpay's Subscription resource — callers never see the SDK's
 *  {@code com.razorpay.Subscription} (a thin wrapper over a raw {@code JSONObject}) directly. */
public record RazorpaySubscriptionDto(String id, String status) {
}
