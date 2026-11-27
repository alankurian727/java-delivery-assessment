# Deployment improvements

The repository runs locally with Docker Compose. This page lists the configuration both services read and what to
change before deploying them to a shared or production environment.

## Configuration

### Delivery Order

| Variable | Local Maven run | Docker Compose | Deployed environment |
| --- | --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `local` (default) | `local` (default) | `prod` (ECS JSON logs, INFO) or `dev` |
| `DELIVERY_DB_URL` | `jdbc:postgresql://localhost:5432/delivery` | `jdbc:postgresql://postgres:5432/delivery` | Per environment |
| `DELIVERY_DB_USER` | `delivery` | `delivery` | Runtime user with data access only (see below) |
| `DELIVERY_DB_PASSWORD` | Required | Defaults to `delivery-dev` | Required, from a secret manager |
| `PRICING_BASE_URL` | `http://localhost:8081` | `http://pricing-eligibility-api:8081` | Internal Pricing address |

The Pricing client settings in `application.yml` (`delivery.pricing.*`: timeouts, retry attempts, circuit breaker) can
be overridden per environment through Spring's environment variables, for example
`DELIVERY_PRICING_READ_TIMEOUT=2s`.

### Pricing & Eligibility

Pricing needs no database or secrets. It reads `SPRING_PROFILES_ACTIVE` and its tariff from `application.yml`
(`pricing.tariff.*`).

## Before deploying

### Secrets

- Remove the hardcoded `delivery-dev` fallback from Compose for anything beyond a developer machine.
- Inject `DELIVERY_DB_PASSWORD` from the platform's secret manager and rotate it independently of releases. Never put
  secrets in `application*.yml`, images or the repository.

### Database

- Use **two database users**: one that owns the schema and runs Flyway migrations, and a runtime user for the
  application with only `SELECT`/`INSERT` (and `DELETE` for the retention job) on its tables.
- Size connections across instances: each Delivery Order instance uses a HikariCP pool of 10, so
  `instances × 10` (plus the migration user and admin connections) must stay below PostgreSQL's `max_connections`.
- Run migrations once per release (one instance or a separate job), not concurrently from every instance at startup.
- Set up backups with regular restore tests, and alerts for availability, connection usage and storage.
- Add the clean-up described in [idempotency record retention](idempotency-record-retention.md).

### Network and security

- Terminate TLS in front of Delivery Order; Pricing and PostgreSQL are reachable only from inside the network.
- **Clients:** authenticate with OAuth2/OIDC access tokens (Spring Security resource server, JWT validation) and
  authorise per client. Once clients are known, scope idempotency keys per client (ADR-009).
- **Service to service:** Delivery Order calls Pricing with mTLS or OAuth2 client credentials.
- Serve Actuator on a separate management port, not exposed publicly; keep `health` only on the public path if a load
  balancer needs it.

### Running the containers

- Both images run as a non-root user (`10001`). Set CPU and memory limits; the JVM sizes its heap from the container
  memory limit.
- Use Actuator health groups for liveness and readiness probes
  (`management.endpoint.health.probes.enabled=true`), so an instance only receives traffic once the database is
  reachable.
- Delivery Order is stateless: scale it horizontally behind a load balancer. Idempotency and race handling rely on
  PostgreSQL, not on instance affinity (ADR-008, ADR-009).
- Pricing protects its own capacity under load (Tomcat `server.tomcat.threads.max` / `accept-count`, or rate limiting
  at a gateway), returning 503 when overloaded (ADR-007).

### Observability

- Use the `prod` profile so logs are ECS JSON with `correlationId` and the `event` fields, and ship them to a central
  log store.
- Alert on Pricing failures (`pricing.call_failed`, `pricing.circuit_state_changed`), Pricing latency, 5xx rates and
  database availability.
- Add distributed tracing (Micrometer Tracing with OpenTelemetry, W3C `traceparent`) in both services, alongside the
  existing `X-Correlation-ID`.
