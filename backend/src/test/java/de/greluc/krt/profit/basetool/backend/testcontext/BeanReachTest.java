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

package de.greluc.krt.profit.basetool.backend.testcontext;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Proves that {@link BeanReach} finds a planted mock behind a security bean. */
class BeanReachTest {

  private static final Map<String, Set<String>> GRAPH =
      Map.of(
          "securityFilterChain", Set.of("jwtConverter"),
          "jwtConverter", Set.of("userService"),
          "userService", Set.of("userRepository"),
          "reportController", Set.of("reportService"),
          "missionSecurityService", Set.of());

  @Test
  void reportsAMockTheSecurityChainReachesTransitively() {
    assertThat(
            BeanReach.reachedTargets(
                bean -> GRAPH.getOrDefault(bean, Set.of()),
                List.of("securityFilterChain", "missionSecurityService"),
                List.of("userRepository", "reportService")))
        .containsExactly("userRepository <- userService <- jwtConverter <- securityFilterChain");
  }

  @Test
  void reportsAMockedRootItself() {
    assertThat(
            BeanReach.reachedTargets(
                bean -> GRAPH.getOrDefault(bean, Set.of()),
                List.of("missionSecurityService"),
                List.of("missionSecurityService")))
        .containsExactly("missionSecurityService");
  }

  @Test
  void passesALeafOnlyAControllerReaches() {
    assertThat(
            BeanReach.reachedTargets(
                bean -> GRAPH.getOrDefault(bean, Set.of()),
                List.of("securityFilterChain", "missionSecurityService"),
                List.of("reportService")))
        .isEmpty();
  }
}
