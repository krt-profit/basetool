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

package de.greluc.krt.profit.basetool.frontend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot entry point for the Frontend module.
 *
 * <p>Excludes two auto-configurations pulled in only transitively:
 *
 * <ul>
 *   <li>{@code SpringBoot3VerifierAutoConfiguration} — the Resilience4j startup verifier, not
 *       wanted here.
 *   <li>{@code DataWebAutoConfiguration} — Spring Data's {@code @EnableSpringDataWebSupport},
 *       dragged in transitively via {@code spring-data-redis} (session store). This module does no
 *       Spring Data web binding at all (no {@code Pageable}/{@code Sort}/projection parameters; the
 *       backend is paged through the module's own {@code PageResponse} DTO), so its {@code
 *       ProxyingHandlerMethodArgumentResolver} is dead weight — and it emits a WARN ("not annotated
 *       with @ProjectedPayload") for every interface-typed {@code @ModelAttribute} parameter, which
 *       {@link de.greluc.krt.profit.basetool.frontend.config.OrgUnitContextAdvice} legitimately
 *       uses to cross-inject its already-loaded {@code List<…>} catalogues. Dropping the unused
 *       auto-config removes that false-positive at the source rather than muting the logger
 *       (REQ-OBS-015, #1202).
 * </ul>
 */
@SpringBootApplication(
    exclude = {
      io.github.resilience4j.springboot3.verifier.autoconfigure.SpringBoot3VerifierAutoConfiguration
          .class,
      org.springframework.boot.data.autoconfigure.web.DataWebAutoConfiguration.class
    })
@ConfigurationPropertiesScan
public class FrontendApplication {
  static void main(String[] args) {
    SpringApplication.run(FrontendApplication.class, args);
  }
}
