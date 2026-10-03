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

package de.greluc.krt.profit.basetool.ingest.contract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * The committed exchange contract documents — the OpenAPI document and the JSON Schemas — read once
 * from the classpath and served unchanged (REQ-XCH-001, REQ-XCH-011).
 */
@Component
public class ExchangeDocuments {

  /** The classpath location of the OpenAPI document. */
  static final String OPENAPI_LOCATION = "classpath:api/exchange-v1.openapi.json";

  /** The classpath pattern of the schema files. */
  static final String SCHEMA_PATTERN = "classpath:exchange/v1/schemas/*.schema.json";

  /** The shape of a schema file name. */
  private static final Pattern SCHEMA_NAME = Pattern.compile("^[a-z][a-z0-9-]*\\.schema\\.json$");

  private final byte[] openApi;
  private final @Unmodifiable Map<String, byte[]> schemas;

  /**
   * Reads every document once.
   *
   * @throws UncheckedIOException if a committed document cannot be read
   */
  public ExchangeDocuments() {
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    try {
      openApi = read(resolver.getResource(OPENAPI_LOCATION));
      Map<String, byte[]> loaded = new TreeMap<>();
      for (Resource resource : resolver.getResources(SCHEMA_PATTERN)) {
        String name = resource.getFilename();
        if (name != null && SCHEMA_NAME.matcher(name).matches()) {
          loaded.put(name, read(resource));
        }
      }
      schemas = Map.copyOf(loaded);
    } catch (IOException e) {
      throw new UncheckedIOException("The exchange contract documents cannot be read", e);
    }
  }

  /**
   * Returns the OpenAPI document.
   *
   * @return a copy of its bytes
   */
  public byte @NotNull [] openApi() {
    return openApi.clone();
  }

  /**
   * Returns one schema by its file name.
   *
   * @param name the file name, e.g. {@code item-ref.schema.json}
   * @return a copy of its bytes, or {@code null} when no committed schema has that name
   */
  public byte @Nullable [] schema(@NotNull String name) {
    byte[] schema = schemas.get(name);
    return schema == null ? null : schema.clone();
  }

  /**
   * Returns the names of every committed schema.
   *
   * @return the file names
   */
  public @NotNull @Unmodifiable Set<String> schemaNames() {
    return schemas.keySet();
  }

  /**
   * Reads a resource fully.
   *
   * @param resource the resource
   * @return its bytes
   * @throws IOException if it cannot be read
   */
  private static byte @NotNull [] read(@NotNull Resource resource) throws IOException {
    try (InputStream in = resource.getInputStream()) {
      return in.readAllBytes();
    }
  }
}
