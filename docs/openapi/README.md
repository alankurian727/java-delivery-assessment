# OpenAPI contracts

Both contracts use OpenAPI 3.0.3. This folder is the single source of each contract; both services generate their code from these files at build time (see ADR-002).

| Contract | Operations |
| --- | --- |
| [delivery-order.yaml](delivery-order.yaml) | POST /api/v1/delivery-orders; GET /api/v1/delivery-orders/{orderId} |
| [pricing-eligibility.yaml](pricing-eligibility.yaml) | POST /api/v1/pricing/evaluations |

## Code generation

Each service's `pom.xml` runs `openapi-generator-maven-plugin` against this folder during the build. The plugin generates code into the service's `target/generated-sources/openapi`, which is not committed.

- Pricing generates its server interface (`PricingApi`) and models from `pricing-eligibility.yaml`.
- Delivery Order generates its server interface (`DeliveryOrdersApi`) and models from `delivery-order.yaml`, and its Pricing client models from the same `pricing-eligibility.yaml`. A Pricing contract change therefore regenerates the client in the same build, so a mismatch fails at compile time.
- Controllers implement the generated interfaces. The interfaces have no default methods, so every operation must be implemented.
- Each service packages only its own contract and serves it at `/openapi/<contract>.yaml`; Swagger UI (`/swagger-ui/index.html`) shows that contract, not a spec derived from the code.
- Docker images are built from the repository root so the build can read this folder: `docker build -f delivery-order-api/Dockerfile .`

## Contract decisions

- Delivery Order validates syntax (400), including positive parcel weight and Dutch postal-code format; business rejection returns 422. Non-NL country codes are rejected by Delivery Order before calling Pricing, whose request follows the assignment's country-free example.
- Pricing returns HTTP 200 with mutually exclusive eligible and rejected response schemas. Weight above 20 kg and incompatible same-day routes are business rejections.
- Only confirmed deliveries have order IDs and are retrievable. Rejected and temporarily unavailable attempts are represented by the `orderStatus` extension of Problem Details; they are not confirmed orders.
- Equivalent completed submissions get their original terminal status/body again (201 and Location for success, 422 for rejection). Different content for the same key returns 409. Concurrent matching requests are each evaluated, but only the first outcome is stored and the others get that outcome. A 503 permits reevaluation with the same key and input. Compare normalized requests, not raw JSON bytes.
- Pricing timeout, upstream server error, and invalid upstream response map to 503 without confirmation. GET uses the persisted historical price and does not call Pricing.
- Idempotency keys are opaque, case-sensitive, 1–128 printable non-space ASCII characters. UUID order/request IDs and UTC timestamps are used. Examples contain fictional labels, not real customer data.
- Pricing responses are open for additive change: clients ignore unknown properties and tolerate unknown price component types, rejection codes and problem codes. The eligible flag distinguishes eligible from rejected results.
- Retryable responses (503) carry a Retry-After header.
- Money is represented as a JSON number, mapped to BigDecimal in Java. Enforce at most two decimal places in application validation and monetary calculations. The schemas deliberately omit fractional multipleOf to avoid floating-point validator inconsistencies. No float/double calculations.

## Pricing assumption to confirm

The assignment's illustrative NEXT_DAY breakdown differs from its explicit tariff table. Follow the table: EUR 6.95 base + EUR 1.00 weight surcharge for 2,750 g + EUR 1.00 urban-area surcharge = EUR 8.95. No separate NEXT_DAY surcharge is added. The urban-area surcharge applies if either endpoint is in 1000AA–1099ZZ. The assignment's example rules out destination-only pricing: only the origin (1012AB) is urban there, and the total is still EUR 8.95. Assume the surcharge is charged once when both endpoints are urban; this remains to confirm with the assessor.

## Scope

These are contract-first business API definitions, not an implementation. Spring Boot Actuator management endpoints are separate; production authentication and authorization are not specified in these local assessment contracts.
