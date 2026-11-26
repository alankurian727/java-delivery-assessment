# Deployment improvements

## Environment properties

Delivery Order reads these variables at startup. For local Compose, set `DELIVERY_DB_PASSWORD` in the root `.env` file or export it in the shell. Compose passes the same password to Delivery Order and PostgreSQL.

| Variable | Local Maven run | Docker Compose |
| --- | --- | --- |
| `DELIVERY_DB_URL` | Defaults to `jdbc:postgresql://localhost:5432/delivery` | `jdbc:postgresql://postgres:5432/delivery` |
| `DELIVERY_DB_USER` | Defaults to `delivery` | `delivery` |
| `DELIVERY_DB_PASSWORD` | Required | Required through `.env` or the shell |
| `PRICING_BASE_URL` | Defaults to `http://localhost:8081` | `http://pricing-eligibility-api:8081` |

Pricing needs no database credentials. The example values above describe local development.

## What to change for a deployed environment

- Inject database credentials through the deployment platform's secret manager and rotate them separately from application releases. Configure the JDBC URL and Pricing endpoint for each environment.
- Restrict network access to Pricing, PostgreSQL, and Actuator management endpoints. Use authenticated, encrypted connections where required by the deployment.
- Define database backups and restore testing, along with alerts for pricing failures, latency, and database availability.
