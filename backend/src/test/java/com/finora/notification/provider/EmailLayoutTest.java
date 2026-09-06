package com.finora.notification.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailLayoutTest {

    private static final String SUPPORT_ADDRESS = "help@example.test";

    @Test
    void wrap_includesTheHeadingAndBody() {
        String html = EmailLayout.wrap("Statement under review",
                "We will notify you once it is ready.", true, SUPPORT_ADDRESS);

        assertThat(html).contains("Statement under review");
        assertThat(html).contains("We will notify you once it is ready.");
    }

    /** The brand wordmark this shell exists to add -- the whole point of the task ("this one line
     *  is looking very bad") was that nothing distinguished the email as Fynora's at all. */
    @Test
    void wrap_carriesTheBrandWordmark() {
        String html = EmailLayout.wrap("Title", "Body", true, SUPPORT_ADDRESS);

        assertThat(html).contains("FYNORA");
    }

    @Test
    void wrap_invitesAReplyWhenSentFromSupport() {
        String html = EmailLayout.wrap("Title", "Body", true, SUPPORT_ADDRESS);

        assertThat(html).contains("reply to this email");
        assertThat(html).doesNotContain(SUPPORT_ADDRESS);
    }

    /**
     * Must never assume support@ silently -- a noreply@ caller inviting a reply would be a
     * dead-end promise. Uses a distinctive, non-default address deliberately: this must be
     * the value the CALLER passed in, not a hardcoded literal happening to match the usual
     * default -- found in review, this class used to hardcode "support@fynora.net" directly, // synthetic-ok: describing a past bug, not customer data
     * which would have silently kept passing this exact test even if the real configured
     * address (EmailProperties.getSupportFromAddress()) had been changed to something else.
     */
    @Test
    void wrap_pointsToTheGivenSupportAddressWhenNotSentFromSupport() {
        String html = EmailLayout.wrap("Title", "Body", false, SUPPORT_ADDRESS);

        assertThat(html).contains(SUPPORT_ADDRESS);
        assertThat(html).contains("mailto:" + SUPPORT_ADDRESS);
        assertThat(html).doesNotContain("reply to this email");
    }

    /** notification_templates rows are operator-authored plain sentences, not pre-vetted markup --
     *  a stray angle bracket must not break out of the layout around it. */
    @Test
    void wrap_escapesHtmlInTheHeadingAndBody() {
        String html = EmailLayout.wrap("<script>alert(1)</script>",
                "5 > 3 & <b>bold</b> isn't real markup here", true, SUPPORT_ADDRESS);

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("&amp;");
        assertThat(html).contains("&lt;b&gt;bold&lt;/b&gt;");
    }

    @Test
    void wrap_turnsNewlinesIntoLineBreaksInTheBody() {
        String html = EmailLayout.wrap("Title", "Line one\nLine two", true, SUPPORT_ADDRESS);

        assertThat(html).contains("Line one<br>Line two");
    }
}
