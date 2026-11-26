# 004 · Distinguish eligibility from validation

**Status** · Planned

Malformed Order requests return HTTP 400. A valid request that fails delivery eligibility returns HTTP 422 and records the rejection against the idempotency attempt. Pricing returns a typed eligible or rejected result for a valid evaluation request. Only an eligible request can produce a confirmed order.
