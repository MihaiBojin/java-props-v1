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
import static java.util.Objects.nonNull;
import static java.util.logging.Level.SEVERE;

import com.mihaibojin.props.core.converters.PropTypeConverter;
import com.mihaibojin.props.core.converters.StringConverter;
import com.mihaibojin.props.core.resolvers.PropertyFileResolver;
import com.mihaibojin.props.core.resolvers.Resolver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class Props implements AutoCloseable {
  private static final Logger log = Logger.getLogger(PropertyFileResolver.class.getName());
  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
  // TODO(mihaibojin): boundProps is read-heavy, find a better data structure
  private final Map<String, AbstractProp<?>> boundProps = new ConcurrentHashMap<>();
  private final Map<String, String> propIdToResolver = new ConcurrentHashMap<>();
  private final CountDownLatch latch = new CountDownLatch(1);

  private final List<String> prioritizedResolvers;
  private final Map<String, Resolver> resolvers;
  private final Duration shutdownGracePeriod;
  private final Duration refreshInterval;

  private Props(
      LinkedHashMap<String, Resolver> resolvers,
      Duration refreshInterval,
      Duration shutdownGracePeriod) {
    this.resolvers = Collections.unmodifiableMap(resolvers);

    // generate a list of resolver IDs, ordered by priority (highest first)
    ArrayList<String> ids = new ArrayList<>(resolvers.keySet());
    Collections.reverse(ids);
    prioritizedResolvers = Collections.unmodifiableList(ids);

    this.refreshInterval = refreshInterval;
    this.shutdownGracePeriod = shutdownGracePeriod;

    // ensure the non-auto-update resolvers have their values loaded
    CompletableFuture.runAsync(
        () -> {
          this.resolvers.entrySet().parallelStream().forEach(Props::safeReload);
          latch.countDown();
        });

    // and schedule the update-able ones to run periodically
    // TODO: this can be risky if the Default ForkJoinPool is busy; refactor to use own executor
    executor.scheduleAtFixedRate(
        () -> refreshResolvers(this.resolvers), 0, refreshInterval.toSeconds(), TimeUnit.SECONDS);
  }

  /**
   * Safely reload all the values managed by the specified {@link Resolver} and logs any exceptions
   */
  private static Set<String> safeReload(Entry<String, Resolver> res) {
    try {
      return res.getValue().reload();
    } catch (Throwable t) {
      log.log(SEVERE, "Unexpected error reloading props from " + res.getKey(), t);
    }
    return Set.of();
  }

  /** Convenience method for configuring {@link Props} registry objects */
  public static Factory factory() {
    return new Factory();
  }

  /**
   * Binds the specified prop to the current {@link Props} registry.
   *
   * <p>If a non-null <code>resolverId</code> is specified, it will link the prop to that resolver.
   *
   * @throws IllegalArgumentException if attempting to bind a {@link Prop} for a key which was
   *     already bound to another object. This is to encourage efficiency and define a single object
   *     per key, keeping memory usage low(er). Use the {@link #retrieve(String)} and {@link
   *     #retrieve(String, Class)} methods to get a pre-existing instance.
   * @throws IllegalArgumentException if the specified <code>resolverId</code> is not know to the
   *     registry.
   */
  public <T, R extends AbstractProp<T>> R bind(R prop, String resolverId) {
    AbstractProp<?> oldProp = boundProps.putIfAbsent(prop.key, prop);
    if (nonNull(oldProp) && oldProp != prop) {
      throw new IllegalArgumentException(
          "Prop with key "
              + prop.key
              + " was already registered via "
              + oldProp.getClass().getSimpleName());
    }

    if (nonNull(resolverId)) {
      // only register the prop with a resolver, if the id is non-null and valid
      validateResolver(resolverId);
      propIdToResolver.put(prop.key(), resolverId);
    }

    // TODO(mihaibojin): lazy load, block on get
    update(prop);

    return prop;
  }

  /**
   * Convenience method for users who need to bind {@link Prop}s manually.
   *
   * @see #bind(AbstractProp, String)
   */
  public <T, R extends AbstractProp<T>> R bind(R prop) {
    return bind(prop, null);
  }

  /**
   * @return an existing (bound) {@link Prop} object, or <code>null</code> if one does not exist for
   *     the specified key
   */
  public Prop<?> retrieve(String key) {
    return boundProps.get(key);
  }

  /**
   * @throws ClassCastException if the property key is associated with a different type
   * @return an existing (bound) {@link Prop} object, cast to the expected type, or <code>null
   *     </code> if a prop was not bound for the specified key
   */
  public <T, R extends Prop<T>> R retrieve(String key, Class<R> clz) {
    return clz.cast(boundProps.get(key));
  }

  /**
   * Update the {@link Prop}'s current value
   *
   * @return true if the property was updated, or false if it kept its value
   */
  protected <T> boolean update(AbstractProp<T> prop) {
    // determine if the prop is linked to a specific resolver
    String resolverId = propIdToResolver.get(prop.key());

    final Optional<T> propValue = resolveProp(prop, resolverId);
    if (propValue.isEmpty()) {
      // nothing to update if a value was not found
      return false;
    }

    // retrieve the prop's current value
    T currentValue = propValue.get();

    // determine if the actual value has changed, return otherwise
    if (Objects.equals(prop.value(), currentValue)) {
      return false;
    }

    // update the current value, if necessary
    prop.setValue(currentValue);

    return true;
  }

  /** Search all resolvers for a value */
  <T> Optional<T> resolveProp(AbstractProp<T> prop, String resolverId) {
    if (!waitForInitialLoad()) {
      return Optional.empty();
    }

    if (nonNull(resolverId)) {
      // if the prop is bound to a single resolver, return it on the spot
      return resolvers.get(resolverId).get(prop.key).map(prop::decode);
    }

    for (String id : prioritizedResolvers) {
      Optional<String> value = resolvers.get(id).get(prop.key());
      if (value.isPresent()) {
        log.log(Level.FINER, format("%s resolved by %s", prop.key(), id));

        // return an optional which decodes the value on get
        // the reason for lazy decoding is to reduce confusion in a potential stacktrace
        // since the problem would be related to decoding the retrieved string and not with
        // resolving the value
        return value.map(prop::decode);
      }
    }

    return Optional.empty();
  }

  /**
   * @throws IllegalArgumentException if the specified id is not known to the current {@link Props}
   *     registry
   */
  private void validateResolver(String resolverId) {
    if (!resolvers.containsKey(resolverId)) {
      throw new IllegalArgumentException(
          "Resolver " + resolverId + " is not registered with the current registry");
    }
  }

  /**
   * Returns a {@link LinkedHashMap} containing mappings for each resolver which contained a value
   * for the specified {@link Prop}'s key.
   *
   * <p>The map can be iterated over in and will return {@link Resolver}s order by priority,
   * lowest-to-highest.
   */
  public <T> Map<String, T> resolvePropLayers(AbstractProp<T> prop) {
    // read all the values from the registry
    Map<String, T> layers = new LinkedHashMap<>();

    // process all layers and transform them into the final type
    for (Entry<String, Resolver> entry : resolvers.entrySet()) {
      Optional<String> value = entry.getValue().get(prop.key());
      if (value.isPresent()) {
        T resolved = prop.decode(value.get());
        prop.validateBeforeSet(resolved);
        layers.put(entry.getKey(), resolved);
      }
    }

    return layers;
  }

  /** @return true if the wait completed successfully */
  private <T> boolean waitForInitialLoad() {
    try {
      // TODO: replace this with a lazy load
      latch.await(refreshInterval.toSeconds(), TimeUnit.SECONDS);
      return true;

    } catch (InterruptedException e) {
      log.log(SEVERE, "Could not resolve in time", e);
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Refreshes values from all the registered {@link Resolver}s */
  private void refreshResolvers(Map<String, Resolver> resolvers) {
    Set<? extends AbstractProp<?>> toUpdate =
        resolvers
            .entrySet()
            .parallelStream()
            .filter(r -> r.getValue().isReloadable())
            .map(Props::safeReload)
            .flatMap(keys -> keys.stream().map(boundProps::get).filter(Objects::nonNull))
            // we need to collect since we need all layers to have finished their update cycle
            // before reading them
            .collect(Collectors.toSet());

    // TODO(mihaibojin): in the future, this will be replace with a better mechanism that keeps
    // track of which resolver owns each prop
    toUpdate.forEach(this::update);
  }

  /**
   * Call this method when shutting down the app to stop this class's {@link
   * ScheduledExecutorService}
   */
  @Override
  public void close() {
    executor.shutdown();
    try {
      executor.awaitTermination(shutdownGracePeriod.toSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      log.warning("Interrupted while waiting for executor shutdown; terminating...");
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /** Convenience method for building string {@link Prop}s */
  public Builder<String> prop(String key) {
    return new Builder<>(key, new StringConverter() {});
  }

  /** Convenience method for building {@link Prop}s */
  public <T> Builder<T> prop(String key, PropTypeConverter<T> converter) {
    return new Builder<>(key, converter);
  }

  /** Factory class for building {@link Props} registry classes */
  public static class Factory {
    private final LinkedHashMap<String, Resolver> resolvers = new LinkedHashMap<>();
    private Duration refreshInterval = Duration.ofSeconds(30);
    private Duration shutdownGracePeriod = Duration.ofSeconds(30);

    private Factory() {}

    /** Adds a resolver and its identifier to be used in the registry object being built */
    public Factory withResolver(String id, Resolver resolver) {
      resolvers.put(id, resolver);
      return this;
    }

    /** Adds a resolver and identifies it by its {@link Resolver#defaultId()} */
    public Factory withResolver(Resolver resolver) {
      resolvers.put(resolver.defaultId(), resolver);
      return this;
    }

    /**
     * Allows customizing the refresh interval at which auto-update-able {@link
     * com.mihaibojin.props.core.resolvers.Resolver}s are refreshed
     */
    public Factory refreshInterval(Duration interval) {
      refreshInterval = interval;
      return this;
    }

    /**
     * Allows customizing the shutdown grace period, before the executor is forcefully shut down.
     */
    public Factory shutdownGracePeriod(Duration shutdownGracePeriod) {
      this.shutdownGracePeriod = shutdownGracePeriod;
      return this;
    }

    /**
     * Creates the {@link Props} object
     *
     * @throws IllegalStateException if the method is called without registering any {@link
     *     Resolver}s
     */
    public Props build() {
      if (resolvers.isEmpty()) {
        throw new IllegalStateException("Cannot initialize Props without any Resolvers");
      }
      return new Props(resolvers, refreshInterval, shutdownGracePeriod);
    }
  }

  /** Builder class for creating custom {@link Prop}s from the current {@link Props} registry */
  public class Builder<T> {
    public final String key;
    public final PropTypeConverter<T> converter;
    private T defaultValue;
    private String description;
    private boolean isRequired;
    private boolean isSecret;
    private String resolverId;

    private Builder(String key, PropTypeConverter<T> converter) {
      this.key = key;
      this.converter = converter;
    }

    public Builder<T> resolver(String resolverId) {
      validateResolver(resolverId);
      this.resolverId = resolverId;
      return this;
    }

    public Builder<T> defaultValue(T defaultValue) {
      this.defaultValue = defaultValue;
      return this;
    }

    public Builder<T> description(String description) {
      this.description = description;
      return this;
    }

    public Builder<T> isRequired(boolean isRequired) {
      this.isRequired = isRequired;
      return this;
    }

    public Builder<T> isSecret(boolean isSecret) {
      this.isSecret = isSecret;
      return this;
    }

    /**
     * Constructs the {@link Prop}, binds it to the current {@link Props} instance, and returns it
     */
    public Prop<T> build() {
      return bind(
          new AbstractProp<>(key, defaultValue, description, isRequired, isSecret) {
            @Override
            public T decode(String value) {
              return converter.decode(value);
            }

            @Override
            public String encode(T value) {
              return converter.encode(value);
            }
          },
          resolverId);
    }

    /**
     * Reads the {@link Prop}'s value without binding it to the current {@link Props} instance.
     *
     * <p>This is a convenience method that can be used when you want to retrieve a value only once.
     */
    public Optional<T> readOnce() {
      AbstractProp<T> prop =
          new AbstractProp<>(key, defaultValue, description, isRequired, isSecret) {
            @Override
            public T decode(String value) {
              return converter.decode(value);
            }

            @Override
            public String encode(T value) {
              return converter.encode(value);
            }
          };

      return resolveProp(prop, resolverId);
    }
  }
}
