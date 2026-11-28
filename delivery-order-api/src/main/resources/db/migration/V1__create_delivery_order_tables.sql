-- Confirmed delivery orders only (ADR-005): an order exists once Pricing has returned an eligible evaluation.
CREATE TABLE delivery_order (
    id                     UUID          PRIMARY KEY,
    customer_reference     VARCHAR(128)  NOT NULL,
    sender_name            VARCHAR(200)  NOT NULL,
    sender_postal_code     VARCHAR(6)    NOT NULL,
    sender_country_code    VARCHAR(2)    NOT NULL,
    recipient_name         VARCHAR(200)  NOT NULL,
    recipient_postal_code  VARCHAR(6)    NOT NULL,
    recipient_country_code VARCHAR(2)    NOT NULL,
    weight_in_grams        BIGINT        NOT NULL CONSTRAINT positive_weight     CHECK (weight_in_grams > 0),
    delivery_type          VARCHAR(20)   NOT NULL CONSTRAINT known_delivery_type CHECK (delivery_type IN ('STANDARD', 'NEXT_DAY', 'SAME_DAY')),
    status                 VARCHAR(20)   NOT NULL CONSTRAINT confirmed_status    CHECK (status = 'CONFIRMED'),
    -- Unconstrained NUMERIC stores the exact amount (no silent rounding); the check allows only whole cents >= 0
    -- and excludes the special values Infinity and NaN.
    price_amount           NUMERIC       NOT NULL CONSTRAINT whole_cents_price   CHECK (price_amount >= 0
                                                                                    AND price_amount < 'Infinity'::numeric
                                                                                    AND price_amount = trunc(price_amount, 2)),
    price_currency         VARCHAR(3)    NOT NULL,
    pricing_request_id     UUID          NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL
);

-- One row per Idempotency-Key (ADR-0010): bound to the request's fingerprint when the key is first received, then
-- given its outcome once, when it is decided. Repeated requests get the same answer; a different request gets 409.
-- The primary key allows one row per key across all instances.
CREATE TABLE idempotency_record (
    idempotency_key     VARCHAR(128) PRIMARY KEY,
    request_fingerprint VARCHAR(64)  NOT NULL,
    outcome             VARCHAR(20)  CONSTRAINT known_outcome CHECK (outcome IN ('CONFIRMED', 'REJECTED')), -- NULL: not decided yet
    rejection_code      TEXT,         -- Pricing owns the codes, so new codes are stored as they are
    order_id            UUID         REFERENCES delivery_order (id),
    created_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT confirmed_has_order      CHECK ((outcome = 'CONFIRMED') = (order_id IS NOT NULL)),
    CONSTRAINT rejected_has_reason_code CHECK ((outcome = 'REJECTED') = (rejection_code IS NOT NULL)),
    CONSTRAINT undecided_has_no_result  CHECK (outcome IS NOT NULL OR (order_id IS NULL AND rejection_code IS NULL))
);
