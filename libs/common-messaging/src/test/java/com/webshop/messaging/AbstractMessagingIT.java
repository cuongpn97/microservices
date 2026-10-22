package com.webshop.messaging;

import com.webshop.messaging.test.SharedContainers;
import com.webshop.messaging.testapp.MessagingTestApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Lớp cơ sở để mọi IT dùng CHUNG một Spring context (cùng cache key). Nếu mỗi lớp test tự khai báo
 * {@code @DynamicPropertySource}, Spring tạo nhiều context và nhiều listener cùng consume một queue,
 * khiến message bị chia cho handler của context khác.
 */
@SpringBootTest(classes = MessagingTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("messaging-test")
abstract class AbstractMessagingIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerPostgres(registry);
        SharedContainers.registerRabbit(registry);
    }
}
