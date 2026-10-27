# Ride-Sharing Backend — Production Grade Microservices

A production-grade ride-sharing backend (Uber/Ola-like) built with Spring Boot, Kafka, Redis, and Stripe.

## Architecture

```
Client → API Gateway (8080) → Microservices via Eureka Load Balancing
              │                          ↕ Kafka (async events)
              │                          ↕ Redis (geo + surge cache + rate limiting)
              ↓                          ↕ PostgreSQL (per-service DB)
    Redis Rate Limiter
    JWT Auth Filter
```

## Services

| Service              | Port | Description                                              |
|----------------------|------|----------------------------------------------------------|
| API Gateway          | 8080 | JWT auth, Redis rate limiting (10 req/s), routing        |
| User Service         | 8081 | Registration, login, JWT — validated DTOs + Swagger      |
| Driver Service       | 8082 | Driver mgmt, Redis GeoSet location — validated DTOs      |
| Ride Service         | 8083 | Ride lifecycle + Saga PaymentEventConsumer               |
| Payment Service      | 8084 | Stripe + circuit breaker + idempotency                   |
| Pricing Service      | 8085 | Surge pricing with Redis demand/supply counters          |
| Notification Service | 8086 | Kafka consumer for all ride/payment events               |
| Config Server        | 8888 | Spring Cloud Config (centralised config)                 |
| Eureka Server        | 8761 | Service discovery + load balancing                       |

## Quick Start

```bash
# 1. Add your Stripe keys to docker-compose.yml or .env
#    STRIPE_SECRET_KEY=sk_test_...
#    STRIPE_WEBHOOK_SECRET=whsec_...

# 2. Start everything
docker-compose up -d

# 3. Wait ~90s, then verify
docker-compose ps

# 4. Open dashboards
open http://localhost:8761   # Eureka — all services should appear
open http://localhost:8090   # Kafka UI — browse topics
open http://localhost:9411   # Zipkin — distributed tracing
open http://localhost:3000   # Grafana — dashboards (admin/admin)

# 5. Swagger UI for each service
open http://localhost:8081/swagger-ui.html   # User Service
open http://localhost:8082/swagger-ui.html   # Driver Service
open http://localhost:8083/swagger-ui.html   # Ride Service
open http://localhost:8084/swagger-ui.html   # Payment Service
open http://localhost:8085/swagger-ui.html   # Pricing Service
```

## Running Tests

```bash
# Run all tests
mvn test

# Run tests for a specific service
cd ride-service && mvn test
cd payment-service && mvn test

# Tests cover:
# RideServiceTest    — requestRide, acceptRide (Redis TTL expiry), fare calculation, cancel
# PaymentServiceTest — idempotency (duplicate event suppression), Stripe webhook handling
```

## API Usage

### 1. Register & Login

```bash
# Register rider
POST http://localhost:8080/api/users/register
{
  "email": "rider@test.com",
  "password": "pass12345",
  "fullName": "John Doe",
  "phone": "9876543210"
}

# Rider login → get JWT token
POST http://localhost:8080/api/users/login
{"email": "rider@test.com", "password": "pass12345"}

# Register driver (also returns JWT token)
POST http://localhost:8080/api/drivers/register
{
  "email": "driver@test.com",
  "password": "pass12345",
  "fullName": "Ram Kumar",
  "phone": "9123456789",
  "vehicleNumber": "MH01AB1234",
  "vehicleType": "SEDAN"
}

# Driver login → get JWT token
POST http://localhost:8080/api/drivers/login
{"email": "driver@test.com", "password": "pass12345"}
# Response: { "token": "eyJ...", "driverId": "...", "email": "...", "status": "OFFLINE", ... }
```

**Validation rules enforced:**
- email — must be valid format
- password — minimum 8 characters
- phone — exactly 10 digits
- vehicleNumber — format `MH01AB1234` (state code + numbers + letters + numbers)
- vehicleType — one of: `SEDAN`, `SUV`, `AUTO`, `BIKE`

### 2. Driver Goes Online

```bash
POST http://localhost:8080/api/drivers/online
Authorization: Bearer <driver_jwt>
{"latitude": 28.6139, "longitude": 77.2090}
```

Latitude must be -90 to 90, longitude must be -180 to 180 (validated).

### 3. Request a Ride

```bash
POST http://localhost:8080/api/rides/request
Authorization: Bearer <rider_jwt>
{
  "pickupLat": 28.6139, "pickupLon": 77.2090,
  "dropLat": 28.7041,   "dropLon": 77.1025,
  "pickupAddress": "Connaught Place",
  "dropAddress": "Rohini"
}
```

Internally: saves ride → sets Redis key with 60s TTL → publishes `ride.requested` to Kafka.

### 4. Driver Accepts Ride

```bash
POST http://localhost:8080/api/rides/{rideId}/accept
Authorization: Bearer <driver_jwt>
```

Fails with error if Redis TTL has expired (60s window). Deletes Redis key on success.

### 5. Complete Ride → Payment Triggered

```bash
POST http://localhost:8080/api/rides/{rideId}/start     # Driver starts
POST http://localhost:8080/api/rides/{rideId}/complete  # Driver completes
```

