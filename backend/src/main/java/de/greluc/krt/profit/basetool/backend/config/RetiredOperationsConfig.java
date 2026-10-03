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

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/** Provides the committed list of retired Android operations (REQ-API-020). */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class RetiredOperationsConfig {

  /**
   * Loads {@value RetiredOperations#LOCATION} from the classpath; a malformed list fails startup.
   *
   * @return the retired operations, empty when nothing is retired
   * @throws IOException if the committed list cannot be read
   */
  @NotNull
  @Bean
  public RetiredOperations retiredOperations() throws IOException {
    RetiredOperations retired =
        RetiredOperations.load(new ClassPathResource(RetiredOperations.LOCATION));
    if (retired.isEmpty()) {
      log.info("Retired operations: none, APP_UPDATE_REQUIRED is not answered");
    } else {
      log.info(
          "Retired operations: {} answer APP_UPDATE_REQUIRED: {}",
          retired.entries().size(),
          retired.entries());
    }
    return retired;
  }
}
