# Idempotency record retention

Status: not implemented. Production improvement for Delivery Order.

## Problem

Every decided request writes one row to `idempotency_record` (ADR-008), and nothing deletes it. The table therefore
grows for as long as the service runs. This covers all outcomes; rejections are only a fraction of the rows.

A record is only needed while a client may still retry the same request. After that it has no purpose: the order, if
any, lives on in `delivery_order`.

## Estimated growth

Rough estimates, not measurements:

| Table | Approximate size per row, including the primary-key index |
| --- | --- |
| `idempotency_record` | 250–300 bytes |
| `delivery_order` | 400–500 bytes |

At the assignment's normal load of 25 requests per second, sustained around the clock (a worst case):

- about 2.2 million requests per day;
- about 0.6 GB of idempotency records per day, or roughly 200 GB per year without clean-up.

Real traffic is not flat, so actual growth is lower, but the table needs a retention limit.

## Proposal

1. **Retention period:** keep idempotency records for 7 days (24 hours is also common). Document it in the API
   description: an `Idempotency-Key` is guaranteed to return the stored outcome for that period; after it, the key may
   be evaluated as a new request.
2. **Index for the clean-up:** add a Flyway migration:

   ```sql
   CREATE INDEX idempotency_record_created_at ON idempotency_record (created_at);
   ```

3. **Scheduled clean-up in small batches**, so it never holds long locks or produces large transactions:

   ```sql
   DELETE FROM idempotency_record
    WHERE idempotency_key IN (
          SELECT idempotency_key FROM idempotency_record
           WHERE created_at < now() - interval '7 days'
           LIMIT 10000);
   ```

   Repeat until no rows are deleted. Run it from one place only: a Spring `@Scheduled` job guarded by a lock such as
   ShedLock, or a database-side job (for example `pg_cron`). Make the period configurable
   (for example `delivery.idempotency.retention: 7d`).
4. **Metrics:** log or expose the number of deleted rows and the table size, and alert if the table keeps growing.

With a 7-day period the table stays bounded at about 15 million rows (4–5 GB) in the worst case above. Lookups stay
fast because they always use the primary key.

## Why deleting records is safe

- The foreign key points from `idempotency_record.order_id` to `delivery_order`. Deleting a record never deletes or
  changes an order; an order without a record is still a valid confirmed order.
- After deletion, the same key with the same request is treated as new and evaluated again. With the original record
  gone, a retry after the retention period can therefore create a second order. The retention period must be longer
  than any client's retry window.

## Related

- **Orders** are business data and follow the GDPR retention rules (README), not this period. Deleting or anonymising
  an order must happen after its idempotency record is deleted, because the foreign key prevents deleting an order
  that a record still references.
- **Rejections are final for their key** during the retention period: the same request with the same key returns the
  same 422. A corrected or retried order uses a new key.
- See ADR-008 (record the outcome once) and ADR-009 (race conditions).
