package com.lab.helloservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Kept intentionally minimal: Spring Boot 4 split web test support into
 * several new modules (spring-boot-starter-webmvc-test for MockMvc,
 * spring-boot-resttestclient for the TestRestTemplate replacement) that
 * were still settling at the time this was written. Endpoint behavior is
 * verified with a real curl smoke test instead (see README.md) rather than
 * chasing that still-new API surface here.
 */
@SpringBootTest
class HelloServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
