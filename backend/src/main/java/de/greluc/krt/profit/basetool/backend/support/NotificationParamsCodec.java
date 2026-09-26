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

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Converts a notification's i18n render parameters between a {@code Map<String,String>} and the
 * JSON text stored in {@code notification.params}.
 *
 * <p>Used as a MapStruct {@code uses} helper by the notification mapper. A malformed payload
 * deserializes to an empty map and a log line rather than failing the read.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationParamsCodec {

  private static final TypeReference<LinkedHashMap<String, String>> MAP_TYPE =
      new TypeReference<>() {};

  private final ObjectMapper objectMapper;

  /**
   * Serializes render parameters to a compact JSON object, or {@code null} when there are none so
   * the column stays {@code NULL} rather than storing {@code "{}"}.
   *
   * @param params the render parameters, may be {@code null} or empty
   * @return the JSON text, or {@code null} when {@code params} is null/empty
   */
  @Nullable
  public String serialize(@Nullable Map<String, String> params) {
    if (params == null || params.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(params);
    } catch (JacksonException e) {
      log.warn("Failed to serialize notification params; storing none — {}", e.getMessage());
      return null;
    }
  }

  /**
   * Deserializes the stored JSON object back into a render-parameter map.
   *
   * @param json the stored JSON text, may be {@code null} or blank
   * @return the parameter map; an empty map when {@code json} is null/blank or malformed
   */
  @NotNull
  public Map<String, String> deserialize(@Nullable String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (JacksonException e) {
      log.warn(
          "Failed to parse notification params JSON; returning empty map — {}", e.getMessage());
      return Map.of();
    }
  }
}
