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

package de.greluc.krt.profit.basetool.backend.config;

import de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation;
import io.swagger.v3.oas.models.Operation;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;

/**
 * SpringDoc customizer that marks operations carrying {@link ApiDeprecation} or {@code @Deprecated}
 * as deprecated in the OpenAPI document, with sunset date and replacement path.
 */
@Configuration
public class OpenApiDeprecationConfig {

  /**
   * Creates the operation customizer that marks endpoints deprecated by {@link ApiDeprecation} or
   * {@code @Deprecated} on the handler method or its class.
   *
   * @return the deprecation-annotating operation customizer
   */
  @Bean
  public OperationCustomizer deprecationCustomizer() {
    return (Operation operation, HandlerMethod handlerMethod) -> {
      ApiDeprecation deprecation = handlerMethod.getMethodAnnotation(ApiDeprecation.class);
      boolean isDeprecated = handlerMethod.hasMethodAnnotation(Deprecated.class);

      if (deprecation == null) {
        deprecation = handlerMethod.getBeanType().getAnnotation(ApiDeprecation.class);
      }
      if (!isDeprecated) {
        isDeprecated = handlerMethod.getBeanType().isAnnotationPresent(Deprecated.class);
      }

      if (isDeprecated || deprecation != null) {
        operation.setDeprecated(true);

        if (deprecation != null) {
          StringBuilder desc = new StringBuilder();
          if (operation.getDescription() != null && !operation.getDescription().isBlank()) {
            desc.append(operation.getDescription()).append("\n\n");
          }
          desc.append("**DEPRECATED**");
          if (!deprecation.sunset().isEmpty()) {
            desc.append("\n- Sunset Date: ").append(deprecation.sunset());
          }
          if (!deprecation.replacement().isEmpty()) {
            desc.append("\n- Replacement: `").append(deprecation.replacement()).append("`");
          }
          operation.setDescription(desc.toString().trim());
        }
      }
      return operation;
    };
  }
}
