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

package de.greluc.krt.profit.basetool.ingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot entry point for the {@code ingest} gateway — the only internet-reachable
 * authenticated entry the desktop extractor uses to push its locally produced JSON into the
 * basetool (epic #639, ADR-0018).
 *
 * <p>The gateway is deliberately tiny: it validates the caller's Keycloak JWT, calls the backend's
 * existing import endpoints over the internal network under its OWN service-account identity while
 * naming the caller in an on-behalf-of header (ADR-0129), stages the returned draft in Redis for a
 * one-time browser pickup, and returns a handoff id. It owns no database, runs no Flyway migration
 * and serves no HTML. {@code @ConfigurationPropertiesScan} registers the {@code config/*Properties}
 * classes without an explicit enable list.
 */
@SpringBootApplication(
    exclude = {
      io.github.resilience4j.springboot3.verifier.autoconfigure.SpringBoot3VerifierAutoConfiguration
          .class
    })
@ConfigurationPropertiesScan
public class IngestApplication {

  /**
   * Standard Spring Boot main; delegates to {@link SpringApplication#run(Class, String...)} with
   * this class as the primary source.
   *
   * @param args command-line arguments forwarded to Spring Boot
   */
  public static void main(String[] args) {
    SpringApplication.run(IngestApplication.class, args);
  }
}
