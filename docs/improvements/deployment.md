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
