# Java Delivery Assessment

Two independently deployable Java 21 and Spring Boot services for a Dutch parcel company. Delivery Order accepts
delivery orders, asks Pricing & Eligibility whether a delivery is allowed and what it costs, and creates an order only
when it is eligible.

| Service | Port | Responsibility |
| --- | --- | --- |
| `delivery-order-api` | 8080 | Delivery order HTTP API, PostgreSQL persistence, and pricing client |
| `pricing-eligibility-api` | 8081 | Stateless pricing and eligibility evaluation |

- [Architecture](docs/architecture.md): request flow, business scenarios, retries and circuit breaker, concurrency and
  the data model, with diagrams.
- [API contracts](docs/openapi/): OpenAPI definitions both services generate their code from.
- [Improvements](docs/improvements/): [deployment](docs/improvements/deployment.md),
  [production authentication and authorisation](docs/improvements/authentication.md), and
  [idempotency record retention](docs/improvements/idempotency-record-retention.md).

## How Delivery Order behaves

| Situation | Response |
| --- | --- |
| Eligible delivery | `201 Created` with the order and a `Location` header; the confirmed price is stored |
| Same request again with the same `Idempotency-Key` | `201` with the same order; Pricing is not called again |
| Same key with a different request | `409 IDEMPOTENCY_CONFLICT` |
| Not eligible (weight above 20 kg, same-day between postcode areas, country other than NL) | `422` with the rejection code; no order is created; repeating the request returns the same `422` |
| Invalid input or missing `Idempotency-Key` | `400 VALIDATION_FAILED` |
| Pricing slow, failing or returning an invalid answer | `503 PRICING_UNAVAILABLE` with `Retry-After: 5`; nothing is stored, so retry with the same key |
| `GET` an order | The stored order and price, without calling Pricing; unknown ID gives `404 ORDER_NOT_FOUND` |

Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) Problem Details with a `code` and a `correlationId`.
Concurrent requests with the same key create exactly one order. Calls to Pricing use a 500 ms connect and 2 s read
timeout, at most one retry for connection failures and 502/503/504, and a circuit breaker.

## Run with Docker Compose

With Docker running, start the stack from the repository root and check both APIs. Compose uses `delivery-dev` as the local PostgreSQL password.

```sh
docker compose up --build --wait
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8081/actuator/health
```

With the standalone Compose tool, use `docker-compose` instead of `docker compose`.
Both health endpoints should return `{"status":"UP"}`. Compose builds both
services with their Maven wrappers and starts PostgreSQL on the Compose network.

Use `docker compose logs -f` to view logs and `docker compose down` to stop the
stack. PostgreSQL data persists in the named volume.

For an optional local override, set `DELIVERY_DB_PASSWORD` in the shell or in a root `.env` file before the first startup. An existing database keeps its current password. See [deployment improvements](docs/improvements/deployment.md) for production configuration.

## Example requests

### Delivery Order

With the stack running, create an order (expect HTTP 201 with `"status":"CONFIRMED"`, a price of 8.95 and a
`Location` header):

```sh
curl -si http://localhost:8080/api/v1/delivery-orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 8fc11c85-630d-4044-a620-cc862b8e19d5' \
  -d '{"customerReference":"CUSTOMER-ORDER-2026-001","sender":{"name":"Test Sender","postalCode":"1012AB","countryCode":"NL"},"recipient":{"name":"Test Recipient","postalCode":"5611EM","countryCode":"NL"},"parcel":{"weightInGrams":2750},"deliveryType":"NEXT_DAY"}'
```

- Run the same command again: the same order is returned.
- Change `weightInGrams` but keep the key: HTTP 409 `IDEMPOTENCY_CONFLICT`.
- Use a new key and `"weightInGrams":25000`: HTTP 422 `WEIGHT_LIMIT_EXCEEDED`.
- Use a new key and `"countryCode":"BE"` for the recipient: HTTP 422 `UNSUPPORTED_COUNTRY`.
- Stop Pricing (`docker compose stop pricing-eligibility-api`) and use a new key: HTTP 503 `PRICING_UNAVAILABLE`.
  Start it again and repeat the same request: HTTP 201.

Retrieve the order with the ID from the `Location` header:

```sh
curl -s http://localhost:8080/api/v1/delivery-orders/<orderId>
```

### Pricing & Eligibility

These `curl` commands call the Pricing & Eligibility API directly.
[`pricing-eligibility-api/sample-requests/`](pricing-eligibility-api/sample-requests/) has
`curl` commands for every scenario, each with its expected result.

Eligible delivery, the assignment's example (expect HTTP 200 and a price of 8.95):

```sh
curl -s http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: example-1' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"1012 AB","destinationPostalCode":"5611EM","weightInGrams":2750,"deliveryType":"NEXT_DAY"}'
```

Business rejection (expect HTTP 200 with `"eligible":false` and `WEIGHT_LIMIT_EXCEEDED`):

```sh
curl -s http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"5611EM","destinationPostalCode":"3011AB","weightInGrams":20001,"deliveryType":"STANDARD"}'
```

Same-day delivery between different areas (expect HTTP 200 with `SAME_DAY_NOT_SUPPORTED`):

```sh
curl -s http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"5611EM","destinationPostalCode":"1012AB","weightInGrams":100,"deliveryType":"SAME_DAY"}'
```

Invalid request (expect HTTP 400 Problem Details with `VALIDATION_FAILED`):

```sh
curl -s http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"0123AB","destinationPostalCode":"5611EM","weightInGrams":0,"deliveryType":"NEXT_DAY"}'
```

