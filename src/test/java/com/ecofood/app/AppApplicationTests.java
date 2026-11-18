package com.ecofood.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"ecofood.notifications.events-enabled=false", "ecofood.complaints.projection-enabled=false"})
class AppApplicationTests {

	@Test
	void contextLoads() {
	}

}
