CREATE TABLE shows (
                       id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                       name            text   NOT NULL,
                       price_paise     bigint NOT NULL CHECK (price_paise >= 0),
                       per_user_limit  int    NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
                       total_seats     int    NOT NULL,
                       created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE seats (
                       show_id         uuid NOT NULL REFERENCES shows(id),
                       seat_label      text NOT NULL,
                       status          text NOT NULL DEFAULT 'available'
                           CHECK (status IN ('available', 'held', 'confirmed')),
                       user_id         text,
                       reservation_id  uuid,
                       PRIMARY KEY (show_id, seat_label)
);

CREATE TABLE reservations (
                              id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                              show_id         uuid   NOT NULL REFERENCES shows(id),
                              user_id         text   NOT NULL,
                              seats           text[] NOT NULL,
                              amount_paise    bigint NOT NULL,
                              status          text   NOT NULL CHECK (status IN ('confirmed', 'cancelled')),
                              created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_reservations_show_user ON reservations (show_id, user_id);

CREATE TABLE idempotency_keys (
                                  user_id         text NOT NULL,
                                  key             text NOT NULL,
                                  request_hash    text NOT NULL,
                                  reservation_id  uuid,
                                  created_at      timestamptz NOT NULL DEFAULT now(),
                                  PRIMARY KEY (user_id, key)
);