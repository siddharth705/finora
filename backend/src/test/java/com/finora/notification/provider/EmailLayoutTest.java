package com.finora.notification.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailLayoutTest {

    @Test
    void wrap_includesTheHeadingAndBody() {
        String html = EmailLayout.wrap("Statement under review",
                "We will notify you once it is ready.", true);

        assertThat(html).contains("Statement under review");
        assertThat(html).contains("We will notify you once it is ready.");
    }

    /** The brand wordmark this shell exists to add -- the whole point of the task ("this one line
     *  is looking very bad") was that nothing distinguished the email as Fynora's at all. */
    @Test
    void wrap_carriesTheBrandWordmark() {
        String html = EmailLayout.wrap("Title", "Body", true);

        assertThat(html).contains("FYNORA");
    }

    @Test
    void wrap_invitesAReplyWhenSentFromSupport() {
        String html = EmailLayout.wrap("Title", "Body", true);

        assertThat(html).contains("reply to this email");
        assertThat(html).doesNotContain("support@fynora.net"); // synthetic-ok: asserting absence
    }

    /** Must never assume support@ silently -- a noreply@ caller inviting a reply would be a
     *  dead-end promise. */
    @Test
    void wrap_pointsToSupportEmailWhenNotSentFromSupport() {
        String html = EmailLayout.wrap("Title", "Body", false);

        assertThat(html).contains("support@fynora.net"); // synthetic-ok: Fynora's own mailbox
        assertThat(html).doesNotContain("reply to this email");
    }

    /** notification_templates rows are operator-authored plain sentences, not pre-vetted markup --
     *  a stray angle bracket must not break out of the layout around it. */
    @Test
    void wrap_escapesHtmlInTheHeadingAndBody() {
        String html = EmailLayout.wrap("<script>alert(1)</script>",
                "5 > 3 & <b>bold</b> isn't real markup here", true);

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("&amp;");
        assertThat(html).contains("&lt;b&gt;bold&lt;/b&gt;");
    }

    @Test
    void wrap_turnsNewlinesIntoLineBreaksInTheBody() {
        String html = EmailLayout.wrap("Title", "Line one\nLine two", true);

        assertThat(html).contains("Line one<br>Line two");
    }
}
