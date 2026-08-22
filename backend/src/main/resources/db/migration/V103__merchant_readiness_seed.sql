-- Readiness seed: 50 more merchants, trusted and scaffolded but NOT live -- requested to widen
-- Gmail receipt-sync coverage beyond the original 6 (Amazon, Myntra, Uber, Ola, Zomato,
-- Booking.com) ahead of the admin Merchant Templates UI (#235/#240) actually being used to grow
-- coverage one verified merchant at a time.
--
-- WHAT THIS IS, AND IS NOT
-- ------------------------
-- Every amount_pattern/date_pattern/receipt_marker below is a BEST-GUESS based on common Indian
-- e-commerce/service receipt conventions ("Order Total: Rs. {amount}", "Order Date: {date}") --
-- NOT extracted from a real sample email the way V85's Uber row was (see that migration's own
-- comment: "seeded from a real Uber trip-receipt shape"). No real receipt from any of these 50
-- senders was read to write these patterns. Several rows share near-identical marker/pattern text
-- precisely because the guess is generic, not because their real emails are known to match --
-- some will not match their sender's actual format at all, and that is the expected, safe outcome
-- of an untested guess: MerchantTemplateAdminService's own routing check only ever matches the
-- LITERAL receipt_marker substring first, so a wrong guess just means "this row never fires,"
-- never a wrong extraction (see below for why it cannot reach a real message at all yet).
--
-- Every merchant_templates row here is enabled = FALSE. This is not a formality -- it is the
-- entire reason this bulk insert is safe. TemplateEmailParser.canParse only ever claims a domain
-- via findByMerchantDomainAndEnabledTrue (MerchantTemplateRepository), and
-- GmailReceiptExtractionService only ever calls a parser on a message already gated by
-- SenderAuthenticationService. A disabled template cannot stage anything, for anyone, no matter
-- how wrong its pattern is. Each row must be individually tested against a real sample email
-- through POST /admin/merchant-templates/test (or the Merchant Templates admin page) and
-- activated by an admin before it does anything -- exactly the workflow #235/#240 built, and the
-- reason that workflow had to exist before this seed could responsibly happen at all.
--
-- The 50 gmail_trusted_sender_domains rows ARE seeded ACTIVE, unlike the templates -- an explicit,
-- deliberate choice (confirmed with the product owner before writing this migration), distinct
-- from the templates' disabled default. Trusting a domain only means Finora will EXAMINE
-- authenticated mail from it; per SenderAuthenticationService's own two-condition gate, examining
-- is not staging -- a trusted domain with no enabled template simply accumulates as
-- DETECTED_NOT_STAGED (GmailMerchantStatsService's noParserYet), the existing, already-accepted
-- posture every trusted-but-unparsed domain has had since C4. Nothing about trusting these 50
-- domains changes what happens to a real user's mailbox beyond that counter moving.
INSERT INTO gmail_trusted_sender_domains (id, domain, merchant_name, status) VALUES
    (gen_random_uuid(), 'swiggy.com',          'Swiggy',              'ACTIVE'),
    (gen_random_uuid(), 'blinkit.com',         'Blinkit',             'ACTIVE'),
    (gen_random_uuid(), 'zeptonow.com',        'Zepto',               'ACTIVE'),
    (gen_random_uuid(), 'bigbasket.com',       'BigBasket',           'ACTIVE'),
    (gen_random_uuid(), 'flipkart.com',        'Flipkart',            'ACTIVE'),
    (gen_random_uuid(), 'meesho.com',          'Meesho',              'ACTIVE'),
    (gen_random_uuid(), 'ajio.com',            'Ajio',                'ACTIVE'),
    (gen_random_uuid(), 'nykaa.com',           'Nykaa',               'ACTIVE'),
    (gen_random_uuid(), 'tatacliq.com',        'Tata Cliq',           'ACTIVE'),
    (gen_random_uuid(), 'snapdeal.com',        'Snapdeal',            'ACTIVE'),
    (gen_random_uuid(), 'firstcry.com',        'FirstCry',            'ACTIVE'),
    (gen_random_uuid(), 'lenskart.com',        'Lenskart',            'ACTIVE'),
    (gen_random_uuid(), 'decathlon.in',        'Decathlon',           'ACTIVE'),
    (gen_random_uuid(), 'croma.com',           'Croma',               'ACTIVE'),
    (gen_random_uuid(), 'reliancedigital.in',  'Reliance Digital',    'ACTIVE'),
    (gen_random_uuid(), 'dominos.co.in',       'Domino''s',           'ACTIVE'),
    (gen_random_uuid(), 'pizzahut.co.in',      'Pizza Hut',           'ACTIVE'),
    (gen_random_uuid(), 'eatsure.com',         'EatSure',             'ACTIVE'),
    (gen_random_uuid(), 'licious.in',          'Licious',             'ACTIVE'),
    (gen_random_uuid(), 'countrydelight.in',   'Country Delight',     'ACTIVE'),
    (gen_random_uuid(), 'irctc.co.in',         'IRCTC',               'ACTIVE'),
    (gen_random_uuid(), 'makemytrip.com',      'MakeMyTrip',          'ACTIVE'),
    (gen_random_uuid(), 'yatra.com',           'Yatra',               'ACTIVE'),
    (gen_random_uuid(), 'goibibo.com',         'Goibibo',             'ACTIVE'),
    (gen_random_uuid(), 'cleartrip.com',       'Cleartrip',           'ACTIVE'),
    (gen_random_uuid(), 'easemytrip.com',      'EaseMyTrip',          'ACTIVE'),
    (gen_random_uuid(), 'redbus.in',           'RedBus',              'ACTIVE'),
    (gen_random_uuid(), 'bookmyshow.com',      'BookMyShow',          'ACTIVE'),
    (gen_random_uuid(), 'pvrcinemas.com',      'PVR Cinemas',         'ACTIVE'),
    (gen_random_uuid(), 'inoxmovies.com',      'INOX',                'ACTIVE'),
    (gen_random_uuid(), 'phonepe.com',         'PhonePe',             'ACTIVE'),
    (gen_random_uuid(), 'paytm.com',           'Paytm',               'ACTIVE'),
    (gen_random_uuid(), 'cred.club',           'CRED',                'ACTIVE'),
    (gen_random_uuid(), 'rapido.bike',         'Rapido',              'ACTIVE'),
    (gen_random_uuid(), 'urbancompany.com',    'Urban Company',       'ACTIVE'),
    (gen_random_uuid(), 'dunzo.com',           'Dunzo',               'ACTIVE'),
    (gen_random_uuid(), 'porter.in',           'Porter',              'ACTIVE'),
    (gen_random_uuid(), '1mg.com',             '1mg',                 'ACTIVE'),
    (gen_random_uuid(), 'pharmeasy.in',        'PharmEasy',           'ACTIVE'),
    (gen_random_uuid(), 'netmeds.com',         'Netmeds',             'ACTIVE'),
    (gen_random_uuid(), 'netflix.com',         'Netflix',             'ACTIVE'),
    (gen_random_uuid(), 'hotstar.com',         'Disney+ Hotstar',     'ACTIVE'),
    (gen_random_uuid(), 'sonyliv.com',         'SonyLIV',             'ACTIVE'),
    (gen_random_uuid(), 'spotify.com',         'Spotify',             'ACTIVE'),
    (gen_random_uuid(), 'airtel.in',           'Airtel',              'ACTIVE'),
    (gen_random_uuid(), 'jio.com',             'Jio',                 'ACTIVE'),
    (gen_random_uuid(), 'myvi.in',             'Vi',                  'ACTIVE'),
    (gen_random_uuid(), 'tatapower.com',       'Tata Power',          'ACTIVE'),
    (gen_random_uuid(), 'policybazaar.com',    'Policybazaar',        'ACTIVE'),
    (gen_random_uuid(), 'hdfcergo.com',        'HDFC ERGO',           'ACTIVE');

INSERT INTO merchant_templates
    (id, merchant_domain, merchant_name, receipt_marker, amount_pattern, date_pattern, enabled)
VALUES
    (gen_random_uuid(), 'swiggy.com',         'Swiggy',           'Order Confirmed',      'Order Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'blinkit.com',        'Blinkit',          'Order Delivered',      'Bill Total: Rs. {amount}',    'Order Date: {date}',      false),
    (gen_random_uuid(), 'zeptonow.com',       'Zepto',            'Order Confirmed',      'Grand Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'bigbasket.com',      'BigBasket',        'Order Confirmation',   'Order Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'flipkart.com',       'Flipkart',         'Order Confirmed',      'Order Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'meesho.com',         'Meesho',           'Order Confirmed',      'Order Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'ajio.com',           'Ajio',             'Order Confirmed',      'Grand Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'nykaa.com',          'Nykaa',            'Order Confirmed',      'Order Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'tatacliq.com',       'Tata Cliq',        'Order Confirmed',      'Order Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'snapdeal.com',       'Snapdeal',         'Order Confirmed',      'Order Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'firstcry.com',       'FirstCry',         'Order Confirmed',      'Order Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'lenskart.com',       'Lenskart',         'Order Confirmed',      'Order Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'decathlon.in',       'Decathlon',        'Order Confirmed',      'Order Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'croma.com',          'Croma',            'Order Confirmed',      'Order Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'reliancedigital.in', 'Reliance Digital', 'Order Confirmed',      'Order Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'dominos.co.in',      'Domino''s',        'Order Placed',         'Order Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'pizzahut.co.in',     'Pizza Hut',        'Order Confirmed',      'Order Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'eatsure.com',        'EatSure',          'Order Confirmed',      'Order Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'licious.in',         'Licious',          'Order Confirmed',      'Order Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'countrydelight.in',  'Country Delight',  'Payment Received',     'Amount Paid: Rs. {amount}',   'Payment Date: {date}',    false),
    (gen_random_uuid(), 'irctc.co.in',        'IRCTC',            'Ticket Booked',        'Total Fare: Rs. {amount}',    'Journey Date: {date}',    false),
    (gen_random_uuid(), 'makemytrip.com',     'MakeMyTrip',       'Booking Confirmed',    'Amount Paid: Rs. {amount}',   'Booking Date: {date}',    false),
    (gen_random_uuid(), 'yatra.com',          'Yatra',            'Booking Confirmed',    'Total Amount: Rs. {amount}',  'Booking Date: {date}',    false),
    (gen_random_uuid(), 'goibibo.com',        'Goibibo',          'Booking Confirmed',    'Amount Paid: Rs. {amount}',   'Booking Date: {date}',    false),
    (gen_random_uuid(), 'cleartrip.com',      'Cleartrip',        'Booking Confirmed',    'Total Fare: Rs. {amount}',    'Booking Date: {date}',    false),
    (gen_random_uuid(), 'easemytrip.com',     'EaseMyTrip',       'Booking Confirmed',    'Total Amount: Rs. {amount}',  'Booking Date: {date}',    false),
    (gen_random_uuid(), 'redbus.in',          'RedBus',           'Ticket Booked',        'Amount Paid: Rs. {amount}',   'Journey Date: {date}',    false),
    (gen_random_uuid(), 'bookmyshow.com',     'BookMyShow',       'Booking Confirmed',    'Amount Paid: Rs. {amount}',   'Booking Date: {date}',    false),
    (gen_random_uuid(), 'pvrcinemas.com',     'PVR Cinemas',      'Booking Confirmed',    'Total Amount: Rs. {amount}',  'Show Date: {date}',       false),
    (gen_random_uuid(), 'inoxmovies.com',     'INOX',             'Booking Confirmed',    'Amount Paid: Rs. {amount}',   'Show Date: {date}',       false),
    (gen_random_uuid(), 'phonepe.com',        'PhonePe',          'Payment Successful',   'Amount Paid: Rs. {amount}',   'Transaction Date: {date}', false),
    (gen_random_uuid(), 'paytm.com',          'Paytm',            'Payment Successful',   'Amount Paid: Rs. {amount}',   'Transaction Date: {date}', false),
    (gen_random_uuid(), 'cred.club',          'CRED',             'Payment Successful',   'Amount Paid: Rs. {amount}',   'Payment Date: {date}',    false),
    (gen_random_uuid(), 'rapido.bike',        'Rapido',           'Trip Completed',       'Total Fare: Rs. {amount}',    'Trip Date: {date}',       false),
    (gen_random_uuid(), 'urbancompany.com',   'Urban Company',    'Service Completed',    'Amount Paid: Rs. {amount}',   'Service Date: {date}',    false),
    (gen_random_uuid(), 'dunzo.com',          'Dunzo',            'Order Delivered',      'Bill Total: Rs. {amount}',    'Order Date: {date}',      false),
    (gen_random_uuid(), 'porter.in',          'Porter',           'Booking Confirmed',    'Total Fare: Rs. {amount}',    'Booking Date: {date}',    false),
    (gen_random_uuid(), '1mg.com',            '1mg',              'Order Confirmed',      'Order Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'pharmeasy.in',       'PharmEasy',        'Order Confirmed',      'Order Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'netmeds.com',        'Netmeds',          'Order Confirmed',      'Order Total: Rs. {amount}',   'Order Date: {date}',      false),
    (gen_random_uuid(), 'netflix.com',        'Netflix',          'Payment Confirmation', 'Amount Charged: Rs. {amount}', 'Billing Date: {date}',   false),
    (gen_random_uuid(), 'hotstar.com',        'Disney+ Hotstar',  'Payment Confirmation', 'Amount Charged: Rs. {amount}', 'Billing Date: {date}',   false),
    (gen_random_uuid(), 'sonyliv.com',        'SonyLIV',          'Payment Confirmation', 'Amount Charged: Rs. {amount}', 'Billing Date: {date}',   false),
    (gen_random_uuid(), 'spotify.com',        'Spotify',          'Payment Confirmation', 'Amount Charged: Rs. {amount}', 'Billing Date: {date}',   false),
    (gen_random_uuid(), 'airtel.in',          'Airtel',           'Payment Received',     'Amount Paid: Rs. {amount}',   'Payment Date: {date}',    false),
    (gen_random_uuid(), 'jio.com',            'Jio',              'Payment Received',     'Amount Paid: Rs. {amount}',   'Payment Date: {date}',    false),
    (gen_random_uuid(), 'myvi.in',            'Vi',               'Payment Received',     'Amount Paid: Rs. {amount}',   'Payment Date: {date}',    false),
    (gen_random_uuid(), 'tatapower.com',      'Tata Power',       'Bill Payment',         'Amount Paid: Rs. {amount}',   'Payment Date: {date}',    false),
    (gen_random_uuid(), 'policybazaar.com',   'Policybazaar',     'Payment Confirmation', 'Premium Paid: Rs. {amount}',  'Payment Date: {date}',    false),
    (gen_random_uuid(), 'hdfcergo.com',       'HDFC ERGO',        'Payment Confirmation', 'Premium Paid: Rs. {amount}',  'Payment Date: {date}',    false);
