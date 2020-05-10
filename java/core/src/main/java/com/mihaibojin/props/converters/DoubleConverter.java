package com.mihaibojin.props.converters;

import static com.mihaibojin.props.converters.ConverterUtils.safeParseNumber;
import static java.util.Objects.requireNonNull;

/** Converter that casts the inputted {@link String} to an {@link Double} value */
public interface DoubleConverter extends PropTypeConverter<Double> {
  @Override
  default Double decode(String value) {
    return requireNonNull(safeParseNumber(value)).doubleValue();
  }
}
