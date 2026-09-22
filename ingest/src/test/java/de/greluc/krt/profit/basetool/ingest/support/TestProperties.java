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

package de.greluc.krt.profit.basetool.ingest.support;

import de.greluc.krt.profit.basetool.ingest.config.ClientIdentityProperties;
import de.greluc.krt.profit.basetool.ingest.config.IngestProperties;
import de.greluc.krt.profit.basetool.ingest.config.RateLimitProperties;
import de.greluc.krt.profit.basetool.ingest.config.ServiceAccountProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Builds the gateway's {@code @ConfigurationProperties} records for unit tests the way Spring
 * builds them: through the real {@link Binder}, so every value a test does not name comes from the
 * record's own {@code @DefaultValue} rather than from a second copy of the defaults kept here.
 *
 * <p>Overrides are given as alternating relaxed property names and values relative to the record's
 * prefix, e.g. {@code ingest("max-handoffs-per-subject", "3")}. A list is a comma-separated value,
 * exactly as it would be in {@code application.yml}.
 */
public final class TestProperties {

  private TestProperties() {
    // Test-support holder — not instantiable.
  }

  /**
   * Binds {@code app.ingest} with a local backend and frontend URL plus the given overrides.
   *
   * @param overrides alternating relative property names and values
   * @return the bound properties
   */
  public static @NotNull IngestProperties ingest(String... overrides) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("backend-base-url", "http://localhost:19999");
    values.put("frontend-base-url", "http://localhost:18081");
    return bind("app.ingest", IngestProperties.class, values, overrides);
  }

  /**
   * Binds {@code app.rate-limit} with the given overrides.
   *
   * @param overrides alternating relative property names and values
   * @return the bound properties
   */
  public static @NotNull RateLimitProperties rateLimit(String... overrides) {
    return bind("app.rate-limit", RateLimitProperties.class, new LinkedHashMap<>(), overrides);
  }

  /**
   * Binds {@code app.ingest.client-identity} with the given overrides.
   *
   * @param overrides alternating relative property names and values
   * @return the bound properties
   */
  public static @NotNull ClientIdentityProperties clientIdentity(String... overrides) {
    return bind(
        "app.ingest.client-identity",
        ClientIdentityProperties.class,
        new LinkedHashMap<>(),
        overrides);
  }

  /**
   * Binds {@code app.ingest.service-account} with the given overrides.
   *
   * @param overrides alternating relative property names and values
   * @return the bound properties
   */
  public static @NotNull ServiceAccountProperties serviceAccount(String... overrides) {
    return bind(
        "app.ingest.service-account",
        ServiceAccountProperties.class,
        new LinkedHashMap<>(),
        overrides);
  }

  /**
   * Binds one record under {@code prefix} from the base values plus the overrides, creating it from
   * its defaults when nothing at all is set.
   *
   * @param prefix the configuration prefix
   * @param type the record type
   * @param base values applied before the overrides
   * @param overrides alternating relative property names and values
   * @param <T> the record type
   * @return the bound instance
   */
  private static <T> @NotNull T bind(
      @NotNull String prefix,
      @NotNull Class<T> type,
      @NotNull Map<String, String> base,
      String... overrides) {
    if (overrides.length % 2 != 0) {
      throw new IllegalArgumentException("overrides must be name/value pairs");
    }
    for (int i = 0; i < overrides.length; i += 2) {
      base.put(overrides[i], overrides[i + 1]);
    }
    Map<String, String> qualified = new LinkedHashMap<>();
    base.forEach((name, value) -> qualified.put(prefix + "." + name, value));
    return new Binder(new MapConfigurationPropertySource(qualified))
        .bindOrCreate(prefix, Bindable.of(type));
  }
}
