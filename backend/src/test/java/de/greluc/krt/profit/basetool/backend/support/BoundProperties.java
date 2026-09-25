/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.support;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Builds a {@code @ConfigurationProperties} record for a unit test through Spring's {@link Binder},
 * so every component the test does not set takes its production {@code @DefaultValue}. Bean
 * validation is not applied.
 */
public final class BoundProperties {

  private BoundProperties() {}

  /**
   * Binds {@code type} with nothing configured, so every component takes its default.
   *
   * @param type the properties record, annotated with {@link ConfigurationProperties}
   * @param <T> the record type
   * @return the record as the application would bind it from an empty environment
   */
  @NotNull
  public static <T> T defaults(@NotNull Class<T> type) {
    return bind(type, Map.of());
  }

  /**
   * Binds {@code type} from {@code values}, keyed relative to the record's prefix (for example
   * {@code "slow-request-threshold-ms"} for {@code app.logging.slow-request-threshold-ms}). A
   * {@link Collection} value is bound as a list; anything else through its {@code toString()}.
   *
   * @param type the properties record, annotated with {@link ConfigurationProperties}
   * @param values the configured keys and their values
   * @param <T> the record type
   * @return the bound record, with every key absent from {@code values} at its default
   */
  @NotNull
  public static <T> T bind(@NotNull Class<T> type, @NotNull Map<String, ?> values) {
    String prefix = prefixOf(type);
    Map<String, Object> source = new LinkedHashMap<>();
    values.forEach(
        (key, value) -> {
          if (value instanceof Collection<?> list) {
            int i = 0;
            for (Object element : list) {
              source.put(prefix + "." + key + "[" + i++ + "]", String.valueOf(element));
            }
          } else {
            source.put(prefix + "." + key, String.valueOf(value));
          }
        });
    return new Binder(new MapConfigurationPropertySource(source))
        .bindOrCreate(prefix, Bindable.of(type));
  }

  /**
   * Binds {@code type} with a single configured key.
   *
   * @param type the properties record, annotated with {@link ConfigurationProperties}
   * @param key the key relative to the record's prefix
   * @param value its value; a {@link Collection} is bound as a list
   * @param <T> the record type
   * @return the bound record, every other component at its default
   */
  @NotNull
  public static <T> T bind(@NotNull Class<T> type, @NotNull String key, @NotNull Object value) {
    return bind(type, Map.of(key, value));
  }

  @NotNull
  private static String prefixOf(@NotNull Class<?> type) {
    ConfigurationProperties annotation = type.getAnnotation(ConfigurationProperties.class);
    if (annotation == null) {
      throw new IllegalArgumentException(
          type.getName() + " is not a @ConfigurationProperties type");
    }
    return annotation.prefix().isEmpty() ? annotation.value() : annotation.prefix();
  }
}
