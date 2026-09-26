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

package de.greluc.krt.profit.basetool.ingest.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Top-level OpenAPI document for the ingest gateway, declaring the {@code bearer-jwt} security
 * scheme as the default requirement.
 */
@Configuration
public class OpenApiConfig {

  /**
   * Returns the {@link OpenAPI} root document SpringDoc merges the scanned {@code /v1} operations
   * into when generating {@code openapi.json}.
   *
   * @return the {@link OpenAPI} root document for the ingest gateway
   */
  @Bean
  public OpenAPI ingestOpenApi() {
    return new OpenAPI()
        .openapi("3.1.1")
        .info(
            new Info()
                .title("KRT Basetool Ingest Gateway API")
                .version("1.0")
                .description(
                    "Forward-only ingest gateway for the KRT Basetool. It validates the caller's"
                        + " Keycloak JWT, relays the payload to the backend's import endpoints"
                        + " under its OWN service-account identity while naming the member it"
                        + " acts for in an on-behalf-of header (ADR-0129 - the caller's token"
                        + " stops here, because a sender-constrained token cannot survive a"
                        + " second hop), stages the returned draft in Redis for a single-use"
                        + " browser pickup, and returns the handoff the desktop extractor opens."
                        + " Nothing is interpreted or persisted here.\n\n"
                        + "## Restricted interface — approved clients only\n\n"
                        + "This document is published so that the official basetool SC extractor"
                        + " can be developed against a stable contract. It is NOT an open"
                        + " integration API. **Only client software explicitly approved by the"
                        + " basetool developer (@greluc) may use this interface.** Approval means"
                        + " a dedicated Keycloak client registration AND an entry on the gateway's"
                        + " client allowlist; unapproved callers are rejected with"
                        + " `403 CLIENT_NOT_ALLOWED`, are unsupported, and may break without"
                        + " notice. Building or distributing an unapproved client is not"
                        + " permitted — if you want to integrate, ask first."))
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
