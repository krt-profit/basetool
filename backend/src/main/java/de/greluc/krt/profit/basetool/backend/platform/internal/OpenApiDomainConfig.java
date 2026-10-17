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

package de.greluc.krt.profit.basetool.backend.platform.internal;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;

/**
 * Tags every operation of the OpenAPI document with its domain (REQ-API-018).
 *
 * <p>Each operation carries exactly one tag, its domain from {@link ApiDomains}, mirrored as the
 * {@code x-domain} extension. The document's tag list holds the domains and nothing else;
 * springdoc's class-name tags are off ({@code springdoc.auto-tag-classes: false}).
 */
@Configuration
public class OpenApiDomainConfig {

  /** The extension naming an operation's domain. */
  public static final String DOMAIN_EXTENSION = "x-domain";

  /**
   * Creates the customizer that replaces an operation's tags with its controller's domain.
   *
   * @return the operation customizer
   */
  @Bean
  public OperationCustomizer domainTagCustomizer() {
    return (Operation operation, HandlerMethod handlerMethod) -> {
      String domain = ApiDomains.of(handlerMethod.getBeanType());
      operation.setTags(List.of(domain));
      operation.addExtension(DOMAIN_EXTENSION, domain);
      return operation;
    };
  }

  /**
   * Creates the customizer that replaces the document's tag list with the domains its operations
   * use.
   *
   * @return the document customizer
   */
  @Bean
  public OpenApiCustomizer domainTagListCustomizer() {
    return OpenApiDomainConfig::customize;
  }

  /**
   * Applies the domain tag list to a document.
   *
   * @param openApi the document under construction
   */
  private static void customize(@NotNull OpenAPI openApi) {
    Set<String> domains = new TreeSet<>();
    if (openApi.getPaths() != null) {
      for (PathItem path : openApi.getPaths().values()) {
        for (Operation operation : path.readOperationsMap().values()) {
          if (operation.getTags() != null) {
            domains.addAll(operation.getTags());
          }
        }
      }
    }
    openApi.setTags(domains.stream().map(domain -> new Tag().name(domain)).toList());
  }
}
