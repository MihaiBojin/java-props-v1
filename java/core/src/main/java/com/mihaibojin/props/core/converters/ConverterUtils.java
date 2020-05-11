package com.mihaibojin.props.core.converters;

import static java.util.logging.Level.SEVERE;

import java.text.NumberFormat;
import java.text.ParseException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class ConverterUtils {
  private static final Logger log = Logger.getLogger(ConverterUtils.class.getName());

  /**
   * Attempts to parse a {@link String} to a {@link Number} and returns <code>null</code> if it
   * cannot.
   *
   * <p>This methods logs a {@link java.util.logging.Level#SEVERE} event instead of throwing {@link
   * ParseException}s.
   */
  static Number safeParseNumber(String value) {
    try {
      return NumberFormat.getInstance().parse(value);
    } catch (ParseException e) {
      log.log(SEVERE, "Could not parse " + value + " to a number", e);
      return null;
    }
  }

  /**
   * Attempts to parse a {@link String} to an {@link Instant} and returns <code>null</code> if it
   * cannot.
   *
   * <p>This methods logs a {@link java.util.logging.Level#SEVERE} event instead of throwing {@link
   * DateTimeParseException}s.
   */
  static Instant safeParseInstant(String value) {
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (DateTimeParseException e) {
      log.log(SEVERE, "Could not parse " + value + " as a valid date/time", e);
      return null;
    }
  }

  /** Splits a {@link String} by the given <code>separator</code> */
  static List<String> splitString(String input, String separator) {
    return List.of(input.split(Pattern.quote(separator)));
  }

  /**
   * Splits a {@link String} by the given <code>separator</code>, casts every item using the
   * specified <code>mapper</code> func and returns a {@link List} of numbers
   */
  static <T extends Number> List<T> splitStringAsNumbers(
      String input, String separator, Function<Number, T> mapper) {
    return Stream.of(input.split(Pattern.quote(separator)))
        .map(ConverterUtils::safeParseNumber)
        .filter(Objects::nonNull)
        .map(mapper)
        .collect(Collectors.toList());
  }
}