On complete: publishes `ride.completed` → payment-service processes Stripe payment asynchronously.

### 6. Ride History & Fare Estimate

```bash
GET  http://localhost:8080/api/rides/my-rides               # Rider's ride history
POST http://localhost:8080/api/pricing/estimate             # Fare estimate with surge
GET  http://localhost:8080/api/pricing/surge/zone_center    # Current surge multiplier
```

### 7. Check Payment

```bash
GET http://localhost:8080/api/payments/ride/{rideId}
Authorization: Bearer <rider_jwt>
```

Returns payment status: `PENDING` / `SUCCESS` / `FAILED` / `REFUNDED`.

## Kafka Topics & Saga Flow

| Topic             | Producer        | Consumers                                          |
|-------------------|-----------------|----------------------------------------------------|
| ride.requested    | Ride Service    | Driver Service, Pricing Service                    |
| ride.accepted     | Ride Service    | Driver Service, Notification Service               |
| ride.started      | Ride Service    | Notification Service                               |
| ride.completed    | Ride Service    | Payment Service, Notification Service              |
| ride.cancelled    | Ride Service    | Driver Service, Notification Service               |
| payment.success   | Payment Service | **Ride Service** (log), Notification Service       |
| payment.failed    | Payment Service | **Ride Service** (→ PAYMENT_FAILED), Notification  |

### Saga Compensating Transaction (payment.failed)

```
ride.completed → payment-service charges Stripe
    ├── SUCCESS → ride stays COMPLETED ✓
    └── FAILED  → ride-service PaymentEventConsumer sets ride to PAYMENT_FAILED ✓
```

Without this consumer, a ride would stay `COMPLETED` even when payment failed — broken business logic. The `PaymentEventConsumer` closes the Saga loop.

## Rate Limiting (Redis Token Bucket)

| Endpoint                        | Rate           | Burst |
|---------------------------------|----------------|-------|
| `/api/users/register`, `/login` | 3 req/s        | 5     |
| `/api/drivers/register`         | 3 req/s        | 5     |
| All other routes (default)      | 10 req/s       | 20    |

Rate limiting is per-user (by `X-User-Id` header) for authenticated requests, per-IP for unauthenticated. Exceeded requests return **HTTP 429 Too Many Requests**.

## Ride Status State Machine

```
REQUESTED → DRIVER_ASSIGNED → STARTED → COMPLETED → (payment async)
    │                                        └── payment.failed → PAYMENT_FAILED
    └── CANCELLED (any time before STARTED)
```

## Key Design Decisions

| Decision | What | Why |
|---|---|---|
| **Event-driven** | Kafka for service communication | Loose coupling — services fail/scale independently |
| **Redis GeoSet** | GEORADIUS for driver matching | O(log N) vs SQL full table scan at scale |
| **Idempotent payments** | DB check + Stripe idempotency key | Prevents double-charge on Kafka redelivery |
| **Circuit Breaker** | Resilience4j on Stripe (50% threshold) | Prevents cascading failure when Stripe is down |
| **Saga Pattern** | Choreography-based, no orchestrator | Eventual consistency without distributed transactions |
| **Database-per-service** | Each service owns its PostgreSQL schema | No shared schema coupling; independent evolution |
| **Redis Rate Limiter** | Token bucket, per-user keys | Prevents abuse without blocking legitimate traffic |
| **Bean Validation** | `@Valid` on all request DTOs | Reject bad input at controller layer, before service logic |
| **Swagger/OpenAPI** | springdoc on all services | Interactive API docs at `/swagger-ui.html` per service |

## Project Structure

```
ridesharing-backend/
├── api-gateway/          # JWT filter + Redis rate limiting
├── user-service/         # Auth + validation + Swagger
├── driver-service/       # GeoSet + Kafka consumer + validation
├── ride-service/         # State machine + PaymentEventConsumer (Saga)
├── payment-service/      # Stripe + circuit breaker + idempotency
├── pricing-service/      # Surge pricing + @Scheduled recalculation
├── notification-service/ # Kafka consumer for all events
├── config-server/        # Centralised Spring Cloud Config
├── eureka-server/        # Service discovery
├── docker-compose.yml    # Full stack in one command
├── prometheus.yml        # Metrics scrape config
├── init-db.sql           # Creates all 4 PostgreSQL databases
├── README.md             # This file
└── RUNNING-THE-PROJECT.md # Step-by-step setup guide
```

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 3.2, Spring Cloud 2023 |
| API Gateway | Spring Cloud Gateway |
| Messaging | Apache Kafka (Confluent 7.5) |
| Cache / Geo | Redis 7 (GeoSet, rate limiting, TTL) |
| Database | PostgreSQL 15 (per-service) |
| Payments | Stripe Java SDK 23.3 |
| Resilience | Resilience4j (circuit breaker) |
| Auth | JWT (JJWT 0.11.5), BCrypt |
| Validation | Spring Boot Starter Validation (Jakarta) |
| API Docs | springdoc-openapi 2.3.0 (Swagger UI) |
| Tracing | Zipkin |
| Metrics | Prometheus + Grafana |
| Containerisation | Docker Compose |
| Testing | JUnit 5, Mockito, AssertJ |
#   R i d e S h a r i n g  
 