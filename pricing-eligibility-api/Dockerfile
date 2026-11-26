# Build from the repository root so the shared contracts in docs/openapi are available:
#   docker build -f pricing-eligibility-api/Dockerfile .
FROM eclipse-temurin:21-jdk AS build
# The Maven wrapper needs unzip: without it, it downloads the .tar.gz distribution, which does not
# match distributionSha256Sum (the checksum of the .zip) in .mvn/wrapper/maven-wrapper.properties.
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app/pricing-eligibility-api
COPY docs/openapi/ /app/docs/openapi/
COPY pricing-eligibility-api/.mvn/ .mvn/
COPY --chmod=755 pricing-eligibility-api/mvnw mvnw
COPY pricing-eligibility-api/pom.xml .
COPY pricing-eligibility-api/src/ src/
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/pricing-eligibility-api/target/*.jar app.jar
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
