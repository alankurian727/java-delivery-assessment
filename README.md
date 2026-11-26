# Java Delivery Assessment

Two independently buildable Java 21 and Spring Boot services.

| Service | Port | Responsibility |
| --- | --- | --- |
| `delivery-order-api` | 8080 | Delivery order HTTP API, PostgreSQL persistence, and pricing client |
| `pricing-eligibility-api` | 8081 | Stateless pricing and eligibility evaluation |

## Run with Docker Compose

With Docker running, start the stack from the repository root and check both APIs. Compose uses `delivery-dev` as the local PostgreSQL password.

```sh
docker compose up --build --wait
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8081/actuator/health
```

Both health endpoints should return `{"status":"UP"}`. Compose builds both
services with their Maven wrappers and starts PostgreSQL on the Compose network.

Use `docker compose logs -f` to view logs and `docker compose down` to stop the
stack. PostgreSQL data persists in the named volume.

For an optional local override, set `DELIVERY_DB_PASSWORD` in the shell or in a root `.env` file before the first startup. An existing database keeps its current password. See [deployment improvements](docs/improvements/) for production configuration.

## Build and run with Maven

Each service has its own Maven wrapper. A local Maven installation is optional.

From the repository root, build both services with JDK 21 using the root
aggregator POM and the Delivery Order wrapper:

```sh
./delivery-order-api/mvnw -f pom.xml clean verify
```

To build either service independently, point its wrapper at its own POM:

```sh
./delivery-order-api/mvnw -f delivery-order-api/pom.xml clean verify
./pricing-eligibility-api/mvnw -f pricing-eligibility-api/pom.xml clean verify
```

Run Pricing with `cd pricing-eligibility-api && ./mvnw spring-boot:run`.
Its health endpoint is `http://localhost:8081/actuator/health`.

Delivery Order requires a running PostgreSQL instance. It defaults to
`jdbc:postgresql://localhost:5432/delivery` with the user `delivery`. Set
`DELIVERY_DB_PASSWORD` and override `DELIVERY_DB_URL` or `DELIVERY_DB_USER`
as needed before running `cd delivery-order-api && ./mvnw spring-boot:run`.
Its health endpoint is `http://localhost:8080/actuator/health`.
Set `PRICING_BASE_URL` to override the default `http://localhost:8081`.

This repository currently contains the build and application scaffolding.
