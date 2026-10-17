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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.config.PathControlInventory.Endpoint;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves every mapping of every registry lies inside the surface the chain's matchers and the
 * {@code /api}-scoped filters actually cover (REQ-SEC-078).
 *
 * <p>A mapping outside the served roots is reached by no path-scoped filter.
 */
@SpringBootTest
class ServedSurfaceCoverageTest {

  /** The roots every mapping lives under. */
  private static final List<String> SURFACE_ROOTS = List.of("/api", "/internal", "/actuator");

  /**
   * Framework mappings allowed outside the roots: the error page and springdoc's two OpenAPI
   * spellings, which are {@code ADMIN}-only and disabled under the {@code prod} profile.
   */
  private static final Set<String> SURFACE_EXTRAS =
      Set.of("/error", "/v3/api-docs", "/v3/api-docs.yaml");

  /** The number of write mappings today; a smaller selection means the inventory broke. */
  private static final int WRITE_MAPPING_FLOOR = 331;

  /** The number of mappings of every registry today. */
  private static final int MAPPING_FLOOR = 583;

  @Autowired private WebApplicationContext context;

  @Test
  @DisplayName("no mapping exists outside /api, /internal, the actuator and the framework pages")
  void noMappingOutsideTheSurface() {
    List<Endpoint> endpoints = PathControlInventory.everyRegistry(context);

    assertThat(endpoints.size()).as("mappings found").isGreaterThanOrEqualTo(MAPPING_FLOOR);
    assertThat(PathControlInventory.outsideSurface(endpoints, SURFACE_ROOTS, SURFACE_EXTRAS))
        .as(
            "a mapping outside the served surface is reached by none of the /api-scoped filters"
                + " and by no request matcher anyone reviewed")
        .isEmpty();
  }

  @Test
  @DisplayName("the write mappings are still found, so the inventory is not silently empty")
  void theWriteMappingsAreStillFound() {
    List<Endpoint> writes =
        PathControlInventory.everyRegistry(context).stream().filter(Endpoint::isWrite).toList();

    assertThat(writes.size())
        .as("write mappings found")
        .isGreaterThanOrEqualTo(WRITE_MAPPING_FLOOR);
  }
}
