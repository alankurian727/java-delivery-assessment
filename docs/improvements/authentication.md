# Production authentication and authorisation

Proposal for production; not implemented in the assessment.

## Today

Neither service checks who is calling. That is fine for the local assessment, but not for production.

## Approach: OAuth 2.0 with JWT access tokens

An identity provider (for example Keycloak, Microsoft Entra ID or Okta) registers every caller as an OAuth client and
issues short-lived, signed JWT access tokens (valid 5–15 minutes). Every request carries one in the
`Authorization: Bearer <token>` header.

Both services validate tokens themselves with the identity provider's public keys, which they download once and cache.
No call to the identity provider is needed per request, and any instance can handle any request.

```text
Client ─(client ID + secret)─▶ Identity provider ─▶ JWT (aud: delivery-order-api)
Client ─(Bearer JWT)─────────▶ Delivery Order
Delivery Order ─(own client ID + secret)─▶ Identity provider ─▶ JWT (aud: pricing-api)
Delivery Order ─(Bearer JWT)─▶ Pricing
```

## How a JWT works

A JWT (JSON Web Token, RFC 7519) is a string of three Base64URL-encoded parts, `header.payload.signature`. The header
names the signing algorithm and key, the payload holds the claims (below), and the signature is made with the identity
provider's private key. Services verify it with the matching public key, so any change to the token makes it invalid.

A JWT is encoded, not encrypted: anyone holding it can read it, so it never contains personal data. It is also a
credential until it expires, so it is short-lived, sent only over TLS, and never logged.

### What a token contains

```json
{
  "iss": "https://login.example-parcels.nl/realms/delivery",
  "sub": "customer-system-abn",
  "aud": "delivery-order-api",
  "scope": "delivery-orders:create delivery-orders:read",
  "exp": 1795776600
}
```

| Claim | Name | Checked for |
| --- | --- | --- |
| `iss` | Issuer | Issued by our identity provider |
| `sub` | Subject | Who the caller is; stored as the order's owner |
| `aud` | Audience | Issued for this API (a token for Pricing cannot be used on Delivery Order, and the other way round) |
| `scope` | Scope | Allowed to call this endpoint |
| `exp` | Expiry | Not expired; a Unix timestamp (seconds since 1 January 1970, UTC), typically 5–15 minutes after issue |

Real tokens usually also carry `iat` (issued at) and `jti` (a unique token ID), which help with auditing.

The token holds only technical identifiers, no personal data. A token whose signature or claims fail any check is
rejected.

## Clients → Delivery Order

- A client system gets a token with the OAuth 2.0 **client credentials** grant and sends it with every request.
- Delivery Order is a Spring Security **resource server**: it checks signature, issuer, audience, expiry and scope
  before the controller runs.
- **Authorisation** with scopes and ownership:

| Endpoint | Required scope | Extra rule |
| --- | --- | --- |
| `POST /api/v1/delivery-orders` | `delivery-orders:create` | The order is stored with the client ID (`sub`) from the token |
| `GET /api/v1/delivery-orders/{orderId}` | `delivery-orders:read` | A client can only read its own orders; others get 404, so they cannot learn that the order exists |
| `/actuator/health` | none | Must stay open for load-balancer and platform health checks, which send no token |
| `/actuator/info`, `/actuator/metrics` | `operations` | Not public: they reveal internal details; alternatively served on a separate internal port |

- Missing, expired or invalid token: **401** with `WWW-Authenticate: Bearer`. Valid token without the scope: **403**.
  Both as Problem Details, without details about the token.

## Delivery Order → Pricing (service to service)

Delivery Order is itself a registered OAuth client, with its own client ID and secret, and only the scope
`pricing:evaluate`. No user is involved, so it also uses the **client credentials** grant.

1. **Get a token.** Delivery Order asks the identity provider for a token with audience `pricing-api` and scope
   `pricing:evaluate`.
2. **Cache it.** The token is reused for every Pricing call until shortly before it expires, then a new one is
   requested. So the identity provider is called once every few minutes per instance, not once per order.
