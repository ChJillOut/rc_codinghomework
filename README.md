# NotificationDispatcher — Internal HTTP Notification Relay Service

## 1. Problem Understanding

Multiple internal business systems (e.g., ad attribution, CRM, inventory management) need to call external vendor HTTP APIs to notify them of key events. While each vendor has a different API format (URL, headers, body), the calling business systems **do not care about the response values**; they only need to **ensure notifications are reliably delivered**.

**Core Requirements Breakdown:**
- A unified internal API for business systems to submit notification requests (Fire-and-Forget).
- **Reliable delivery guarantees** (no message loss).
- **Automatic retries** in case external APIs are temporarily down or unreachable.
- **Centralized configuration** for multiple vendors.

## 2. Architecture Overview

```
┌─────────────┐     POST /api/notifications     ┌─────────────────────┐
│  Business   │ ──────────────────────────────→ │ NotificationController│
│   Systems   │     (fire-and-forget, 202)      └─────────┬───────────┘
└─────────────┘                                            │ Validate & Enqueue
                                                           ▼
                                               ┌──────────────────────┐
                                               │      RabbitMQ        │
                                               │  notification.queue  │
                                               │   (Durable Queue)    │
                                               └─────────┬────────────┘
                                                          │ Consume
                                                          ▼
                                               ┌──────────────────────┐
                                               │ NotificationConsumer │
                                               │ → HTTP call to vendor│
                                               └─────────┬────────────┘
                                                          │
                                             ┌────────────┴────────────┐
                                             │                         │
                                        Success (ACK)               Failure
                                                          ┌───────────┴──────────┐
                                                          │                      │
                                                     Retryable (5xx)      Non-Retryable (4xx)
                                                     → Retry Queues          → DLQ
                                                     (Backoff TTL)        (Dead Letter)
```

### Technology Stack

| Component | Choice | Description |
|-----------|--------|-------------|
| Language | Java 25 | Latest long-term support version |
| Framework | Spring Boot 4.1.0 | Latest Spring Boot framework |
| Message Broker | RabbitMQ | Durable queuing, TTL, dead-letter exchanges |
| HTTP Client | RestClient | Spring 4.x synchronous, blocking HTTP client |
| Build Tool | Maven (wrapper) | Standardized build script |
| Test Suite | JUnit 5 + Testcontainers + MockWebServer | End-to-end integration and unit testing |

## 3. Core Design Decisions

### 3.1 Delivery Semantics: At-Least-Once
- **Rationale**: True "Exactly-Once" delivery requires cooperation from external vendors (via idempotency keys and transaction confirmations), which we cannot control. "At-Least-Once" is the strongest guarantee we can unilaterally provide.
- **Implication**: External APIs may receive duplicate notifications. This is acceptable because:
  - Calling business systems do not require callback return values.
  - Most third-party systems (ad callbacks, CRM status updates) are idempotent by design.
- **Deduplication Responsibility**:
  - **No In-Service Deduplication**: Our service is a generic payload relay and treats the message body as an opaque string. We do not have the business context to determine if two identical payloads represent a duplicate error or two distinct user events.
  - **Deduplication Complexity**: True deduplication requires client-supplied request IDs, distributed caching (e.g. Redis), and locking, which adds significant architectural overhead.
  - **Downstream Mitigation**: If deduplication is needed, calling business systems should embed unique idempotency keys inside the message payload (`body`) or headers. The receiving external vendor can then use these keys to discard duplicates. This aligns with industry-standard webhook practices (e.g., Stripe, GitHub).

### 3.2 Retry Strategy: Exponential Backoff via RabbitMQ TTL
```
1st failure → retry.queue.1 (TTL = 5 seconds)
2nd failure → retry.queue.2 (TTL = 30 seconds)
3rd failure → retry.queue.3 (TTL = 120 seconds)
4th failure → DLQ (Dead Letter Queue, held for manual inspection)
```
- **Why not `@Retryable`?** Spring Retry blocks the consumer execution threads during backoff delays and cannot persist messages if the application crashes or restarts. Using RabbitMQ's message TTL and Dead Letter Exchanges (DLX) is non-blocking, survives system crashes, and scales horizontally.
- **Why not RabbitMQ's native `x-death` header?** Managing a custom `retryCount` payload field is more explicit, less brittle, and much easier to test than parsing complex, broker-specific header maps.

