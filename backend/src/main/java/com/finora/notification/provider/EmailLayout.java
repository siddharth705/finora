package com.finora.notification.provider;

import org.springframework.web.util.HtmlUtils;

/**
 * The one branded HTML shell for the notification-outbox email path (Task: "make the email
 * beautiful", 2026-09-06) -- before this, every email in the product, this path and {@code
 * ResendEmailProvider}'s hand-built ones alike, was bare unstyled {@code <p>} tags. Scoped
 * deliberately to just the two DB-template notification types ({@code IMPORT_STATEMENT_HELD},
 * {@code IMPORT_STATEMENT_READY}) that go through {@link EmailNotificationProvider} -- the
 * account-lifecycle emails in {@code ResendEmailProvider} were not asked to be redesigned, and
 * touching their already-tested copy for an unrelated request is exactly the kind of scope creep
 * worth avoiding.
 *
 * <p>Table-based layout with every style attribute inlined, not a `{@code <style>}` block or
 * external stylesheet -- the only layout approach that renders consistently across Outlook's Word
 * rendering engine, Gmail's stripped `{@code <head>}`, and everything in between. No web fonts
 * either (unreliable in mail clients); the font stack falls through to whatever
 * system UI font each client already has.
 *
 * <p>Colors are the product's actual brand palette (graphite/cream), not invented ones -- see
 * {@code frontend/src/index.css}'s {@code --color-primary}/{@code --color-primary-light} custom
 * properties, the source of truth this hardcodes hex equivalents of (a mail client cannot read a
 * CSS custom property, so there is no way to reference that file directly).
 */
final class EmailLayout {

    private EmailLayout() {}

    private static final String GRAPHITE = "#262A33";
    private static final String CREAM = "#F4F1EC";
    private static final String PAGE_BACKGROUND = "#F8FAFC";
    private static final String BORDER = "#E5E7EB";
    private static final String MUTED_TEXT = "#6B7280";
    private static final String FONT_STACK =
            "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";

    /**
     * Wraps a notification's plain-text title/body in the branded shell.
     *
     * @param heading the notification's own title (e.g. "We're checking your statement") --
     *                rendered large, not repeated as a page title, since a customer's inbox
     *                already shows the subject line once.
     * @param bodyText the notification's own plain-text body -- HTML-escaped before insertion,
     *                 since {@code notification_templates} rows are operator-authored copy, not
     *                 pre-vetted markup, and a stray {@code <}/{@code &} in one must not corrupt
     *                 the layout around it.
     * @param supportSender true when this message is going out from {@code support@} (see {@link
     *                      com.finora.service.EmailMessage.Sender}) -- the footer then invites a
     *                      reply, since one will actually reach a person; a {@code noreply@}
     *                      caller would get a dead-end invitation, so this must never default to
     *                      true silently.
     */
    static String wrap(String heading, String bodyText, boolean supportSender) {
        String safeHeading = HtmlUtils.htmlEscape(heading);
        String safeBody = HtmlUtils.htmlEscape(bodyText).replace("\n", "<br>");
        String footer = supportSender
                ? "Questions? Just reply to this email and it'll reach us."
                // synthetic-ok: Fynora's own published support mailbox, not a customer address
                : "Need help? Email <a href=\"mailto:support@fynora.net\" style=\"color:" // synthetic-ok
                        + GRAPHITE + ";\">support@fynora.net</a>."; // synthetic-ok

        return """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" \
                style="background:%s;padding:32px 16px;font-family:%s;">
                  <tr>
                    <td align="center">
                      <table role="presentation" width="480" cellpadding="0" cellspacing="0" \
                style="max-width:480px;width:100%%;background:#ffffff;border-radius:12px;\
                border:1px solid %s;overflow:hidden;">
                        <tr>
                          <td style="height:4px;background:%s;font-size:0;line-height:0;">&nbsp;</td>
                        </tr>
                        <tr>
                          <td style="padding:20px 32px;background:%s;">
                            <p style="margin:0;font-size:13px;font-weight:700;letter-spacing:0.08em;\
                color:%s;text-transform:uppercase;">FYNORA</p>
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:24px 32px 0 32px;">
                            <h1 style="margin:0 0 16px 0;font-size:20px;line-height:1.3;color:%s;\
                font-weight:600;">%s</h1>
                            <p style="margin:0;font-size:15px;line-height:1.6;color:%s;">%s</p>
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:28px 32px 32px 32px;">
                            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0">
                              <tr><td style="border-top:1px solid %s;padding-top:16px;">
                                <p style="margin:0;font-size:13px;color:%s;">%s</p>
                              </td></tr>
                            </table>
                          </td>
                        </tr>
                      </table>
                    </td>
                  </tr>
                </table>
                """.formatted(
                PAGE_BACKGROUND, FONT_STACK, BORDER,
                GRAPHITE,
                CREAM, GRAPHITE,
                GRAPHITE, safeHeading, GRAPHITE, safeBody,
                BORDER, MUTED_TEXT, footer);
    }
}
