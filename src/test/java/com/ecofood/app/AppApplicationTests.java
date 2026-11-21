package com.ecofood.app;

import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

class AppApplicationTests {

	@Test
	void mainPassesArgumentsToSpringApplication() {
		String[] args = {"--server.port=0"};

		// Verify the entry point without starting Spring or external connections.
		try (var springApplication = mockStatic(SpringApplication.class)) {
			AppApplication.main(args);

			springApplication.verify(() -> SpringApplication.run(AppApplication.class, args));
		}
	}

}
