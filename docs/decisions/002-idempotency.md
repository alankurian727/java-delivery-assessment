# 002 · Database-backed idempotency

**Status** · Planned

Bind each idempotency key to a normalized request fingerprint in PostgreSQL. Replay the recorded terminal result for an equivalent request and return HTTP 409 if the same key is reused with different input. Use database uniqueness and atomic state updates to prevent concurrent requests from confirming more than one order.
