-- Local profile only. Reapplying this seed never resets existing products or stocks.
-- Sale dates are relative to the first seed execution, not to each application start.
INSERT INTO products (
    id, sku, name, description, price_minor, currency, active, sale_starts_at, sale_ends_at
)
VALUES
    ('10000000-0000-0000-0000-000000000001', 'LOCAL-KEYBOARD', 'Mechanical keyboard',
     'Available product for normal and multi-item reservations', 249900, 'UAH', TRUE, NULL, NULL),
    ('10000000-0000-0000-0000-000000000002', 'LOCAL-MOUSE', 'Wireless mouse',
     'Available product for normal and multi-item reservations', 89900, 'UAH', TRUE, NULL, NULL),
    ('10000000-0000-0000-0000-000000000003', 'LOCAL-HEADPHONES', 'Wireless headphones',
     'Only one unit remains', 189900, 'UAH', TRUE, NULL, NULL),
    ('10000000-0000-0000-0000-000000000004', 'LOCAL-MONITOR', 'Monitor 24 inch',
     'Active product with no available stock', 599900, 'UAH', TRUE, NULL, NULL),
    ('10000000-0000-0000-0000-000000000005', 'LOCAL-SPEAKER', 'Portable speaker',
     'Inactive product with stock; hidden from the public catalog', 129900, 'UAH', FALSE, NULL, NULL),
    ('10000000-0000-0000-0000-000000000006', 'LOCAL-GAMEPAD', 'Wireless gamepad',
     'Sale starts one day after seeding and lasts one day', 159900, 'UAH', TRUE,
     NOW() + INTERVAL '1 day', NOW() + INTERVAL '2 days'),
    ('10000000-0000-0000-0000-000000000007', 'LOCAL-WEBCAM', 'Webcam Full HD',
     'Sale has already ended', 119900, 'UAH', TRUE,
     NOW() - INTERVAL '2 days', NOW() - INTERVAL '1 day')
ON CONFLICT (sku) DO NOTHING;

INSERT INTO stocks (id, product_id, on_hand, reserved)
SELECT seed.stock_id, product.id, seed.on_hand, 0
FROM (VALUES
    ('20000000-0000-0000-0000-000000000001'::UUID, '10000000-0000-0000-0000-000000000001'::UUID, 20::BIGINT),
    ('20000000-0000-0000-0000-000000000002'::UUID, '10000000-0000-0000-0000-000000000002'::UUID, 30::BIGINT),
    ('20000000-0000-0000-0000-000000000003'::UUID, '10000000-0000-0000-0000-000000000003'::UUID, 1::BIGINT),
    ('20000000-0000-0000-0000-000000000004'::UUID, '10000000-0000-0000-0000-000000000004'::UUID, 0::BIGINT),
    ('20000000-0000-0000-0000-000000000005'::UUID, '10000000-0000-0000-0000-000000000005'::UUID, 5::BIGINT),
    ('20000000-0000-0000-0000-000000000006'::UUID, '10000000-0000-0000-0000-000000000006'::UUID, 10::BIGINT),
    ('20000000-0000-0000-0000-000000000007'::UUID, '10000000-0000-0000-0000-000000000007'::UUID, 10::BIGINT)
) AS seed(stock_id, product_id, on_hand)
JOIN products product ON product.id = seed.product_id
ON CONFLICT (product_id) DO NOTHING;
