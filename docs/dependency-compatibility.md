# Phiên bản phụ thuộc và lý do chọn

| Thành phần | Phiên bản | Ghi chú |
|---|---|---|
| Java (target) | 21 (`maven.compiler.release=21`) | LTS. Đã build/test trên JDK 25.0.4 với `--release 21`; image runtime là `eclipse-temurin:21-jre-alpine`. |
| Maven | 3.9.16 (Maven Wrapper 3.3.4) | Không cần cài Maven toàn cục: `./mvnw`. |
| Spring Boot | 3.5.16 | Dòng 3.x mới nhất tại thời điểm tạo. Quản lý version cho Spring Framework 6.2, Spring Security 6.5, Spring AMQP 3.2.12, Hibernate 6.6, Flyway 11.7.2, PostgreSQL JDBC 42.7.11, Testcontainers 1.21.4, Mockito 5.17, Byte Buddy 1.17.8 (hỗ trợ JDK 25). |
| Spring Cloud | 2025.0.3 (Northfields) | Release train tương thích Boot 3.5. Dùng artifact mới `spring-cloud-starter-gateway-server-webflux` và prefix cấu hình `spring.cloud.gateway.server.webflux.*`. |
| Resilience4j | 2.3.0 (BOM) | `resilience4j-spring-boot3` + `resilience4j-micrometer`. Circuit breaker cấu hình qua YAML; retry tạo bằng code (`ResilientCaller`) để dùng `IntervalFunction.ofExponentialRandomBackoff` (backoff mũ + jitter) rõ ràng. |
| PostgreSQL | 16 (`postgres:16-alpine`) | Cùng image cho docker compose và Testcontainers. |
| RabbitMQ | 4.1 (`rabbitmq:4.1-management-alpine`) | Classic queue durable (queue transient non-exclusive đã bị deprecate ở 4.x; hệ thống không dùng). |

Nguyên tắc:

- Không khai báo version riêng cho thư viện đã có trong BOM của Spring Boot/Spring Cloud — tránh lệch version (ví dụ Jackson, Netty).
- Version được pin trong `pom.xml` gốc; mọi module kế thừa.
- Gateway là ứng dụng WebFlux nên KHÔNG phụ thuộc `common-web` (Servlet).
- Testcontainers dùng Docker Desktop (đã kiểm tra với Docker Engine 28.0.1 trên Windows 11).

## Search (profile `search`)

| Thành phần | Phiên bản | Ghi chú |
|---|---|---|
| Elasticsearch | 8.18.8 (`docker.elastic.co/elasticsearch/elasticsearch`) | Trùng version với `elasticsearch-java` mà Spring Boot 3.5.16 quản lý (`elasticsearch-client.version`). Client 8.x chỉ đảm bảo tương thích với server 8.x. |
| Kafka broker | 3.9.2 (`apache/kafka`, KRaft, không ZooKeeper) | Trùng `kafka-clients` 3.9.2 của Boot 3.5.16 (Spring Kafka 3.3.16). Cùng image cho compose và Testcontainers. |
| Debezium | 3.5.2.Final (`quay.io/debezium/connect`) | Bản stable mới nhất; build trên Kafka Connect 4.1 — worker 4.x nói chuyện được với broker 3.9. Dùng image chính thức trên quay.io. |
| PostgreSQL connector | `pgoutput` | Plugin logical decoding có sẵn trong PostgreSQL ≥ 10, không cần cài extension (`wal2json`/`decoderbufs`). |
