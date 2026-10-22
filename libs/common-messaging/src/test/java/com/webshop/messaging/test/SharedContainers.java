package com.webshop.messaging.test;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Container dùng chung (singleton, lazy) cho integration test trong một JVM: khởi động 1 lần, Ryuk dọn khi JVM thoát.
 * Dùng holder class để service chỉ cần PostgreSQL (product, cart...) không phải có Testcontainers RabbitMQ
 * trên classpath. Image version giống docker-compose.yml để test sát môi trường chạy thật.
 */
public final class SharedContainers {

    private SharedContainers() {
    }

    private static final class Postgres {
        static final PostgreSQLContainer<?> INSTANCE = start(
                new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                        .withDatabaseName("testdb")
                        .withUsername("test")
                        .withPassword("test"));

        private static PostgreSQLContainer<?> start(PostgreSQLContainer<?> container) {
            container.start();
            return container;
        }
    }

    private static final class Rabbit {
        static final RabbitMQContainer INSTANCE = start(
                new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.1-management-alpine")));

        private static RabbitMQContainer start(RabbitMQContainer container) {
            container.start();
            return container;
        }
    }

    public static PostgreSQLContainer<?> postgres() {
        return Postgres.INSTANCE;
    }

    public static RabbitMQContainer rabbit() {
        return Rabbit.INSTANCE;
    }

    public static void registerPostgres(DynamicPropertyRegistry registry) {
        PostgreSQLContainer<?> pg = postgres();
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
    }

    public static void registerRabbit(DynamicPropertyRegistry registry) {
        RabbitMQContainer rabbit = rabbit();
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
    }
}
