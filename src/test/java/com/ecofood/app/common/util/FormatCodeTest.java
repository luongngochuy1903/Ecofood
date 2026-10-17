package com.ecofood.app.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FormatCodeTest {

  @Test
  void padsValueWithLeadingZerosAndPrefix() {
    assertThat(FormatCode.byPaddingZeros(1, 4, "TE")).isEqualTo("TE0001");
  }

  @Test
  void doesNotTruncateWhenValueIsWiderThanPaddingLength() {
    assertThat(FormatCode.byPaddingZeros(123456, 4, "TE")).isEqualTo("TE123456");
  }

  @Test
  void handlesZero() {
    assertThat(FormatCode.byPaddingZeros(0, 4, "TE")).isEqualTo("TE0000");
  }

  @Test
  void supportsEmptyPrefix() {
    assertThat(FormatCode.byPaddingZeros(7, 3, "")).isEqualTo("007");
  }
}
