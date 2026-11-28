# Architecture

Delivery Order and Pricing & Eligibility are separate Java 21 / Spring Boot applications. Each has its own Maven
wrapper, POM, Dockerfile and OpenAPI contract (`docs/openapi/`), and they share no code or database. The root Compose
file starts both services and PostgreSQL.

## System overview

```mermaid
flowchart LR
    Client["Client"] -->|"HTTPS :8080<br/>Idempotency-Key, X-Correlation-ID"| Order

    subgraph Order["Delivery Order API (owns orders)"]
        direction TB
        Controller["controller<br/>validation, Problem Details"] --> Service["service<br/>DeliveryOrderService, IdempotencyService"]
        Service --> Client2["pricing<br/>PricingClient: timeouts, retry, circuit breaker"]
        Service --> Repo["repository<br/>JPA, native insert"]
    end

    Client2 -->|"HTTP :8081<br/>X-Correlation-ID forwarded"| Pricing["Pricing & Eligibility API<br/>stateless, owns pricing rules"]
    Repo -->|"JDBC :5432<br/>Flyway migrations"| DB[("PostgreSQL<br/>delivery_order<br/>idempotency_record")]
```

- **Delivery Order** owns orders and the database. It stores the confirmed price, so later tariff changes never alter
  historical orders, and GET never calls Pricing.
- **Pricing & Eligibility** is stateless: eligibility rules and price rules are pluggable beans (ADR-003).
- Requests run on virtual threads; no transaction or database connection is held while Pricing is called (ADR-007).

## Create an order: POST /api/v1/delivery-orders

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant API as DeliveryOrderController
    participant S as DeliveryOrderService
    participant I as IdempotencyService
    participant P as PricingClient
    participant PR as Pricing API
    participant DB as PostgreSQL

    C->>API: POST + Idempotency-Key
    API->>API: Bean Validation, normalise postcode and country
    API->>S: createOrder(key, request)
    S->>S: fingerprint = SHA-256 of normalised request
    S->>I: bindOrFindOutcome(key, fingerprint)
    I->>DB: INSERT idempotency_record (key, fingerprint) ON CONFLICT DO NOTHING, COMMIT
    alt key bound to a different request
        I-->>S: conflict
        S-->>C: 409 IDEMPOTENCY_CONFLICT
    else key already decided
        I-->>S: stored order / stored rejection
        S-->>C: 201 same order, or 422 same code
    else new key, or bound but not decided yet
        S->>S: country is NL? otherwise record REJECTED
        S->>P: evaluate(pricingRequestId, request)
        Note over P,PR: no transaction, no DB connection
        P->>PR: POST /api/v1/pricing/evaluations
        PR-->>P: eligible + price, or rejected + code
        alt eligible
            S->>DB: BEGIN, INSERT delivery_order, UPDATE idempotency_record SET CONFIRMED WHERE outcome IS NULL, COMMIT
            S-->>C: 201 Created + Location
        else rejected
            S->>DB: UPDATE idempotency_record SET REJECTED WHERE outcome IS NULL
            S-->>C: 422 orderStatus REJECTED
        else Pricing unavailable
            S-->>C: 503 PRICING_UNAVAILABLE, Retry-After 5, key stays bound without outcome
        end
    end
