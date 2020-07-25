/*
 * Copyright 2020 Mihai Bojin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mihaibojin.props.core;

import static java.lang.String.format;
import static java.util.Objects.isNull;

import com.mihaibojin.props.core.annotations.Nullable;
import java.util.Optional;

public abstract class AbstractProp<T> implements Prop<T> {

  public final String key;
  @Nullable private final T defaultValue;
  @Nullable private final String description;
  private final boolean isRequired;
  private final boolean isSecret;
  @Nullable private volatile T currentValue;

  /**
   * Constructs a new property class.
   *
   * @throws IllegalStateException if the constructed object is in an invalid state
   */
  protected AbstractProp(
      String key,
      @Nullable T defaultValue,
      @Nullable String description,
      boolean isRequired,
      boolean isSecret) {
    this.key = key;
    this.defaultValue = defaultValue;
    if (isNull(key)) {
      throw new IllegalStateException("The property's key cannot be null");
    }

    this.description = description;
    this.isRequired = isRequired;
    this.isSecret = isSecret;
  }

  /**
   * Validates any updates to a property's value.
   *
   * <p>This method can be overridden for more advanced validation requirements.
   *
   * @throws ValidationException when validation fails
   */
  protected void validateBeforeSet(T value) {}

  /**
   * This method validates the property's value before returning it.
   *
   * <p>This method can be overridden for more advanced validation requirements. In that case, the
   * overriding implementation should still call this method via <code>super.validateOnGet()</code>,
   * to preserve the non-null value required property guarantee.
   *
   * @throws ValidationException when validation fails
   */
  protected void validateBeforeGet(T value) {
    // if the Prop is required, a value must be available
    if (isRequired && isNull(value)) {
      throw new ValidationException(
          format("Prop '%s' is required, but neither a value or a default were specified", key));
    }
  }

  /** Update this property's value. */
  void setValue(T updateValue) {
    // ensure the value is validated before it is set
    validateBeforeSet(updateValue);

    synchronized (this) {
      currentValue = updateValue;
    }
  }

  /** Retrieve this property's value. */
  @Nullable
  T getValueInternal() {
    synchronized (this) {
      return currentValue;
    }
  }

  /**
   * This method will return an empty <code>Optional</code> if called on a {@link Prop} which was
   * not bound to a {@link Props} registry.
   *
   * @return an {@link Optional} representing the current value
   */
  @Override
  public Optional<T> value() {
    Optional<T> result =
        Optional.ofNullable(currentValue).or(() -> Optional.ofNullable(defaultValue));

    // ensure the Prop is in a valid state before returning it
    validateBeforeGet(result.orElse(null));

    return result;
  }

  @Override
  public String key() {
    return key;
  }

  @Override
  @Nullable
  public String description() {
    return description;
  }

  @Override
  public boolean isRequired() {
    return isRequired;
  }

  @Override
  public boolean isSecret() {
    return isSecret;
  }

  /** Helper method for redacting secret values. */
  protected String redact(T value) {
    return "<redacted>";
  }

  @Override
  public String toString() {
    // copy the value to avoid an NPE caused by a race condition
    T currentValue = this.currentValue;
    if (currentValue != null) {
      return format(
          "Prop{%s=(%s)%s}",
          key,
          currentValue.getClass().getSimpleName(),
          isSecret() ? redact(currentValue) : currentValue);
    }

    return format("Prop{%s=null}", key);
  }
}