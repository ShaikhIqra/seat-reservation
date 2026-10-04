# Write-up

## 1. The atomic decision

One conditional `UPDATE` per seat decides the winner:

```sql
UPDATE seats
SET status = 'confirmed', user_id = ?, reservation_id = ?
WHERE show_id = ? AND seat_label = ? AND status = 'available';
```

- **1 row changed** → this request won. **0 rows** → seat taken (409).
- **Why it's race-free:** there's no separate "is it free?" read. Postgres locks the row during the update. A competing request waits, then re-checks `status = 'available'` on the latest row, and changes 0 rows.
- **Multi-seat is all-or-nothing:** all seats are claimed in one transaction. If any seat fails, the transaction rolls back, and nobody ever sees the partial state.
- **No deadlocks:** seats are sorted before locking, so everyone locks in the same order. Every request locks key → per-user counter → seats. Cancel follows the same order.

**Per-user limit** uses the same idea on a counter row per (show, user):

```sql
UPDATE user_show_holds SET seat_count = seat_count + ?
WHERE show_id = ? AND user_id = ? AND seat_count + ? <= ?;
```

- A plain `count(*)` check would let one user's parallel requests all pass.
- With the counter row, that user's requests queue on one row. Other users aren't slowed down.

## 2. Idempotency

- **Stored in** `idempotency_keys`, primary key `(user_id, key)`, with a fingerprint (show + sorted seats) and the `reservation_id`.
- **Exactly once:** the key is inserted first, inside the booking transaction, with `INSERT ... ON CONFLICT DO NOTHING`.
  - 1 row → first time, go ahead.
  - 0 rows → key exists. A simultaneous duplicate waits for the first to commit, then sees it.
- **Why not catch a duplicate-key error?** In Postgres, any error aborts the whole transaction.
- **Same key, same request** → original reservation, **200** (nothing new created).
- **Same key, different request** → **409** `idempotency_key_reused`. One key means one booking. Guessing intent with someone's money is unsafe.
- **Booking failed?** The rollback removes the key too, so a retry just tries again.
- **Cancelled later?** The key still returns the cancelled reservation. A late retry never re-books.
- **Retention:** kept for now. In production I'd expire keys after about 24 hours.

## 3. Holds and release

- **Chose explicit cancel** over auto-expiring holds: no background job, fewer edge cases, easier to prove correct under load.
- Reservations go straight to `confirmed`. The `held` status exists but stays 0.
- **Cancel is one atomic step:** `WHERE id = ? AND user_id = ? AND status = 'confirmed'`. Only the owner can cancel, and two cancels can't both win.
- Seats are freed only `WHERE reservation_id = <this one>`, so a cancel can **never** free someone else's seat.
- The per-user counter goes down in the same transaction. Cancelling twice changes nothing.

## 4. Consistency vs availability

- **I chose consistency.** One Postgres database is the single source of truth. The app keeps no booking state in memory, so more instances could run side by side.
- **No database → no bookings.** Turning users away is better than selling a seat twice.
- **Readiness** checks the database and returns 503 when it's down. **Liveness** doesn't, so the app isn't restarted in a loop during a DB outage.
- **Known gap:** during a DB outage, a reserve request fails with a 5xx after the pool's 30-second timeout. Next step: a shorter timeout and a fast 503 with `Retry-After`.

## 5. Observability: what pages me at 2am

**Metrics** (`/actuator/prometheus`, tagged by `show_id`): confirmed, declined by reason, cancelled, and seats by status.
- Counted **after** the transaction commits, so they always match the database.
- The seat gauge reads the database every 5 seconds, so it can lag by up to 5 seconds.

**Logs:** JSON, one line per request: `request_id`, `user_id`, `status`, `duration_ms`, `outcome`.
- Why one line? The platform caps log lines per second, and extra lines get dropped during a burst.

**Page me for:**
- Any 5xx on `/reserve` (declines should always be 4xx)
- Readiness failing
- Counts drifting between `seats`, `reservations` and `user_show_holds` (not automated yet)
- Database connection pool waits climbing
- p99 latency spiking

**Don't page for:** lots of `seat_taken`. During an on-sale, that's the system working.

## 6. Testing: what I tried and what I found

`./burst.sh <BASE_URL>` runs three scenarios at once: a hot-seat storm, one user firing 10 parallel requests (limit 4), and 20 same-key retries.

| Hot-seat users | Confirmed | seat_taken | per_user_limit | Replays | 5xx | Invariant |
|---|---|---|---|---|---|---|
| 500 | 6 | 499 | 6 | 19 | 0 | ✅ |
| 2,000 | 6 | 1,999 | 6 | 19 | 0 | ✅ |
| 5,000 | 6 | 4,999 | 6 | 19 | 0 | ✅ |

**Surprises along the way:**
- **Errors only on my laptop?** Hundreds of "connection refused" locally. Ran the same test inside Docker's network: zero errors. The cause was Windows Docker port forwarding, not the app.
- **409s I didn't expect:** my test script reused idempotency keys across runs. The app was right; the script was wrong. Fixed with unique keys per run.
- **401s at 5,000 users:** 176 token requests failed during setup. Smaller batches with retries fixed it, and the script now fails loudly instead of hiding it.
- **The app got killed mid-burst:** memory hit 931 of 1000 MB at 2,000 users. Java's percentage-based heap left no room for threads. Fixed with a fixed 400 MB heap, smaller thread stacks, and 100 threads instead of 200. Reran 2,000 clean.

**Still open:** the 5,000 run was before the memory fix. Slowest requests reached about 18 seconds, partly my laptop. Not yet tested at 20,000. No automated test suite yet.

## 7. AI usage

I used Claude as a tutor and pair programmer.

**AI:**
- Explained each concept through questions I had to answer: race conditions, transactions, locks, deadlocks, idempotency, the per-user limit race.
- Drafted most of the code, the k6 script, and first drafts of the README and this write-up.
- Helped debug setup issues (Java, Docker, YAML, a wrong dependency, a time zone error).

**Me:**
- When something didn't click, I kept asking until it did, and made my own notes per topic: problem, key idea, code, my mistakes, how I'd explain it.
- Chose Spring Boot + Postgres. Reviewed and accepted the main design choices: plain SQL over JPA, cancel over expiry, all-or-nothing, package-by-feature.
- Questioned things before accepting them:
  - Why reject a reused key instead of treating it as a new booking?
  - Should keys be deleted after use?
  - Is a manual undo really worse than a rollback?
  - Why did a missing seat come back as "seat taken"? (It was the idempotency check.)
- Typed and integrated the code, fixed compile errors, and ran every test.
- Spotted a load test run against a half-started server, and confirmed the memory cause from Railway's chart before changing anything.
- Deployed on my own personal Railway account.

## 8. What I'd do next

1. Integration tests with real Postgres (Testcontainers) for every race case
2. Fast 503 + `Retry-After` when the database is slow or down
3. An automated reconciliation check and alert
4. Timed holds (hold → pay → confirm) for a real checkout
5. Rate limiting per user and IP
6. Expire idempotency keys after about 24 hours
7. Load test at 20,000+ from distributed machines, then tune from the results
8. A real identity provider instead of the demo token endpoint
