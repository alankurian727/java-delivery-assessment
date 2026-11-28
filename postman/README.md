# Postman collections

| File | Contents |
| --- | --- |
| `delivery-order-api.postman_collection.json` | Create and retrieve orders, business rejections, invalid requests, retrieval errors, health |
| `pricing-eligibility-api.postman_collection.json` | Eligible evaluations, business rejections, invalid requests, health |
| `local.postman_environment.json` | Environment **Local**: `deliveryOrderBaseUrl` (`http://localhost:8080`), `pricingBaseUrl` (`http://localhost:8081`), `correlationId` |

**Use:** import the three files in Postman, select the **Local** environment, start the stack
(`docker compose up --build --wait`), and run a collection with the Collection Runner.

- Every request has tests for the expected status code and body.
- In the Delivery Order collection, folder **1. Create and retrieve** runs in order: "Create order" generates a new
  `idempotencyKey` and stores the created `orderId` in collection variables, which the next requests reuse. The other
  folders generate a new key per request.
- Pricing requests use a new `requestId` (`{{$guid}}`) each time.
- All test data is fictional.

To run from the command line with Newman:

```sh
npx newman run postman/delivery-order-api.postman_collection.json -e postman/local.postman_environment.json
```
