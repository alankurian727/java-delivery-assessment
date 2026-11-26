# Pricing & Eligibility API: example requests

`curl` commands for every scenario of `POST /api/v1/pricing/evaluations`. Start the
service first, for example with `docker compose up --build --wait`, then copy any
command into a terminal. `-i` prints the status line and headers before the body.

Business rejections return HTTP 200 with `"eligible": false`; only invalid requests
return HTTP 400.

## Health
### Health check

```sh
curl -s http://localhost:8081/actuator/health
```

Expected: HTTP 200, `{"status":"UP"}`.

## Eligible: HTTP 200, `"eligible": true`
### Assignment example: NEXT_DAY 2,750 g from an urban area

```sh
curl -s -i http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: example-eligible-1' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"1012 AB","destinationPostalCode":"5611EM","weightInGrams":2750,"deliveryType":"NEXT_DAY"}'
```

Expected: HTTP 200, price **8.95** = `BASE_PRICE` 6.95 + `WEIGHT_SURCHARGE` 1.00 + `URBAN_AREA_SURCHARGE` 1.00. The response header `X-Correlation-ID` is `example-eligible-1`.
### STANDARD 2,000 g outside the urban area

```sh
curl -s -i http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"5611EM","destinationPostalCode":"3011AB","weightInGrams":2000,"deliveryType":"STANDARD"}'
```

Expected: HTTP 200, price **4.95**: base price only, no weight surcharge.
### STANDARD 20,000 g, the heaviest eligible parcel

```sh
curl -s -i http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"5611EM","destinationPostalCode":"3011AB","weightInGrams":20000,"deliveryType":"STANDARD"}'
```

Expected: HTTP 200, price **9.95** = 4.95 base + 5.00 weight.
### Both postal codes urban

```sh
curl -s -i http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"1012AB","destinationPostalCode":"1099ZZ","weightInGrams":500,"deliveryType":"STANDARD"}'
```

Expected: HTTP 200, price **5.95**: the urban surcharge is charged once.
### SAME_DAY within the same four-digit area, 15,000 g

```sh
curl -s -i http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"1012AB","destinationPostalCode":"1012XY","weightInGrams":15000,"deliveryType":"SAME_DAY"}'
```

Expected: HTTP 200, price **17.95** = 11.95 base + 5.00 weight + 1.00 urban.

## Business rejections: HTTP 200, `"eligible": false`
### Weight above 20,000 g

```sh
curl -s -i http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"5611EM","destinationPostalCode":"3011AB","weightInGrams":20001,"deliveryType":"STANDARD"}'
```

Expected: HTTP 200, `rejectionCode` `WEIGHT_LIMIT_EXCEEDED`.
### SAME_DAY between different four-digit areas

```sh
curl -s -i http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"5611EM","destinationPostalCode":"1012AB","weightInGrams":100,"deliveryType":"SAME_DAY"}'
```

Expected: HTTP 200, `rejectionCode` `SAME_DAY_NOT_SUPPORTED`.
### Both rules fail

```sh
curl -s -i http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"5611EM","destinationPostalCode":"1012AB","weightInGrams":25000,"deliveryType":"SAME_DAY"}'
```

Expected: HTTP 200, `rejectionCode` `WEIGHT_LIMIT_EXCEEDED`: the weight rule is checked first.

## Invalid requests: HTTP 400, `application/problem+json`, code `VALIDATION_FAILED`
### Missing field

```sh
curl -s -i http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: example-invalid-1' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"1012AB","weightInGrams":2750,"deliveryType":"NEXT_DAY"}'
```

Expected: HTTP 400 with a violation for `destinationPostalCode`; `correlationId` is `example-invalid-1`.
### Invalid Dutch postal code

```sh
curl -s -i http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"0123AB","destinationPostalCode":"5611EM","weightInGrams":2750,"deliveryType":"NEXT_DAY"}'
```

Expected: HTTP 400 with a violation for `originPostalCode`. The rejected value is not echoed.
### Zero weight

```sh
curl -s -i http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"1012AB","destinationPostalCode":"5611EM","weightInGrams":0,"deliveryType":"NEXT_DAY"}'
```

Expected: HTTP 400 with a violation for `weightInGrams`.
### Negative weight

```sh
curl -s -i http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"1012AB","destinationPostalCode":"5611EM","weightInGrams":-1,"deliveryType":"NEXT_DAY"}'
```

Expected: HTTP 400 with a violation for `weightInGrams`.
### Unsupported delivery type

```sh
curl -s -i http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"1012AB","destinationPostalCode":"5611EM","weightInGrams":2750,"deliveryType":"EXPRESS"}'
```

Expected: HTTP 400; the response does not echo `EXPRESS`.
### Malformed JSON

```sh
curl -s -i http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"1012AB"'
```

Expected: HTTP 400.
### X-Correlation-ID longer than 128 characters

```sh
curl -s -i http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx' \
  -d '{"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"1012AB","destinationPostalCode":"5611EM","weightInGrams":2750,"deliveryType":"NEXT_DAY"}'
```

Expected: HTTP 400 with a violation for `X-Correlation-ID`.

## Unsupported requests: code `REQUEST_NOT_SUPPORTED`
### Wrong HTTP method

```sh
curl -s -i http://localhost:8081/api/v1/pricing/evaluations
```

Expected: HTTP 405.
### Wrong content type

```sh
curl -s -i http://localhost:8081/api/v1/pricing/evaluations \
  -H 'Content-Type: text/plain' \
  -d 'not json'
```

Expected: HTTP 415.
