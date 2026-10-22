package com.webshop.payment;

import com.webshop.messaging.test.SharedContainers;
import com.webshop.messaging.test.TestJwts;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {
        TestJwts.SECRET_PROPERTY,
        "webshop.messaging.outbox.scheduling-enabled=false",
        "webshop.messaging.retry.initial-delay=50ms",
        "webshop.messaging.retry.max-delay=200ms"
})
@AutoConfigureMockMvc
public abstract class AbstractPaymentIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerPostgres(registry);
        SharedContainers.registerRabbit(registry);
    }
}
