# Running the Ride-Sharing Backend — Complete Setup Guide

Everything you need to get the project running locally, including how to get Stripe keys, test webhooks, and verify all services are healthy.

---

## 1. Install Prerequisites

Make sure you have these installed before starting:

| Tool | Check Command | Minimum Version |
|---|---|---|
| Java | `java -version` | 17+ |
| Maven | `mvn -version` | 3.9+ |
| Docker | `docker --version` | 20+ |
| Docker Compose | `docker-compose --version` | 2+ |
| Git | `git --version` | any |

---

## 2. Get Your Stripe Keys

### Step 1 — Create a Stripe Account
Go to [https://dashboard.stripe.com](https://dashboard.stripe.com) and create a free account. **No credit card required** — test mode is completely free.

### Step 2 — Get Your Secret Key
After login: **Developers → API Keys**

You'll see two keys:
- `pk_test_...` — Publishable key (frontend only, ignore this)
- `sk_test_...` — **Secret key ← copy this**

### Step 3 — Get Your Webhook Secret
Go to **Developers → Webhooks → Add endpoint**

- **Endpoint URL:** `http://localhost:8085/api/payments/webhook`
- **Events to listen to:** `payment_intent.succeeded`, `payment_intent.payment_failed`

After creating, click on the webhook → **Reveal signing secret**
- Starts with `whsec_...` ← **copy this**

> **For local development**, use the Stripe CLI instead of setting up a real webhook URL — it's much easier. See Step 5 below.

---

## 3. Set Environment Variables

Open `docker-compose.yml` and fill in your Stripe keys under the `payment-service` section:

```yaml
payment-service:
  environment:
    STRIPE_SECRET_KEY: sk_test_your_actual_key_here
    STRIPE_WEBHOOK_SECRET: whsec_your_actual_secret_here
```

Or create a `.env` file in the project root:

```env
STRIPE_SECRET_KEY=sk_test_xxxxxxxxxxxx
STRIPE_WEBHOOK_SECRET=whsec_xxxxxxxxxxxx
```

---

## 4. Start the Entire Platform

From inside the `ridesharing-backend/` folder:

```bash
docker-compose up -d
```

This starts all 9 services: Zookeeper, Kafka, Redis, 4 PostgreSQL databases, Eureka, Zipkin, Prometheus, Grafana, and all 7 Spring Boot microservices.

Wait **60–90 seconds** for everything to be ready, then verify:

```bash
# Check all containers are running
docker-compose ps

# Watch live logs
docker-compose logs -f

# Watch logs for a specific service
docker-compose logs -f ride-service
```

All services should show status `Up` or `healthy`.

---

## 5. Test Stripe Webhooks Locally (Stripe CLI)

Since `localhost` is not publicly accessible, Stripe can't reach your webhook URL directly. Use the **Stripe CLI** to forward webhook events to your local machine.

### Install Stripe CLI

```bash
# macOS
brew install stripe/stripe-cli/stripe

# Windows / Linux — download from:
# https://stripe.com/docs/stripe-cli
```

### Login and Forward Webhooks

```bash
# Login with your Stripe account
stripe login

# Forward webhook events to your local payment service
stripe listen --forward-to localhost:8085/api/payments/webhook
```

The CLI will print a **temporary webhook signing secret** like:
```
> Ready! Your webhook signing secret is whsec_abc123xyz... (^C to quit)
```

Use this `whsec_...` value as your `STRIPE_WEBHOOK_SECRET` in docker-compose.yml or .env while developing locally.

---

## 6. Access All Services

Once running, open these URLs in your browser:

| Service | URL | Notes |
|---|---|---|
| **API Gateway** | http://localhost:8080 | All API requests go here |
| **Eureka Dashboard** | http://localhost:8761 | See all registered services |
| **Kafka UI** | http://localhost:9090 | Browse topics and messages |
| **Zipkin Tracing** | http://localhost:9411 | Distributed request traces |
| **Prometheus** | http://localhost:9091 | Raw metrics |
| **Grafana** | http://localhost:3000 | Dashboards (login: admin / admin) |

---

## 7. Quick Test — Full Ride Flow

Run these commands in order to test the complete ride booking flow:

### Register a Rider
```bash
curl -X POST http://localhost:8080/api/users/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "rider@test.com",
    "password": "pass123",
    "fullName": "Test Rider",
    "phone": "9999999999"
  }'
```

### Login and Get JWT Token
```bash
curl -X POST http://localhost:8080/api/users/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "rider@test.com",
    "password": "pass123"
  }'
```
Copy the `token` value from the response. Use it in all further requests as `Authorization: Bearer <token>`.

### Register a Driver
```bash
curl -X POST http://localhost:8080/api/drivers/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "driver@test.com",
    "password": "pass123",
    "fullName": "Test Driver",
    "vehicleNumber": "MH01AB1234",
    "vehicleType": "SEDAN"
  }'
```

### Driver Goes Online and Sets Location
```bash
# Login as driver first to get driver token, then:

curl -X POST http://localhost:8080/api/drivers/online \
  -H "Authorization: Bearer DRIVER_TOKEN_HERE"

curl -X POST http://localhost:8080/api/drivers/location \
  -H "Authorization: Bearer DRIVER_TOKEN_HERE" \
  -H "Content-Type: application/json" \
  -d '{"latitude": 19.0760, "longitude": 72.8777}'
```

### Get Fare Estimate
```bash
curl -X POST http://localhost:8080/api/pricing/estimate \
  -H "Authorization: Bearer RIDER_TOKEN_HERE" \
  -H "Content-Type: application/json" \
  -d '{
    "pickupLat": 19.0760, "pickupLon": 72.8777,
    "dropoffLat": 19.0880, "dropoffLon": 72.8950
  }'
```

### Request a Ride
```bash
curl -X POST http://localhost:8080/api/rides/request \
  -H "Authorization: Bearer RIDER_TOKEN_HERE" \
  -H "Content-Type: application/json" \
  -d '{
    "pickupLat": 19.0760, "pickupLon": 72.8777,
    "dropoffLat": 19.0880, "dropoffLon": 72.8950,
    "pickupAddress": "Bandra West",
    "dropoffAddress": "Andheri East"
  }'
```
Copy the `rideId` from the response.

### Driver Accepts the Ride
```bash
curl -X POST http://localhost:8080/api/rides/{rideId}/accept \
  -H "Authorization: Bearer DRIVER_TOKEN_HERE"
```

### Start and Complete the Ride
```bash
curl -X POST http://localhost:8080/api/rides/{rideId}/start \
  -H "Authorization: Bearer DRIVER_TOKEN_HERE"

curl -X POST http://localhost:8080/api/rides/{rideId}/complete \
  -H "Authorization: Bearer DRIVER_TOKEN_HERE"
```

After completing, Kafka fires a `ride.completed` event → payment-service picks it up → creates a Stripe PaymentIntent → Stripe calls the webhook → payment status updates to `SUCCESS`.

### Check Payment Status
```bash
curl http://localhost:8080/api/payments/ride/{rideId} \
  -H "Authorization: Bearer RIDER_TOKEN_HERE"
```

---

## 8. Common Issues & Fixes

**Services crash on startup?**
Kafka and PostgreSQL take 20–30 seconds to be ready. Spring Boot services may fail their first health check. Fix:
```bash
docker-compose restart ride-service driver-service payment-service
```

**Port already in use?**
If you have PostgreSQL or Redis running locally, they conflict with Docker. Stop them first:
```bash
# macOS
brew services stop postgresql
brew services stop redis
```

**Stripe webhook returning 400?**
Make sure the `STRIPE_WEBHOOK_SECRET` in your config matches the `whsec_...` printed by `stripe listen`, not your API secret key (`sk_test_...`). These are two different values.

**Eureka shows services as DOWN?**
Services register after about 30 seconds. Wait and refresh http://localhost:8761. If a service is missing, check its logs: `docker-compose logs -f <service-name>`.

**Want to reset everything and start fresh?**
```bash
docker-compose down -v   # stops containers AND deletes all data volumes
docker-compose up -d     # fresh start
```

---

## 9. Stop the Project

```bash
# Stop all containers (keeps data)
docker-compose down

# Stop and delete all data (full reset)
docker-compose down -v
```

---

## Summary

| Step | What to do |
|---|---|
| 1 | Install Java 17, Maven, Docker |
| 2 | Get `sk_test_...` and `whsec_...` from Stripe Dashboard |
| 3 | Add keys to `docker-compose.yml` or `.env` |
| 4 | Run `docker-compose up -d` |
| 5 | Run `stripe listen` for local webhook forwarding |
| 6 | Hit `http://localhost:8080` with API calls |