## Build and run with Maven

Each service has its own Maven wrapper. A local Maven installation is optional.

From the repository root, build both services with JDK 21 using the root
aggregator POM and the root Maven wrapper:

```sh
./mvnw clean verify
```

The root POM and wrapper provide a convenience build for local development.
Each service owns its POM, produces a separate executable JAR, and has its own
Dockerfile. For production, build, version, and deploy each service's artifact
separately. Configure PostgreSQL and the Pricing endpoint for Delivery Order in
the deployment environment.

To build either service independently, point its wrapper at its own POM:

```sh
./delivery-order-api/mvnw -f delivery-order-api/pom.xml clean verify
./pricing-eligibility-api/mvnw -f pricing-eligibility-api/pom.xml clean verify
```

Both services generate their API interfaces and models from the shared
OpenAPI contracts in [`docs/openapi/`](docs/openapi/), so a service build needs
that folder from the same checkout. To build a single service image, run
`docker build` from the repository root:

```sh
docker build -f delivery-order-api/Dockerfile -t delivery-order-api .
docker build -f pricing-eligibility-api/Dockerfile -t pricing-eligibility-api .
```

Run Pricing with `cd pricing-eligibility-api && ./mvnw spring-boot:run`.
Its health endpoint is `http://localhost:8081/actuator/health`.

Delivery Order requires a running PostgreSQL instance. It defaults to
`jdbc:postgresql://localhost:5432/delivery` with the user `delivery`. Set
`DELIVERY_DB_PASSWORD` and override `DELIVERY_DB_URL` or `DELIVERY_DB_USER`
as needed before running `cd delivery-order-api && ./mvnw spring-boot:run`.
Its health endpoint is `http://localhost:8080/actuator/health`.
Set `PRICING_BASE_URL` to override the default `http://localhost:8081`.

## Tests

`./mvnw clean verify` runs the unit tests (`*Test`) and integration tests (`*IT`) of both services, and writes a
coverage report per service to `target/site/jacoco/index.html`.

- **Pricing & Eligibility:** every pricing and eligibility rule, the boundary weights, postcode normalisation, money
  rounding, and the API over real HTTP.
- **Delivery Order:** the idempotency decisions and order flow (unit), the Pricing client against a WireMock Pricing
  (timeouts, retry, circuit breaker, invalid responses), the full API against PostgreSQL and WireMock (including 10
  concurrent requests with one key), and the database constraints.

Delivery Order's database tests start PostgreSQL with Testcontainers, so **Docker must be running**.

## Privacy and GDPR

Delivery Order processes personal data (sender and recipient names and postal codes), so the GDPR (AVG in the
Netherlands) applies. Items marked *proposal* are not implemented in this assessment.

**Data minimisation and purpose limitation**

- Delivery Order stores only what a delivery needs: sender and recipient name, postal code and country, parcel weight,
  delivery type, `customerReference` and the confirmed price. No street address, phone number, email or date of birth
  is collected.
- Pricing receives no names: only postal codes, weight and delivery type.
- The data is used to price, confirm and carry out the delivery, nothing else. Idempotency records keep a SHA-256
  fingerprint of the request, not the request itself.
- Error responses never echo submitted values.

**Retention**

- Orders are kept as long as the delivery and legal obligations require. The confirmed price is a financial record,
  which under Dutch tax law generally has to be kept for 7 years; confirm the exact period with legal advice.
- Names are not needed for that record, so they can be anonymised once the delivery period is over (*proposal*: a
  scheduled job).
- Idempotency records are only needed while a client may retry and get their own short retention period
  ([idempotency record retention](docs/improvements/idempotency-record-retention.md), *proposal*).

**Right to erasure**

- On request, the names on a customer's orders are removed or anonymised (*proposal*: an erasure process).
- Where a legal retention obligation applies (GDPR Article 17(3)(b)), the order and its price are kept without the
  names.
- The idempotency record references the order, so it is deleted before an order itself is deleted.

**Auditability without unnecessary personal data**

- Events are traced by technical identifiers only: `orderId`, `pricingRequestId`, `correlationId`, `traceId`, and in
  production the client ID from the access token
  ([production authentication](docs/improvements/authentication.md)).

**Observability without leaking personal data**

- Logs never contain names, postal codes, request bodies or the `Authorization` header; a test checks that names do
  not appear in the log output.
- Database errors leave out row values (`logServerErrorDetail=false`); a test checks that a constraint violation does
  not expose names or postal codes.
- Internal errors return a generic `500 INTERNAL_ERROR` without stack traces or internal details.
- All test data and examples are fictional.

## Assumptions and known limitations

- **Orders exist only when eligible.** Rejected and Pricing-unavailable outcomes are reported in the POST response
  (`orderStatus`), not stored as orders, so `GET` returns confirmed orders only.
- **A rejection is final for its key.** A corrected or retried order uses a new `Idempotency-Key`.
- **No authentication yet.** In production, both services would accept OAuth 2.0 JWT access tokens from an identity
  provider, checking scopes per endpoint, and Delivery Order would call Pricing with its own client-credentials
  token. Idempotency keys, global today, would then be scoped per client. See
  [production authentication and authorisation](docs/improvements/authentication.md).
- **Not implemented:** authentication (see above), the idempotency record clean-up job, exporting traces to a tracing backend
  (trace and span IDs are logged and propagated, but spans are not sent anywhere), and Markdown copies of the
  decision records in `docs/decisions/`.
- **Database changes:** the schema is a single Flyway migration. If you ran an earlier version locally, recreate the
  database with `docker compose down -v`.
