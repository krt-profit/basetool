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
import org.springframework.http.HttpMethod;

/**
 * Proves every check of the path-keyed control guards able to fail, by feeding each one a planted
 * violation next to a compliant endpoint (REQ-SEC-031, REQ-SEC-078…080).
 */
class PathControlInventoryTest {

  /** A compliant read. */
  private static final Endpoint MISSION_READ =
      new Endpoint(Set.of(HttpMethod.GET), "/api/v1/missions/{id}");

  /** A compliant write. */
  private static final Endpoint MISSION_JOIN =
      new Endpoint(Set.of(HttpMethod.POST), "/api/v1/missions/{missionId}/join");

  @Test
  @DisplayName("a pattern becomes a concrete path its own matcher accepts")
  void patternsBecomeConcretePaths() {
    assertThat(PathControlInventory.concrete("/api/v1/missions/{missionId}/units/{name}"))
        .isEqualTo("/api/v1/missions/" + PathControlInventory.NIL_UUID + "/units/x");
    assertThat(PathControlInventory.concrete("/actuator/health/**"))
        .isEqualTo("/actuator/health/x");
    assertThat(PathControlInventory.concrete("/actuator/loggers/{name}"))
        .isEqualTo("/actuator/loggers/x");
  }

  @Test
  @DisplayName("a mapping outside the served surface is reported")
  void aPlantedMappingOutsideTheSurfaceIsReported() {
    Endpoint planted = new Endpoint(Set.of(HttpMethod.POST), "/legacy/write");

    assertThat(
            PathControlInventory.outsideSurface(
                List.of(MISSION_READ, planted, new Endpoint(Set.of(), "/error")),
                List.of("/api", "/internal"),
                Set.of("/error")))
        .containsExactly(planted.toString());
  }

  @Test
  @DisplayName("an API mapping no family classifies is reported")
  void aPlantedUnclassifiedFamilyIsReported() {
    Endpoint planted = new Endpoint(Set.of(HttpMethod.GET), "/api/v1/planted-family/{id}");

    assertThat(PathControlInventory.unclassifiedApiEndpoints(List.of(MISSION_READ, planted)))
        .containsExactly(planted.toString());
  }

  @Test
  @DisplayName("a rule path that matches no operation with its verbs is reported")
  void aPlantedDeadRulePathIsReported() {
    List<Endpoint> endpoints = List.of(MISSION_READ, MISSION_JOIN);

    assertThat(
            PathControlInventory.deadPatterns(
                List.of("/api/v1/missions/*/join", "/api/v1/planted", "/api/v1/missions/*"),
                List.of(HttpMethod.POST),
                endpoints))
        .containsExactly("/api/v1/planted", "/api/v1/missions/*");
    assertThat(
            PathControlInventory.deadPatterns(List.of("/api/v1/missions/*"), List.of(), endpoints))
        .isEmpty();
  }

  @Test
  @DisplayName("an export segment no mapping carries is reported")
  void aPlantedUnusedSegmentIsReported() {
    assertThat(
            PathControlInventory.unusedSegments(Set.of("join", "planted"), List.of(MISSION_JOIN)))
        .containsExactly("planted");
  }

  @Test
  @DisplayName("an exempt path that names no mapping is reported")
  void aPlantedUnmappedExemptionIsReported() {
    assertThat(
            PathControlInventory.unmappedPaths(
                List.of("/api/v1/missions/{id}", "/api/v1/planted"), List.of(MISSION_READ)))
        .containsExactly("/api/v1/planted");
  }

  @Test
  @DisplayName("a new mapping below an exact-set root is reported until it is listed")
  void aPlantedMappingBelowAnExactRootIsReported() {
    Endpoint status = new Endpoint(Set.of(HttpMethod.GET), "/api/v1/terms/status");
    Endpoint planted = new Endpoint(Set.of(HttpMethod.GET), "/api/v1/terms/admin");

    assertThat(
            PathControlInventory.unlistedBelow(
                "/api/v1/terms", Set.of("/api/v1/terms/status"), List.of(status, planted)))
        .containsExactly(planted.toString());
  }

  @Test
  @DisplayName("a mapping without declared verbs counts as a write")
  void anUndeclaredVerbSetIsAWrite() {
    assertThat(new Endpoint(Set.of(), "/error").isWrite()).isTrue();
    assertThat(MISSION_READ.isWrite()).isFalse();
    assertThat(MISSION_JOIN.isWrite()).isTrue();
  }
}
