-- Corrects V103's dominos.co.in row from an untested guess to a pattern verified against two real
-- "Order Successful" emails (different orders, different dates and amounts, same structural shape).
--
-- What was wrong with the original guess:
--   receipt_marker = 'Order Placed'              -- real subject/body text is "Order Confirmed",
--                                                    never "Order Placed"
--   amount_pattern = 'Order Total: Rs. {amount}'  -- real emails have no space around the amount
--                                                    ("Order TotalRs.1,288.00" -- the sanitizer
--                                                    unwraps the <span> tags Domino's uses here
--                                                    with no separating space) and no colon
--   date_pattern   = 'Order Date: {date}'         -- there is no "Order Date:" label anywhere; the
--                                                    date sits between two pipe characters in the
--                                                    order-number/date/time header line, and it is
--                                                    day-first with hyphens ("12-07-2026"), a shape
--                                                    MerchantTemplate.DATE_CAPTURE did not support
--                                                    at all until this same change adds it (see
--                                                    MerchantTemplate.java / ReceiptDateFormats.java)
--
-- The corrected pattern below uses the "Grand Total" field (the td/b-wrapped final payable amount,
-- which the sanitizer's tag-to-space conversion renders with clean, predictable spacing, unlike the
-- span-wrapped "Order Total" figure) and a pipe-anchored date pattern that does not hardcode the
-- order number, which varies on every order.
--
-- Still enabled = false. Being pattern-verified against two real samples is not the same thing as
-- being activated -- that is a separate, audited admin action (POST /{id}/activate), left to happen
-- through the real admin workflow rather than silently flipped here, per V103's own reasoning for
-- keeping "trusted" and "enabled" as two deliberately separate decisions.
UPDATE merchant_templates
SET receipt_marker = 'Order Confirmed',
    amount_pattern = 'Grand Total : Rs.{amount}',
    date_pattern   = '|{date}|',
    updated_at     = now()
WHERE merchant_domain = 'dominos.co.in';
