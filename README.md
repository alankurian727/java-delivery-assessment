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

## Example requests

With the stack running, these `curl` commands call the Pricing & Eligibility API.
[`http/pricing-eligibility.http`](http/pricing-eligibility.http) covers every
scenario and runs from IntelliJ IDEA or VS Code with the REST Client extension.

Eligible delivery, the assignment's example (expect HTTP 200 and a price of 8.95):

```sh
curl -s http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: example-1' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"1012 AB","destinationPostalCode":"5611EM","weightInGrams":2750,"deliveryType":"NEXT_DAY"}'
```

Business rejection (expect HTTP 200 with `"eligible":false` and `WEIGHT_LIMIT_EXCEEDED`):

```sh
curl -s http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"5611EM","destinationPostalCode":"3011AB","weightInGrams":20001,"deliveryType":"STANDARD"}'
```

Same-day delivery between different areas (expect HTTP 200 with `SAME_DAY_NOT_SUPPORTED`):

```sh
curl -s http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"5611EM","destinationPostalCode":"1012AB","weightInGrams":100,"deliveryType":"SAME_DAY"}'
```

Invalid request (expect HTTP 400 Problem Details with `VALIDATION_FAILED`):

```sh
curl -s http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"0123AB","destinationPostalCode":"5611EM","weightInGrams":0,"deliveryType":"NEXT_DAY"}'
```

## Build and run with Maven

Each service has its own Maven wrapper. A local Maven installation is optional.

From the repository root, build both services with JDK 21 using the root
aggregator POM and the root Maven wrapper:

```sh
./mvnw clean verify
```

The root POM and wrapper provide a convenience build for local development.
Each service owns its POM, produces a separate executable JAR, and has its own
Dockerfile. For production, build, version, and deploy each service's artifact
separately. Configure PostgreSQL and the Pricing endpoint for Delivery Order in
the deployment environment.

To build either service independently, point its wrapper at its own POM:

```sh
./delivery-order-api/mvnw -f delivery-order-api/pom.xml clean verify
./pricing-eligibility-api/mvnw -f pricing-eligibility-api/pom.xml clean verify
```

Both services generate their API interfaces and models from the shared
OpenAPI contracts in [`docs/openapi/`](docs/openapi/), so a service build needs
that folder from the same checkout. To build a single service image, run
`docker build` from the repository root:

```sh
docker build -f delivery-order-api/Dockerfile -t delivery-order-api .
docker build -f pricing-eligibility-api/Dockerfile -t pricing-eligibility-api .
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