```

## Business scenarios

```mermaid
flowchart TD
    A["POST /delivery-orders"] --> B{"Request valid?<br/>key, fields, postcode, weight"}
    B -- no --> R400["400 VALIDATION_FAILED"]
    B -- yes --> C{"Key already has<br/>an outcome?"}
    C -- "yes, different request" --> R409["409 IDEMPOTENCY_CONFLICT"]
    C -- "yes, CONFIRMED" --> R201a["201 same order<br/>no Pricing call"]
    C -- "yes, REJECTED" --> R422a["422 same rejection code<br/>no Pricing call"]
    C -- no --> D{"Sender and<br/>recipient in NL?"}
    D -- no --> E["record REJECTED"] --> R422b["422 UNSUPPORTED_COUNTRY"]
    D -- yes --> F["Call Pricing"]
    F -- "eligible, valid EUR total" --> G["save order + CONFIRMED record<br/>in one transaction"]
    G -- inserted --> R201b["201 Created + Location"]
    G -- "key recorded first by a<br/>concurrent request" --> H["roll back own order"] --> C
    F -- "rejected<br/>WEIGHT_LIMIT_EXCEEDED, SAME_DAY_NOT_SUPPORTED" --> I["record REJECTED"] --> R422c["422 with rejection code"]
    F -- "timeout, 5xx, invalid response,<br/>circuit open" --> R503["503 PRICING_UNAVAILABLE<br/>Retry-After 5, key bound without outcome"]
    F -- "4xx from Pricing" --> R500["500 INTERNAL_ERROR<br/>our bug, not retried"]
```

| Scenario | Response | Stored |
| --- | --- | --- |
| Eligible delivery | 201 with order, `Location` | order + `CONFIRMED` record |
| Same request, same key again | 201 with the same order | nothing new; Pricing not called |
| Same key, different request | 409 `IDEMPOTENCY_CONFLICT` | nothing |
| Weight over 20 kg / same-day outside one postcode area | 422 `WEIGHT_LIMIT_EXCEEDED` / `SAME_DAY_NOT_SUPPORTED` | `REJECTED` record |
| Country other than NL | 422 `UNSUPPORTED_COUNTRY` (Pricing not called) | `REJECTED` record |
| Invalid input (postcode, weight ≤ 0, missing key, unknown delivery type) | 400 `VALIDATION_FAILED` | nothing |
| Pricing timeout, 5xx, invalid response or circuit open | 503 `PRICING_UNAVAILABLE`, `Retry-After: 5` | the key stays bound to the request, without an outcome: the same request can retry; a different request with that key gets 409 |
| Concurrent requests with one key | all get the outcome of the first one recorded | one order, one record |
| GET unknown order / invalid ID | 404 `ORDER_NOT_FOUND` / 400 | — |

Errors use RFC 9457 Problem Details with a `code`, the `correlationId`, and `orderStatus` for 422 and 503 (ADR-004).

## Calling Pricing: timeouts, retry and circuit breaker

`PricingClient` wraps each call as `CircuitBreaker( Retry( post + validate ) )`.

```mermaid
flowchart TD
    S["PricingClient.evaluate"] --> CB{"Circuit breaker<br/>state?"}
    CB -- OPEN --> U1["PricingUnavailable<br/>reason circuit_open<br/>Pricing not called"]
    CB -- "CLOSED or HALF_OPEN" --> T1["Attempt 1: POST<br/>connect 500 ms, read 2 s"]
    T1 -- "200 and valid" --> OK["Eligible or rejected decision"]
    T1 -- "connection refused, connect timeout,<br/>502, 503 or 504" --> W["wait 100-200 ms"] --> T2["Attempt 2: POST"]
    T1 -- "read timeout, 500,<br/>invalid body" --> U2["PricingUnavailable<br/>no retry"]
    T1 -- "4xx" --> BUG["IllegalStateException<br/>500, not counted by the breaker"]
    T2 -- "200 and valid" --> OK
    T2 -- fails --> U3["PricingUnavailable<br/>timeout, connection_failed,<br/>server_error, invalid_response"]
    OK --> REC["breaker records success"]
    U2 --> RECF["breaker records one failure"]
    U3 --> RECF
```

- **Timeouts:** connect 500 ms, read 2 s (normal Pricing latency 50–150 ms; 2–5 s counts as unavailable).
- **Retry:** at most one extra attempt, only when the request did not reach Pricing or Pricing answered 502/503/504.
  Read timeouts and 500 are not retried; validation failures and business rejections are never retried.
- **Response check:** `requestId` must match, `eligible` must be a boolean, the total must be EUR, ≥ 0 and have at
  most two decimals. Anything else is `invalid_response`.
- **Circuit breaker:** because retry is inside it, one call counts once, however many attempts it made.

```mermaid
stateDiagram-v2
    [*] --> CLOSED
    CLOSED --> OPEN: at least 50% of the last 20 calls failed (after 10 calls)
    OPEN --> HALF_OPEN: after 10 s
    HALF_OPEN --> CLOSED: trial calls succeed
    HALF_OPEN --> OPEN: trial calls fail
    note right of OPEN: every call fails fast with 503, Pricing is not called
    note left of CLOSED: business rejections and 4xx do not count as failures
