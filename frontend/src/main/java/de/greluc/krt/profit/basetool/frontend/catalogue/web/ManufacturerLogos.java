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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The Fan Kit manufacturer logos (REQ-P4K-022, ADR-0338): a versioned mapping from a
 * manufacturer's P4K GUID to an unmodified Fan Kit PNG shipped under {@value #LOGO_PATH}. A
 * manufacturer without an entry shows its abbreviation badge. Templates reach it as {@code
 * @manufacturerLogos}.
 */
@Component("manufacturerLogos")
public class ManufacturerLogos {

  /** Where the mapping lives on the classpath. */
  static final String MAPPING_LOCATION = "catalogue/manufacturer-logos.json";

  /** The URL path the PNGs are served under, below {@code static/}. */
  static final String LOGO_PATH = "/images/manufacturers/";

  /** The PNG file of each mapped P4K GUID. */
  private final @NotNull @Unmodifiable Map<UUID, String> files;

  /**
   * Loads the committed mapping from {@value #MAPPING_LOCATION}.
   *
   * @param objectMapper the application's Jackson mapper
   */
  @Autowired
  public ManufacturerLogos(@NotNull ObjectMapper objectMapper) {
    this(objectMapper, new ClassPathResource(MAPPING_LOCATION));
  }

  /**
   * Loads a mapping from an explicit resource.
   *
   * @param objectMapper the mapper that reads the JSON object of GUID to file name
   * @param resource the mapping
   * @throws IllegalStateException when the mapping cannot be read or holds a malformed GUID
   */
  ManufacturerLogos(@NotNull ObjectMapper objectMapper, @NotNull Resource resource) {
    this.files = read(objectMapper, resource);
  }

  /**
   * Returns the URL path of a manufacturer's logo.
   *
   * @param p4kUuid the manufacturer's P4K GUID, or {@code null}
   * @return the path below the context root, or {@code null} when the manufacturer has no logo
   */
  @Nullable
  public String path(@Nullable UUID p4kUuid) {
    String file = p4kUuid == null ? null : files.get(p4kUuid);
    return file == null ? null : LOGO_PATH + file;
  }

  /**
   * Returns the whole mapping.
   *
   * @return the PNG file name of each mapped GUID
   */
  @NotNull
  @Unmodifiable
  Map<UUID, String> mapping() {
    return files;
  }

  /**
   * Reads and validates a mapping.
   *
   * @param objectMapper the mapper
   * @param resource the mapping
   * @return the PNG file of each GUID
   */
  @NotNull
  @Unmodifiable
  private static Map<UUID, String> read(
      @NotNull ObjectMapper objectMapper, @NotNull Resource resource) {
    try (InputStream in = resource.getInputStream()) {
      Map<String, String> raw = objectMapper.readValue(in, new TypeReference<>() {});
      Map<UUID, String> parsed = new LinkedHashMap<>();
      raw.forEach((guid, file) -> parsed.put(UUID.fromString(guid), file));
      return Map.copyOf(parsed);
    } catch (IOException | JacksonException | IllegalArgumentException e) {
      throw new IllegalStateException("Unreadable manufacturer logo mapping " + resource, e);
    }
  }
}
