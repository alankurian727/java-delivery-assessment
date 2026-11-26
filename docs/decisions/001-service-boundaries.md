# 001 · Separate services and historical prices

**Status** · Planned

Pricing owns eligibility and tariff rules. Delivery Order owns persistence and stores the confirmed amount, currency, and timestamp. Reading an order uses the stored snapshot so tariff changes cannot alter historical amounts. Each service builds and deploys independently. Compatibility across the HTTP boundary is covered by the planned contract tests.
