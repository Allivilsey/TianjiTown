-- Reattribute by first reservation creation time (never settlement/retry time).
-- Shanghai business day = UTC + 8 hours - 4 hours. Epoch values remain UTC.
-- Amounts, IDs, business keys and RESERVED/APPLIED/CANCELLED states are unchanged.
UPDATE quickshop_subsidy_reservations
   SET period_12h_start = CAST(strftime('%s', datetime(created_at / 1000.0, 'unixepoch', '+4 hours'),
                            'start of day') AS INTEGER) * 1000
                         + CASE WHEN CAST(strftime('%H', created_at / 1000.0, 'unixepoch', '+4 hours') AS INTEGER) < 12
                                THEN 0 ELSE 43200000 END - 14400000,
       week_start = CAST(strftime('%s', datetime(created_at / 1000.0, 'unixepoch', '+4 hours'),
                         'start of day') AS INTEGER) * 1000
                    - ((CAST(strftime('%w', created_at / 1000.0, 'unixepoch', '+4 hours') AS INTEGER) + 6) % 7) * 86400000
                    - 14400000;
