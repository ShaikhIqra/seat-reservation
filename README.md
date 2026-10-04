# Seat Reservation at Scale

A JSON HTTP API that sells assigned seats for an event and stays correct under heavy concurrent load: no seat is ever sold twice, no user exceeds their booking limit, and retried requests never book twice.

**Stack:** Java 21, Spring Boot, PostgreSQL, plain SQL via `JdbcTemplate`, Flyway, Spring Security (JWT), Micrometer/Prometheus, k6.

**Live URL:** https://seat-reservation-production-dca1.up.railway.app

Design decisions and trade-offs are in [WRITEUP.md](WRITEUP.md).

---

## Run locally

Requires Docker.

```bash
git clone https://github.com/ShaikhIqra/seat-reservation.git
cd seat-reservation
docker compose up --build
```

The app starts on `http://localhost:8080` once Postgres is healthy. Flyway creates the tables on startup.

Check it's up:

```bash
curl http://localhost:8080/actuator/health/readiness
```

---

## Authentication

Identity always comes from a signed JWT (HS256). Any user ID in a request body is ignored.

`POST /auth/token` issues tokens. **This is a demo login** so testers can create many users; a real system would use a proper identity provider.

**User token:**

```bash
curl -X POST $BASE/auth/token -H "Content-Type: application/json" \
  -d '{"user_id":"u1"}'
# → {"token":"eyJ..."}
```

**Admin token** (required to create shows) needs the `X-Admin-Key` header. The live admin key is shared in the submission email, not in this repo. Locally it is `local-admin-key`.

```bash
curl -X POST $BASE/auth/token -H "Content-Type: application/json" \
  -H "X-Admin-Key: <admin key>" \
  -d '{"user_id":"admin","role":"admin"}'
```

Tokens expire after 24 hours. Send them as `Authorization: Bearer <token>`.

---

## API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/auth/token` | none | Get a token (demo login) |
| POST | `/shows` | admin | Create a show with all seats available |
| GET | `/shows/{id}` | none | Per-seat status and counts |
| POST | `/shows/{id}/reserve` | user | Reserve one or more seats |
| POST | `/reservations/{id}/cancel` | owner | Cancel a reservation and free its seats |
| GET | `/actuator/health/liveness` | none | Process alive |
| GET | `/actuator/health/readiness` | none | Ready to serve (checks the database) |
| GET | `/actuator/prometheus` | none | Metrics |

Money is always integer paise.

### Create a show

```bash
curl -X POST $BASE/shows -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"friday-night","seats":["A1","A2","A12","A13"],"price_paise":25000,"per_user_limit":4}'
```

`per_user_limit` is optional (default 4).

### Reserve

```bash
curl -X POST $BASE/shows/$SHOW_ID/reserve -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"seats":["A12"],"idempotency_key":"any-unique-string"}'
```

```json
{ "reservation_id": "…", "show_id": "…", "user_id": "u1", "seats": ["A12"], "amount_paise": 25000, "status": "confirmed" }
```

Multi-seat requests are **all-or-nothing**: if any requested seat is taken, nothing is reserved.

### Cancel

```bash
curl -X POST $BASE/reservations/$RESERVATION_ID/cancel -H "Authorization: Bearer $TOKEN"
```

No body. Only the owner can cancel. Cancelling twice is safe and changes nothing the second time.

### Responses

| Status | When |
|---|---|
| 201 | Reservation created |
| 200 | Idempotent replay (same key, same request): returns the original reservation |
| 400 | Invalid input, duplicate seats in request, unknown seat |
| 401 | Missing or invalid token |
| 403 | Not allowed (non-admin creating a show, cancelling someone else's reservation) |
| 404 | Show or reservation not found |
| 409 | `seat_taken`, `per_user_limit`, or `idempotency_key_reused` (same key, different request) |

Error bodies look like `{"error":"seat_taken","message":"Seat A12 is already taken"}`.

---

## Burst test (one command)

Requires [k6](https://k6.io/docs/get-started/installation/).

```bash
ADMIN_KEY=<admin key> ./burst.sh https://seat-reservation-production-dca1.up.railway.app
```

Optional: `HOT_USERS=5000` to change the size of the hot-seat storm (default 2000).

The script creates a fresh 50-seat show (limit 4), creates one token per test user, then runs three scenarios **at the same time**:

| Scenario | What it does | Expected |
|---|---|---|
| `hot_seat` | `HOT_USERS` different users all reserve seat A12 at once | exactly 1 × 201, the rest 409 `seat_taken` |
| `one_user` | One user fires 10 parallel reserves for 10 different seats | at most 4 × 201, the rest 409 `per_user_limit` |
| `retries` | One user sends the same request with the same key 20 times at once | 1 × 201, 19 × 200 (replays) |

It prints the outcome distribution (`confirmed_201`, `declined_seat_taken`, `declined_per_user_limit`, `replay_200`, `errors_5xx`, `other_4xx`) and the final reconciliation (`available + held + confirmed == total`).

**Without installing k6** (Docker only):

```bash
docker run --rm -i -e BASE_URL=<url> -e ADMIN_KEY=<admin key> grafana/k6 run - < burst/burst.js
```

### Results so far (against the live URL)

| Hot-seat users | Confirmed | seat_taken | per_user_limit | Replays | 5xx | Invariant |
|---|---|---|---|---|---|---|
| 500 | 6 | 499 | 6 | 19 | 0 | ✅ |
| 2,000 | 6 | 1,999 | 6 | 19 | 0 | ✅ |
| 5,000 | 6 | 4,999 | 6 | 19 | 0 | ✅ |

---

## Observability

**Health**
- Liveness: `/actuator/health/liveness` checks only that the process is running.
- Readiness: `/actuator/health/readiness` includes a database check and returns 503 when the database is unreachable.

**Metrics** at `/actuator/prometheus`, all tagged with `show_id`:

| Metric | Type | Meaning |
|---|---|---|
| `reservations_confirmed_total` | counter | Successful reservations |
| `reservations_declined_total{reason}` | counter | `seat_taken`, `per_user_limit`, `idempotent_replay`, `idempotency_key_reused`, `unknown_seat` |
| `reservations_cancelled_total` | counter | Cancellations that freed seats |
| `seats_by_status{status}` | gauge | Seats per status, read from the database every 5s |

Counters are recorded only after the transaction commits, so they match the API state.

**Logs** are structured JSON, one line per request with `request_id`, `user_id`, `method`, `path`, `status`, `duration_ms` and `outcome`. Send an `X-Request-Id` header to trace your own request; otherwise one is generated and returned in the response header.

Railway logs are not publicly accessible. A screen recording of the live logs during a burst: [watch on Google Drive](https://drive.google.com/file/d/1MCEwvjGnIZB9ty9cWS1ZfPpmYL5Ql44M/view?usp=sharing)

---

## Configuration

| Variable | Default (local only) | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/seats` | JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `postgres` / `postgres` | Database credentials |
| `JWT_SECRET` | local dev value | Token signing secret (at least 32 bytes). Always set in production |
| `ADMIN_KEY` | `local-admin-key` | Required to get an admin token |
| `PORT` | `8080` | HTTP port (set by the platform) |
