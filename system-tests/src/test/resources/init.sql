-- Runs once when the PostgreSQL container is created (copied to /docker-entrypoint-initdb.d/ by Stack), as the
-- superuser of the image. The system test uses ONE PostgreSQL container for both services; each service still gets
-- its own database and its own credentials, so from the service's point of view nothing differs from docker-compose
-- (booking-db / payment-db) or Kubernetes. The schemas themselves are created by each service's Flyway migrations.
CREATE USER booking WITH PASSWORD 'booking';
CREATE DATABASE booking_db OWNER booking;

CREATE USER payment WITH PASSWORD 'payment';
CREATE DATABASE payment_db OWNER payment;
