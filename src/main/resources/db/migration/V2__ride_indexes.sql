CREATE INDEX idx_rides_rider_requested  ON rides (rider_id, requested_at);
CREATE INDEX idx_rides_driver_requested ON rides (driver_id, requested_at);
CREATE INDEX idx_rides_driver_status    ON rides (driver_id, status);
CREATE INDEX idx_rides_status_location  ON rides (status, pickup_lat, pickup_lng);
