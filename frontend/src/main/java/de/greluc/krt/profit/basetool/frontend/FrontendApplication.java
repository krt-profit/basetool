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
 * Spring Boot entry point for the frontend module.
 *
 * <p>Excludes the Resilience4j startup verifier and Spring Data's {@code DataWebAutoConfiguration},
 * both unused here; the latter logs false-positive warnings for interface-typed
 * {@code @ModelAttribute} parameters (REQ-OBS-015).
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
