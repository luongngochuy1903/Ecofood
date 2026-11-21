package com.ecofood.app.customer;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

class CustomerSettingsTest {
  @ParameterizedTest
  @CsvSource({
      "https://food.example.org, https://food.example.org",
      "https://food.example.org/, https://food.example.org",
      "http://localhost:3000/, http://localhost:3000",
      "http://127.0.0.1:3000/, http://127.0.0.1:3000",
      "http://food.localhost:3000/, http://food.localhost:3000"
  })
  void trustedOriginsKeepTheirAuthorityAndRemoveOnlyTrailingSlash(String input, String expected) {
    var settings = new CustomerSettings("", "", input, 24);
    assertEquals(expected, settings.base());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "http://food.example.org/", "https://food.example.org/path",
      "https://food.example.org//", "https://food.example.org/?next=external",
      "https://food.example.org/#fragment", "https://user@food.example.org/"
  })
  void invalidOriginsAreRejectedBeforeNormalization(String input) {
    var settings = new CustomerSettings("", "", input, 24);
    var failure = assertThrows(ResponseStatusException.class, settings::base);
    assertEquals(503, failure.getStatusCode().value());
  }
}
