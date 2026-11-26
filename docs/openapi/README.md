# OpenAPI contracts

Both standalone contracts use OpenAPI 3.0.3. Each contract lives in the service that owns it, so every service builds on its own, including inside its Docker build context.

| Contract | Operations |
| --- | --- |
| [delivery-order.yaml](../../delivery-order-api/src/main/resources/static/openapi/delivery-order.yaml) | POST /api/v1/delivery-orders; GET /api/v1/delivery-orders/{orderId} |
| [pricing-eligibility.yaml](../../pricing-eligibility-api/src/main/resources/static/openapi/pricing-eligibility.yaml) | POST /api/v1/pricing/evaluations |

## Code generation

Each service's `pom.xml` runs `openapi-generator-maven-plugin` during the build. The plugin generates code into `target/generated-sources/openapi`, which is not committed.

- Pricing generates its server interface (`PricingApi`) and models from its own contract.
- Delivery Order generates its server interface (`DeliveryOrdersApi`) and models from its own contract. A second execution generates Pricing client models from `delivery-order-api/src/main/resources/openapi-client/pricing-eligibility.yaml`. That file is Delivery Order's copy of the Pricing contract, so the services stay release-independent. When the Pricing contract changes, update this copy in the same change and review whether the Delivery Order client needs to change.
- Controllers implement the generated interfaces. The interfaces have no default methods, so every operation must be implemented.
- Swagger UI (`/swagger-ui/index.html`) shows the static contract served from `/openapi/`, not a spec derived from the code.

## Contract decisions

- Delivery Order validates syntax (400), including positive parcel weight and Dutch postal-code format; business rejection returns 422. Non-NL country codes are rejected by Delivery Order before calling Pricing, whose request follows the assignment's country-free example.
- Pricing returns HTTP 200 with mutually exclusive eligible and rejected response schemas. Weight above 20 kg and incompatible same-day routes are business rejections.
- Only confirmed deliveries have order IDs and are retrievable. Rejected and temporarily unavailable attempts are represented by the `orderStatus` extension of Problem Details; they are not confirmed orders.
- Equivalent completed submissions replay their original terminal status/body (201 and Location for success, 422 for rejection). Different content for the same key returns 409; concurrent matching requests still processing may receive 409 with code IDEMPOTENCY_IN_PROGRESS. A 503 permits reevaluation with the same key and input. Compare normalized requests, not raw JSON bytes.
- Pricing timeout, upstream server error, and invalid upstream response map to 503 without confirmation. GET uses the persisted historical price and does not call Pricing.
- Idempotency keys are opaque, case-sensitive, 1–128 printable non-space ASCII characters. UUID order/request IDs and UTC timestamps are used. Examples contain fictional labels, not real customer data.
- Pricing responses are open for additive change: clients ignore unknown properties and tolerate unknown price component types, rejection codes and problem codes. The eligible flag distinguishes eligible from rejected results.
- Retryable responses (503, and 409 with IDEMPOTENCY_IN_PROGRESS) carry a Retry-After header.
- Money is represented as a JSON number, mapped to BigDecimal in Java. Enforce at most two decimal places in application validation and monetary calculations. The schemas deliberately omit fractional multipleOf to avoid floating-point validator inconsistencies. No float/double calculations.

## Pricing assumption to confirm

The assignment's illustrative NEXT_DAY breakdown differs from its explicit tariff table. Follow the table: EUR 6.95 base + EUR 1.00 weight surcharge for 2,750 g + EUR 1.00 urban-area surcharge = EUR 8.95. No separate NEXT_DAY surcharge is added. The urban-area surcharge applies if either endpoint is in 1000AA–1099ZZ. The assignment's example rules out destination-only pricing: only the origin (1012AB) is urban there, and the total is still EUR 8.95. Assume the surcharge is charged once when both endpoints are urban; this remains to confirm with the assessor.

## Scope

These are contract-first business API definitions, not an implementation. Spring Boot Actuator management endpoints are separate; production authentication and authorization are not specified in these local assessment contracts.
