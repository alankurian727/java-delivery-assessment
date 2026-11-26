# Architecture

Delivery Order and Pricing & Eligibility are separate Java 21 and Spring Boot applications. Each has its own Maven wrapper, POM, and Dockerfile. The root Compose file starts both services and PostgreSQL.

```mermaid
flowchart LR
    Client -->|HTTP :8080| Order[Delivery Order API]
    Order -->|HTTP :8081| Pricing[Pricing & Eligibility API]
    Order -->|JDBC :5432| DB[(PostgreSQL)]
```

## Planned request flow

Delivery Order validates the request and binds its idempotency key. It calls Pricing over HTTP outside a database transaction. Pricing evaluates eligibility and calculates a quote. Delivery Order stores the confirmed price and currency with the order. Later reads return that stored snapshot, including when Pricing is unavailable.

Pricing is stateless. Delivery Order owns the database schema and migrations.

The Maven builds, Compose wiring, and Actuator endpoints are in place. The request flow above describes the planned business implementation.

See [decision records](decisions/), [API contracts](openapi/), and [deployment improvements](improvements/).
