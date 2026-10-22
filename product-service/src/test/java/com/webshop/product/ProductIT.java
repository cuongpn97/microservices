package com.webshop.product;

import com.webshop.messaging.test.SharedContainers;
import com.webshop.messaging.test.TestJwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = TestJwts.SECRET_PROPERTY)
@AutoConfigureMockMvc
class ProductIT {

    static final String KEYBOARD = "11111111-1111-1111-1111-111111111111";
    static final String DISCONTINUED = "55555555-5555-5555-5555-555555555555";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerPostgres(registry);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void flywaySeedIsAvailableAndListingIsPublicAndActiveOnly() throws Exception {
        mvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].active", everyItem(is(true))));
        mvc.perform(get("/api/products/" + KEYBOARD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceVnd").value(1500000));
    }

    @Test
    void lookupReturnsInactiveProductWithFlagSoCallersCanValidateStatus() throws Exception {
        mvc.perform(get("/api/products/" + DISCONTINUED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/products/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void batchLookupReturnsOnlyExistingProducts() throws Exception {
        mvc.perform(get("/api/products/batch").param("ids", KEYBOARD, DISCONTINUED, UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void onlyAdminCanCreateOrUpdateProducts() throws Exception {
        String body = "{\"name\":\"Webcam\",\"description\":\"HD\",\"priceVnd\":990000}";
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", TestJwts.bearer(TestJwts.user(UUID.randomUUID()))))
                .andExpect(status().isForbidden());
        String location = mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", TestJwts.bearer(TestJwts.admin(UUID.randomUUID()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priceVnd").value(990000))
                .andReturn().getResponse().getContentAsString();
        String id = location.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(put("/api/products/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Webcam\",\"priceVnd\":1090000,\"active\":true}")
                        .header("Authorization", TestJwts.bearer(TestJwts.admin(UUID.randomUUID()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceVnd").value(1090000));
    }

    @Test
    void negativePriceIsRejected() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bad\",\"priceVnd\":-1}")
                        .header("Authorization", TestJwts.bearer(TestJwts.admin(UUID.randomUUID()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"priceVnd\":1}")
                        .header("Authorization", TestJwts.bearer(TestJwts.expired(UUID.randomUUID()))))
                .andExpect(status().isUnauthorized());
    }
}
