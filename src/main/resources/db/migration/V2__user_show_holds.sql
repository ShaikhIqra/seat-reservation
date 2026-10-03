
CREATE TABLE user_show_holds (

    show_id     uuid NOT NULL REFERENCES shows(id),
    user_id     text NOT NULL,
    seat_count  int  NOT NULL DEFAULT 0 CHECK (seat_count >= 0),
    PRIMARY KEY (show_id, user_id)
);