# OpenAPI contracts

Both standalone contracts use OpenAPI 3.0.3.

| Contract | Operations |
| --- | --- |
| [delivery-order.yaml](delivery-order.yaml) | POST /api/v1/delivery-orders; GET /api/v1/delivery-orders/{orderId} |
| [pricing-eligibility.yaml](pricing-eligibility.yaml) | POST /api/v1/pricing/evaluations |

## Contract decisions

- Delivery Order validates syntax (400), including positive parcel weight and Dutch postal-code format; business rejection returns 422. Non-NL country codes are rejected by Delivery Order before calling Pricing, whose request follows the assignment's country-free example.
- Pricing returns HTTP 200 with mutually exclusive eligible and rejected response schemas. Weight above 20 kg and incompatible same-day routes are business rejections.
- Only confirmed deliveries have order IDs and are retrievable. Rejected and temporarily unavailable attempts are represented by the `orderStatus` extension of Problem Details; they are not confirmed orders.
- Equivalent completed submissions replay their original terminal status/body (201 and Location for success, 422 for rejection). Different content for the same key returns 409; concurrent matching requests still processing may receive 409 with code IDEMPOTENCY_IN_PROGRESS. A 503 permits reevaluation with the same key and input. Compare normalized requests, not raw JSON bytes.
- Pricing timeout, upstream server error, and invalid upstream response map to 503 without confirmation. GET uses the persisted historical price and does not call Pricing.
- Idempotency keys are opaque, case-sensitive, 1–128 printable non-space ASCII characters. UUID order/request IDs and UTC timestamps are used. Examples contain fictional labels, not real customer data.
- Money is represented as a JSON number with cent precision, mapped to BigDecimal in Java. No float/double calculations.

## Pricing assumption to confirm

The assignment's illustrative NEXT_DAY breakdown differs from its explicit tariff table. Follow the table: EUR 6.95 base + EUR 1.00 weight surcharge for 2,750 g + EUR 1.00 urban-area surcharge = EUR 8.95. No separate NEXT_DAY surcharge is added. Assume the urban-area surcharge is charged once if either endpoint is in the specified postal-code range; clarify with the assessor if destination-only pricing is intended.

## Scope

These are contract-first business API definitions, not an implementation. Spring Boot Actuator management endpoints are separate; production authentication and authorization are not specified in these local assessment contracts.