### 3.3 Error Classification

| HTTP Status | Action | Reason |
|-------------|--------|--------|
| `2xx` | ACK (Success) | Completed successfully |
| `4xx` | Route to DLQ (No Retry) | Vendor client error (e.g., invalid auth or bad endpoint on our side). Repeating the request will not change the outcome |
| `5xx` | Route to Retry Queue | Transient server error. Likely to recover eventually |
| Timeout / Connect Error | Route to Retry Queue | Temporary network issue. Likely to recover |

### 3.4 Vendor Configuration: Static YAML
```yaml
notification:
  vendors:
    ad-system:
      url: "https://ads.vendor.com/api/callback"
      default-headers:
        Authorization: "Bearer ${AD_SYSTEM_TOKEN}"
      timeout-seconds: 10
```
Business systems submit requests specifying a `vendorId`. The application merges the request overrides with the vendor's default configuration in YAML.
- **Why not a dynamic CRUD registry?** For an MVP, adding or modifying vendors is a low-frequency administrative action. Static configurations in YAML are simple and safe. The design encapsulates configurations in a `VendorProperties` configuration bean, making it easy to migrate to a database later if required.

### 3.5 Failure Modes & Resilience

To ensure production-grade robustness, we mapped out each potential failure point, comparing the developer's pragmatism with the AI's initial recommendations:

| Failure Scenario | Impact | How it is Handled | Developer's Thought vs. AI Suggestion |
|------------------|--------|-------------------|---------------------------------------|
| **Downstream Vendor Down (5xx / Timeout)** | Transient Failure | Caught as retryable exception. Message is re-routed to the exponential backoff queues (5s $\rightarrow$ 30s $\rightarrow$ 120s) and eventually the DLQ after 4 failed attempts. | **Developer Decision**: Use RabbitMQ message TTLs for non-blocking backoff. <br> *AI Suggestion*: Use `@Retryable` annotations (rejected as it blocks threads and is memory-bound). |
| **Vendor Rejects Payload (4xx)** | Permanent Failure | Caught as non-retryable exception. Handled by acknowledging from the main queue and sending directly to the DLQ. | **Developer Decision**: Do not retry client errors; route directly to DLQ. <br> *AI Suggestion*: Treat all errors as retryable (rejected as it wastes resources). |
| **Service Instance Crashes Midway** | Processing Interrupted | RabbitMQ detects connection loss. Because we use manual ACKs (`basicAck`), the message is kept in the queue and redelivered to a healthy instance. | **Developer Decision**: Rely on native AMQP manual ACKs.<br> *AI Suggestion*: Write progress updates to PostgreSQL (rejected as over-engineering). |
| **RabbitMQ Broker Crashes** | Broker Offline | Durable queues and exchanges (`durable=true`) and persistent messages ensure that queue state is loaded from disk on broker restart. | **Developer Decision**: Use RabbitMQ's built-in persistence.<br> *AI Suggestion*: Dual-write payloads to local log files (rejected as redundant and slow). |
| **Inbound Request Malformed** | Ingestion Error | Validated at API boundary using Jakarta `@Valid`. Fails instantly with a `400 Bad Request` back to caller. | **Developer Decision**: Enforce strict boundary checks. Message never enters RabbitMQ. <br> *AI Suggestion*: Enqueue bad requests and filter them inside the consumer (rejected as queue pollution). |
| **Inbound Request Ingestion Fails** | Service Offline | REST call returns `502`/`503`/`ConnectException`. Ingestion failed; message is not committed to the queue. | **Developer Decision**: Caller is responsible for handling ingestion failures (e.g., using Transactional Outbox). <br> *AI Suggestion*: Service caches failed ingestions locally (rejected, violates stateless design). |

