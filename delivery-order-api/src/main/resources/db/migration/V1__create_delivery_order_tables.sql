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
    weight_in_grams        BIGINT        NOT NULL CHECK (weight_in_grams > 0),
    delivery_type          VARCHAR(20)   NOT NULL CHECK (delivery_type IN ('STANDARD', 'NEXT_DAY', 'SAME_DAY')),
    status                 VARCHAR(20)   NOT NULL CHECK (status = 'CONFIRMED'),
    price_amount           NUMERIC(10,2) NOT NULL CHECK (price_amount >= 0),
    price_currency         VARCHAR(3)    NOT NULL,
    pricing_request_id     UUID          NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL
);

-- The final outcome of each Idempotency-Key (ADR-008), written once and never updated, so it can be replayed.
-- The primary key allows one outcome per key across all instances.
CREATE TABLE idempotency_record (
    idempotency_key     VARCHAR(128) PRIMARY KEY,
    request_fingerprint VARCHAR(64)  NOT NULL,
    outcome             VARCHAR(20)  NOT NULL CHECK (outcome IN ('CONFIRMED', 'REJECTED')),
    rejection_code      VARCHAR(50),
    order_id            UUID         REFERENCES delivery_order (id),
    created_at          TIMESTAMPTZ  NOT NULL,
    CHECK ((outcome = 'CONFIRMED') = (order_id IS NOT NULL)),
    CHECK ((outcome = 'REJECTED') = (rejection_code IS NOT NULL))
);