3. **Send it.** `PricingClient` sends `Authorization: Bearer <token>` with each call, added by an interceptor on the
   existing `RestClient` (Spring's `OAuth2ClientHttpRequestInterceptor`).
4. **Pricing validates it** exactly like Delivery Order validates client tokens: signature, issuer, audience
   `pricing-api`, expiry, and scope `pricing:evaluate`. Any other caller, including a client token meant for Delivery
   Order, gets 401 or 403.

**Failure handling:**

| Situation | Result |
| --- | --- |
| Pricing rejects the token (401) | Delivery Order requests a new token and retries once; if it still fails, the order gets 503 `PRICING_UNAVAILABLE` and nothing is stored |
| Identity provider unreachable when a token is needed | No token, no Pricing call: 503 `PRICING_UNAVAILABLE`, nothing stored (no false confirmation) |
| A token is still cached | Pricing calls keep working while the identity provider is briefly down |

**Why not only network rules or a shared secret:** being inside the network should not be enough to call Pricing. A
token proves which service is calling, limits it to one scope, expires on its own, and can be revoked by disabling the
client at the identity provider.

### Alternative: token exchange (RFC 8693)

Instead of its own token, Delivery Order can exchange the client's incoming token at the identity provider for a new
token for Pricing, issued **on behalf of** that client:

```text
Delivery Order ─(client's token + own client ID + secret)─▶ Identity provider ─▶ JWT (aud: pricing-api, sub: customer-system-abn)
Delivery Order ─(Bearer JWT)─▶ Pricing
```

- **Use it when** Pricing needs to know which client is behind a request, for example for client-specific prices or
  auditing per client in Pricing.
- **Delivery Order still needs its own credential**, because it has to authenticate to the identity provider to do the
  exchange.
- **Costs:** one exchange per incoming client token instead of one cached token for all calls, and the identity
  provider must support token exchange and be configured for it.
- Simply forwarding the client's token to Pricing is **not** an option: client tokens would then have to be valid for
  Pricing too, so clients could call Pricing directly.

Today Pricing prices every request the same way, regardless of the client, so Delivery Order's own token (above) is the
simpler choice.

## Example security configuration

Sketches of what each service would add. Spring turns each scope in the token into an authority with the prefix
`SCOPE_`, so `scope: "delivery-orders:create"` becomes `SCOPE_delivery-orders:create`.

### Delivery Order

```java
@Configuration
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/delivery-orders")
                                .hasAuthority("SCOPE_delivery-orders:create")
                        .requestMatchers(HttpMethod.GET, "/api/v1/delivery-orders/*")
                                .hasAuthority("SCOPE_delivery-orders:read")
                        .requestMatchers("/actuator/health").permitAll()          // platform health checks
                        .requestMatchers("/actuator/**").hasAuthority("SCOPE_operations")
                        .anyRequest().denyAll())                                 // everything else is closed
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())) // validate the Bearer JWT
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)                           // tokens in headers, no cookies
                .build();
    }
}
```

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${AUTH_ISSUER_URI}      # public keys are found from the issuer
          audiences: delivery-order-api       # reject tokens issued for another API
```

For the call to Pricing, Delivery Order is also an OAuth client:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          pricing:
            provider: identity-provider
            client-id: delivery-order-api
            client-secret: ${PRICING_CLIENT_SECRET}   # from the secret manager
            authorization-grant-type: client_credentials
            scope: pricing:evaluate
        provider:
          identity-provider:
            issuer-uri: ${AUTH_ISSUER_URI}
```

```java
// In PricingClientConfiguration: adds "Authorization: Bearer <token>" to every Pricing call.
// The manager requests a token with client credentials and reuses it until it nearly expires.
OAuth2ClientHttpRequestInterceptor tokenInterceptor = new OAuth2ClientHttpRequestInterceptor(authorizedClientManager);
tokenInterceptor.setClientRegistrationIdResolver(request -> "pricing");

return builder
        .baseUrl(properties.baseUrl().toString())
        .requestInterceptor(tokenInterceptor)
        ...
        .build();
```

The token for Pricing belongs to Delivery Order itself, not to the client that placed the order, so the
authorized-client manager should store it for one fixed principal and share it across all requests.

### Pricing

```java
@Configuration
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/pricing/evaluations")
                                .hasAuthority("SCOPE_pricing:evaluate")          // only Delivery Order has this scope
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").hasAuthority("SCOPE_operations")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }
}
```

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${AUTH_ISSUER_URI}
          audiences: pricing-api              # a client token for Delivery Order is rejected here
```

In both services, a custom `AuthenticationEntryPoint` (401) and `AccessDeniedHandler` (403) would write the responses
in the existing Problem Details format.

## What would change

- **Dependencies:** `spring-boot-starter-oauth2-resource-server` in both services; `spring-boot-starter-oauth2-client`
  in Delivery Order.
- **Configuration per environment:** the issuer URI and expected audience in both services; Delivery Order's client ID
  and secret for calling Pricing, with the secret in a secret manager, never in the repository.
- **Security configuration:** per-endpoint scope rules, no server-side sessions, 401 and 403 as Problem Details.
- **Data:** a `client_id` column on `delivery_order`, and idempotency keys unique per client:
  `(client_id, idempotency_key)`. Two clients using the same key no longer share it.
- **Logging:** never log tokens or the `Authorization` header; log the client ID next to the correlation and trace IDs.
- **Tests:** Spring Security's test support creates tokens in tests, so 401, 403 and ownership can be tested without a
  real identity provider.
