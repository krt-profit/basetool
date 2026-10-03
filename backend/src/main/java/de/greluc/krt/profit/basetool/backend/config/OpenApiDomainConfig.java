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

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;

/**
 * Tags every operation of the OpenAPI document with its domain and its contract tier (REQ-API-018,
 * ADR-0234).
 *
 * <p>Each operation carries exactly one tag, its domain from {@link ApiDomains}, mirrored as the
 * {@code x-domain} extension, and the {@code x-contract-tier} extension from {@link ContractTiers}.
 * The document's tag list holds the domains and nothing else; springdoc's class-name tags are off
 * ({@code springdoc.auto-tag-classes: false}).
 */
@Configuration
public class OpenApiDomainConfig {

  /** The extension naming an operation's domain. */
  public static final String DOMAIN_EXTENSION = "x-domain";

  /** The extension naming an operation's contract tier. */
  public static final String TIER_EXTENSION = "x-contract-tier";

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
   * Creates the customizer that marks every operation with its contract tier and replaces the
   * document's tag list with the domains its operations use.
   *
   * @return the document customizer
   */
  @Bean
  public OpenApiCustomizer contractTierCustomizer() {
    ContractTiers tiers = ContractTiers.load();
    return openApi -> customize(openApi, tiers);
  }

  /**
   * Applies the tiers and the domain tag list to a document.
   *
   * @param openApi the document under construction
   * @param tiers the tier list
   */
  private static void customize(@NotNull OpenAPI openApi, @NotNull ContractTiers tiers) {
    Set<String> domains = new TreeSet<>();
    if (openApi.getPaths() != null) {
      for (Map.Entry<String, PathItem> path : openApi.getPaths().entrySet()) {
        for (Map.Entry<PathItem.HttpMethod, Operation> entry :
            path.getValue().readOperationsMap().entrySet()) {
          Operation operation = entry.getValue();
          operation.addExtension(
              TIER_EXTENSION, tiers.tierOf(entry.getKey().name(), path.getKey()));
          if (operation.getTags() != null) {
            domains.addAll(operation.getTags());
          }
        }
      }
    }
    openApi.setTags(domains.stream().map(domain -> new Tag().name(domain)).toList());
  }
}
