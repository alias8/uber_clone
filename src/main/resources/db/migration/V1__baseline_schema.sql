CREATE TABLE IF NOT EXISTS users (
    id            VARCHAR(255) PRIMARY KEY,
    username      VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(50)  NOT NULL,
    avg_rating    DOUBLE PRECISION
);

CREATE TABLE IF NOT EXISTS drivers (
    user_id       VARCHAR(255) PRIMARY KEY,
    vehicle_type  VARCHAR(255) NOT NULL,
    license_plate VARCHAR(255) NOT NULL,
    is_available  BOOLEAN      NOT NULL DEFAULT FALSE,
    avg_rating    DOUBLE PRECISION
);

CREATE TABLE IF NOT EXISTS rides (
    id             VARCHAR(255) PRIMARY KEY,
    rider_id       VARCHAR(255)   NOT NULL,
    driver_id      VARCHAR(255),
    pickup_lat     DOUBLE PRECISION NOT NULL,
    pickup_lng     DOUBLE PRECISION NOT NULL,
    dropoff_lat    DOUBLE PRECISION NOT NULL,
    dropoff_lng    DOUBLE PRECISION NOT NULL,
    status         VARCHAR(50)    NOT NULL,
    estimated_fare NUMERIC(19, 2),
    fare           NUMERIC(19, 2),
    requested_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at   TIMESTAMP WITH TIME ZONE,
    version        BIGINT         NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS ratings (
    id           VARCHAR(255) PRIMARY KEY,
    ride_id      VARCHAR(255) NOT NULL,
    from_user_id VARCHAR(255) NOT NULL,
    to_user_id   VARCHAR(255) NOT NULL,
    score        INTEGER      NOT NULL,
    comment      TEXT,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_ratings_ride_from UNIQUE (ride_id, from_user_id)
);
