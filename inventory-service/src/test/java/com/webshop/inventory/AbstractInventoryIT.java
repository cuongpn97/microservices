package com.webshop.inventory;

import com.webshop.messaging.test.SharedContainers;
import com.webshop.messaging.test.TestJwts;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Base class chung → mọi IT của service dùng chung một Spring context (một listener duy nhất trên queue). */
@SpringBootTest(properties = {
        TestJwts.SECRET_PROPERTY,
        // Giữ reply trong outbox để test kiểm tra được; publisher được test riêng trong common-messaging.
        "webshop.messaging.outbox.scheduling-enabled=false",
        "webshop.messaging.retry.initial-delay=50ms",
        "webshop.messaging.retry.max-delay=200ms"
})
@AutoConfigureMockMvc
public abstract class AbstractInventoryIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerPostgres(registry);
        SharedContainers.registerRabbit(registry);
    }
}
