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

import de.greluc.krt.profit.basetool.backend.web.CurrentUserId;
import de.greluc.krt.profit.basetool.backend.web.UserZone;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Top-level OpenAPI document of the backend, declaring the {@code bearer-jwt} security scheme as
 * the default requirement.
 */
@Configuration
public class OpenApiConfig {

  static {
    SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUserId.class, UserZone.class);
  }

  /**
   * Returns the {@link OpenAPI} root document used by SpringDoc to generate {@code openapi.json}.
   *
   * @return the {@link OpenAPI} root document used by SpringDoc to generate {@code openapi.json}
   */
  @Bean
  public OpenAPI krtOpenApi() {
    return new OpenAPI()
        .openapi("3.1.1")
        .info(
            new Info()
                .title("KRT Basetool Backend API")
                .version("1.0")
                .description("API documentation for the KRT Basetool Backend application."))
        .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearer-jwt",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
  }
}