### 3.6 Concurrency Model: Java Virtual Threads

Since our application is strictly **I/O-bound** (waiting on blocking HTTP calls to external vendors and listening to RabbitMQ messages), standard operating system thread pools present scalability limits (each OS thread consumes ~1MB stack memory). 

Because we target **Java 25 and Spring Boot 4.x**, we configured the application to run entirely on **Java Virtual Threads** (Project Loom) by setting the following property in [application.yml](file:///Users/Shiqing/Workplace/NotificationDispatcher/src/main/resources/application.yml):
```yaml
spring:
  threads:
    virtual:
      enabled: true
```

#### Why we use Virtual Threads:
*   **Non-Blocking on HTTP Calls**: When a consumer worker blocks on an outbound `RestClient` call to a slow vendor, the JVM automatically yields the virtual thread, releasing the underlying OS carrier thread to perform other work.
*   **Trivial Concurrency Scaling**: We can run thousands of concurrent consumer workers and REST request handlers without consuming gigabytes of system memory.
*   **Simple Synchronous Programming**: We get the massive throughput benefits of reactive programming (like WebClient/Reactor) while maintaining a simple, synchronous blocking programming model (`RestClient` and `@RabbitListener`) that is far easier to write, debug, and write unit tests for.

## 4. System Boundaries

### What We Solve

| Requirement | Solution |
|-------------|----------|
| Unified Request API | REST endpoint `POST /api/notifications` |
| Zero Message Loss | Durable RabbitMQ queues |
| Transient Failures | Automatic exponential backoff retries (5s → 30s → 120s) |
| Persistent Failures | Dead Letter Queue (DLQ) containment |
| Multi-vendor defaults | Centralized YAML config with request-level overrides |
| Validation | Inbound payload validation via Jakarta validation (`@NotBlank`) |

### What We Do NOT Solve (Out of Scope)

| Feature | Reason |
|---------|--------|
| **Response Callbacks** | Calling business systems do not care about vendor response payloads |
| **Dynamic Vendor Registry** | MVP static YAML configuration is sufficient and safer for low-frequency changes |
| **Authentication/Authorization** | Assumed internal VPC security context for microservice-to-microservice calls |
| **Rate Limiting** | Out of scope for MVP; requires custom token bucket implementations per vendor |
| **Message Deduplication** | At-Least-Once delivery is sufficient; duplicate filtering is the vendor's responsibility |
| **Dashboard UI** | Logging and DLQ inspection via RabbitMQ console are sufficient for the MVP |

## 5. Trade-offs and Evolution

### Rejected AI Recommendations ("Over-Engineering")
1. **Adding PostgreSQL Database** — The AI suggested persisting all notification transactions in PostgreSQL. I rejected this for the MVP; RabbitMQ's durable queues ensure message persistence, and the DLQ tracks failures. Databases introduce schema management and transaction overhead.
2. **Adding Redis for Idempotency** — The AI suggested keeping track of message IDs in Redis to prevent duplicates. Since "At-Least-Once" is our chosen delivery guarantee, deduplication is the recipient's responsibility.
3. **WebClient (Reactive) over RestClient** — The AI recommended using non-blocking WebFlux and WebClient to scale concurrent outbound dispatches. I rejected this and instead enabled **Java Virtual Threads** globally in combination with Spring's synchronous **`RestClient`**. This yields the exact same high-concurrency, non-blocking performance scaling benefits as WebFlux, but allows us to keep a clean, synchronous, and easily testable codebase.
4. **CRUD REST APIs for Vendors** — Static configuration files are easier to audit and keep in version control.

### Future Evolution Paths

When traffic or system complexity increases significantly, the MVP will face bottlenecks. Below is the roadmap for scaling the service:

#### 1. Scaling Traffic (Throughput & Storage)
*   **Migrating the Broker**: For massive scale (e.g., 30K+ TPS ingestion with strict freshness SLAs), we would migrate from RabbitMQ to a distributed log-based broker like **Apache Kafka** or **Amazon Kinesis**. This allows high-throughput stream processing and partition-parallel consumer groups.
*   **Database Offloading**: If we need to persist and track the lifecycle status of millions of notifications, writing every state change to a single relational database will cause write lock contention. We would offload this to a highly scalable **NoSQL store** (like DynamoDB or Cassandra) for fast, low-latency writes, using **Redis** to cache frequent routing rules.

#### 2. Managing Complexity (Payloads & Routing)
*   **Dynamic Configurations**: Instead of editing configuration files and deploying code every time a vendor changes, we would implement a dynamic routing engine. Vendor URLs, authorization headers, and retry strategies would be stored in a database and cached in memory, exposing an Admin Portal for product managers to onboard new APIs without developer intervention.
*   **Schema Registry**: To prevent malformed payloads from business systems from breaking consumer workers, we would introduce a **Schema Registry (using Avro or Protobuf)**. This enforces strict serialization contracts on event types and request structures at the API Gateway boundary before they can ever reach the broker.

#### 3. Enhancing Reliability (Protecting the Target)
*   **Circuit Breakers**: To prevent retries from accidentally DDOSing a struggling vendor during an outage, we would implement Circuit Breakers (via libraries like **Resilience4j**). If a vendor fails repeatedly, the circuit opens, immediately routing new requests to the DLQ (or pausing consumer execution) instead of wasting thread pools on guaranteed timeouts.
*   **Outbound Rate Limiting (Traffic Shaping)**: Different vendors enforce distinct rate limits (e.g. "Max 100 requests/sec"). We would build a **Token Bucket rate-limiter** on our outbound dispatcher workers to pace HTTP deliveries, buffer excess traffic in queues, and avoid getting rate-limited by vendors.

#### 4. Observability & Auditing
*   **Distributed Tracing**: We would inject Correlation IDs (Trace IDs) at the gateway and propagate them through RabbitMQ headers down to the outgoing HTTP client. Integration with **OpenTelemetry**, **Zipkin**, or **Jaeger** would allow visual auditing of the entire lifecycle of a single notification.
*   **Dead Letter Automation**: Instead of manually checking the DLQ, we would configure automated alerting (paging engineers on-call when DLQ depth crosses a threshold) and build self-service replay tools to easily re-queue and re-process corrected messages from the DLQ.

## 6. Why RabbitMQ?
- **Native Support**: RabbitMQ supports exchanges, queue durability, message TTL, and Dead Letter Exchanges out of the box.
- **Ecosystem Integration**: Spring Boot provides excellent native support via `spring-boot-starter-amqp` and Jackson serialization.
- **Alternatives Considered**:
  - *In-memory scheduling*: Unsafe (loses messages on crash/restart).
  - *Database Polling*: Reliable but slow, introduces database load, and lacks native TTL backoff mechanics.
  - *Kafka*: Powerful, but over-engineered for a simple "at-least-once delivery queue" and harder to implement individual message TTLs.


## 7. Running Instructions

### Prerequisites
- **Java 25**
- **Docker** (For starting RabbitMQ locally or running integration tests via Testcontainers)

### Steps

1. **Start RabbitMQ**:
   Launch RabbitMQ via Docker Compose from the root directory:
   ```bash
   docker compose up -d
   ```
   *(Management UI is accessible at `http://localhost:15672` with credentials `guest` / `guest`)*

2. **Run Tests**:
   Execute the full unit and integration test suite:
   ```bash
   ./mvnw test
   ```

3. **Run Locally**:
   Start the Spring Boot microservice:
   ```bash
   ./mvnw spring-boot:run
   ```

4. **Stop RabbitMQ**:
   Tear down the local broker container:
   ```bash
   docker compose down
   ```

### Packaging
To build a production jar without running tests:
```bash
./mvnw clean package -DskipTests
```

### API Usage Example

**Request (POST /api/notifications):**
```bash
curl -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "vendorId": "ad-system",
    "body": "{\"event\": \"user_registered\", \"userId\": \"12345\"}"
  }'
```

**Response (202 Accepted):**
```json
{
  "notificationId": "afa22e58-0f6b-4499-ab20-e2ea1480e956",
  "status": "accepted"
}
```

### Simulating Traffic (Load Test)

You can run the provided load-testing script to automatically send a sequence of requests simulating various real-world scenarios:

```bash
./load-test.sh
```

The script fires the following request scenarios:
1. **Successful Delivery Paths**: Sends valid notification payloads to `ad-system`, `crm-system`, and `inventory-system` using `PUT` methods targeting `https://httpbin.org/put`. These succeed immediately (`200 OK`).
2. **Immediate Validation Failures**: Sends requests with missing bodies or unknown vendor IDs. These are rejected instantly at the REST API controller boundary with `400 Bad Request` and never enter RabbitMQ.
3. **Transient Failure & DLQ Path**: Sends a valid payload but overrides the destination to a fake domain (`https://this-is-a-fake-domain.com/api/callback`). This fails to connect, triggers the RabbitMQ backoff retry queues (re-consuming after 5s $\rightarrow$ 30s $\rightarrow$ 120s), and is eventually dispatched to the Dead-Letter Queue (`notification.dlq`) upon retries exhaustion.

## 8. Project Structure

```
src/main/java/com/example/notificationdispatcher/
├── NotificationDispatcherApplication.java   # Spring Boot entry point
├── config/
│   ├── RabbitMQConfig.java                  # Broker topology configuration
│   └── VendorProperties.java                # Multi-vendor configuration bindings
├── controller/
│   └── NotificationController.java          # REST entry point & request mapping
├── model/
│   ├── NotificationRequest.java             # Inbound API payload DTO
│   └── NotificationMessage.java             # Queue message representation
├── service/
│   ├── NotificationProducer.java            # RabbitMQ publisher service
│   ├── NotificationConsumer.java            # Message listener & retry routing
│   └── NotificationDispatcher.java          # HTTP client call handler
└── exception/
    ├── DispatchException.java               # Custom retryable/permanent error
    └── GlobalExceptionHandler.java          # API error mapper
```

## 9. AI Usage Statement

### Where the AI helped:
1. **Requirements Clarification** — Assisted in designing the specification framework based on assignment constraints.
2. **RabbitMQ Topology Setup** — Drafted the initial Spring configuration beans for RabbitMQ exchanges, queues, and bindings.
3. **Boilerplate Code Generation** — Scaffolded the basic model and DTO structures, reducing writing overhead.
4. **Test Outline Designing** — Outlined JUnit mock assertions and structure for the integration tests.

### What I rejected:
1. **PostgreSQL Integration** — Rejected database setups to keep the MVP simple.
2. **Reactive WebClient / Reactor WebFlux** — Rejected the high-complexity reactive streams (Mono/Flux) suggested by the AI, opting for simpler synchronous APIs.
3. **Redis Deduplication** — Removed redundant client-side caching to delegate duplicate handling to vendors.
4. **Full CRUD Registry API** — Used static YAML configurations for simpler configurations auditing.

### Key engineering decisions made by the developer:
1. **Explicit At-Least-Once Delivery**: Settled on "at-least-once" as the only logical design boundary.
2. **Queue-based Exponential Backoff**: Used RabbitMQ message TTLs and dead-letters for durable retry cycles.
3. **HTTP 4xx Non-retryable Exception Mapping**: Designed exception checks to automatically redirect permanent client errors straight to the DLQ.
4. **Static Configuration Merge Model**: Merged incoming API payloads with default vendor configs statically.
5. **Java Virtual Threads Concurrency Model**: Configured global Virtual Threads (Project Loom) to process servlet requests and RabbitMQ consumption concurrently, getting reactive-level scalability while retaining synchronous codebase simplicity.
