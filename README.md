# Java Delivery Assessment

Two independently buildable Java 21 and Spring Boot services.

| Service | Port | Responsibility |
| --- | --- | --- |
| `delivery-order-api` | 8080 | Delivery order HTTP API, PostgreSQL persistence, and pricing client |
| `pricing-eligibility-api` | 8081 | Stateless pricing and eligibility evaluation |

Each service has its own Maven wrapper. A local Maven installation is optional.

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