```

## Concurrent requests with the same key

No locks are used in the application (ADR-0001, ADR-0010). The primary key on `idempotency_record.idempotency_key`
binds each key to one request, and the conditional update `WHERE outcome IS NULL` lets exactly one request decide it:
a second update of the same key waits for the first to commit, and PostgreSQL then re-checks the condition.

```mermaid
sequenceDiagram
    participant A as Request A
    participant DB as PostgreSQL
    participant B as Request B (same key)

    A->>DB: bindOrFindOutcome: INSERT record (key, fingerprint), 1 row
    B->>DB: bindOrFindOutcome: INSERT record, 0 rows, same fingerprint, no outcome yet
    Note over A,B: both call Pricing (stateless, harmless)
    A->>DB: BEGIN, INSERT order A, UPDATE record WHERE outcome IS NULL (1 row)
    B->>DB: BEGIN, INSERT order B, UPDATE record WHERE outcome IS NULL
    Note over B,DB: waits until A's transaction ends
    A->>DB: COMMIT
    DB-->>B: re-checked: outcome is set, 0 rows
    B->>DB: ROLLBACK (order B discarded)
    B->>DB: bindOrFindOutcome: order A
    Note over A,B: both clients receive 201 with order A
```

## Read an order: GET /api/v1/delivery-orders/{orderId}

Reads the stored order from PostgreSQL and never calls Pricing, so it works while Pricing is down and always shows the
price that was confirmed. `createdAt` is stored in UTC and returned with the Europe/Amsterdam offset.

## Data model

```mermaid
erDiagram
    delivery_order ||--o| idempotency_record : "confirmed by"
    delivery_order {
        UUID id PK
        VARCHAR customer_reference
        VARCHAR sender_name
        VARCHAR sender_postal_code
        VARCHAR recipient_name
        VARCHAR recipient_postal_code
        BIGINT weight_in_grams "positive_weight"
        VARCHAR delivery_type "known_delivery_type"
        VARCHAR status "confirmed_status"
        NUMERIC price_amount "whole_cents_price"
        VARCHAR price_currency
        UUID pricing_request_id
        TIMESTAMPTZ created_at
    }
    idempotency_record {
        VARCHAR idempotency_key PK
        VARCHAR request_fingerprint
        VARCHAR outcome "CONFIRMED or REJECTED"
        TEXT rejection_code "rejected_has_reason_code"
        UUID order_id FK "confirmed_has_order"
        TIMESTAMPTZ created_at
    }
```

## Observability

- `X-Correlation-ID` is read or generated per request, put in the MDC, returned, and forwarded to Pricing. It is the
  business-facing ID a client can quote.
- Micrometer Tracing (OpenTelemetry bridge) adds `traceId` and `spanId` to every log line and sends the W3C
  `traceparent` header to Pricing, so one order has one trace ID in both services and a span ID per step. An incoming
  `traceparent` is continued. Spans are not exported yet; add an exporter (for example OTLP) to see them in a tracing
  backend.
- Structured `event` log fields: `order.created`, `order.rejected`, `order.pricing_unavailable`,
  `order.previous_outcome_returned`, `order.idempotency_conflict`, `pricing.call_failed`, `pricing.call_retry`,
  `pricing.circuit_state_changed`. Names, postcodes and request bodies are never logged.
- Actuator: `/actuator/health`, `/actuator/info`, `/actuator/metrics`.

See [decision records](decisions/), [API contracts](openapi/), [deployment improvements](improvements/deployment.md), and [idempotency record retention](improvements/idempotency-record-retention.md).
