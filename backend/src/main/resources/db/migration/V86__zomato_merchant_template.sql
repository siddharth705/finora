-- Phase C5.3, second data point for the templating experiment V85 started. Zomato is a food-
-- delivery receipt: one order total, one order date, the same single-total shape Uber's row
-- already proved out -- a clean template fit, unlike this phase's other two merchants (Myntra,
-- Booking.com), which stay hand-written because they each need a positive/negative check a
-- one-marker template cannot express (see MyntraEmailParser, BookingEmailParser doc comments).
INSERT INTO merchant_templates (id, merchant_domain, merchant_name, receipt_marker, amount_pattern, date_pattern, enabled)
VALUES (
    gen_random_uuid(),
    'zomato.com',
    'Zomato',
    'Order Summary',
    'Grand Total: Rs. {amount}',
    'Order Date: {date}',
    true
);
