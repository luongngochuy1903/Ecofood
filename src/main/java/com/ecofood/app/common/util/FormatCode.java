package com.ecofood.app.common.util;

/**
 * Matches DCMP's real Service_Core FormatCode exactly — zero-pads a sequence
 * value and prepends a prefix, e.g. byPaddingZeros(1, 4, "TE") -> "TE0001".
 */
public final class FormatCode {

  private FormatCode() {}

  public static String byPaddingZeros(long value, int paddingLength, String prefix) {
    String format = "%0" + paddingLength + "d";
    return prefix + String.format(format, value);
  }
}
