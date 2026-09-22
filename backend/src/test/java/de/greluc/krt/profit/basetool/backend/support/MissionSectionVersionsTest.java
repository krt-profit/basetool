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

package de.greluc.krt.profit.basetool.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.support.MissionSectionVersions.MissionSection;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit tests for {@link MissionSectionVersions}: the in-memory counter the helper writes after the
 * DB-enforced conditional bump, and the overflow bound on that write (CodeQL alert {@code
 * java/tainted-arithmetic}, where the operand is the client's echoed version).
 */
class MissionSectionVersionsTest {

  private static final UUID MISSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

  private MissionRepository repository;
  private Mission mission;

  @BeforeEach
  void setUp() {
    repository = mock(MissionRepository.class);
    mission = new Mission();
    mission.setCoreVersion(7L);
    mission.setStepsVersion(3L);
  }

  @Test
  @DisplayName("a matched echo advances only that section's in-memory counter to echo + 1")
  void matchedEchoAdvancesTheSectionCounter() {
    when(repository.bumpCoreVersionIfMatches(MISSION_ID, 7L)).thenReturn(1);

    MissionSectionVersions.enforceSectionVersion(
        repository, mission, MissionSection.CORE, 7L, MISSION_ID);

    assertThat(mission.getCoreVersion()).isEqualTo(8L);
    assertThat(mission.getStepsVersion()).isEqualTo(3L);
  }

  @Test
  @DisplayName("a stale echo matches no row and surfaces as an optimistic-lock conflict")
  void staleEchoIsAConflict() {
    when(repository.bumpCoreVersionIfMatches(eq(MISSION_ID), anyLong())).thenReturn(0);

    assertThatThrownBy(
            () ->
                MissionSectionVersions.enforceSectionVersion(
                    repository, mission, MissionSection.CORE, 6L, MISSION_ID))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThat(mission.getCoreVersion()).isEqualTo(7L);
  }

  @Test
  @DisplayName("an echo of Long.MAX_VALUE is refused instead of wrapping the counter negative")
  void maxValueEchoDoesNotWrapTheCounter() {
    // The repository is mocked to report a match, which the real bigint column could never do for
    // this value (PostgreSQL rejects the out-of-range bump first). Without the bound the helper
    // would write Long.MIN_VALUE onto the managed entity and the dirty-checking flush would
    // persist it.
    when(repository.bumpCoreVersionIfMatches(MISSION_ID, Long.MAX_VALUE)).thenReturn(1);

    assertThatThrownBy(
            () ->
                MissionSectionVersions.enforceSectionVersion(
                    repository, mission, MissionSection.CORE, Long.MAX_VALUE, MISSION_ID))
        .isInstanceOf(ArithmeticException.class);
    assertThat(mission.getCoreVersion()).isEqualTo(7L);
  }

  @Test
  @DisplayName("the unconditional bump treats an absent counter as 0 and refuses to wrap MAX_VALUE")
  void unconditionalBumpCoalescesNullAndIsBounded() {
    mission.setFlagsVersion(null);
    MissionSectionVersions.bumpSectionVersion(mission, MissionSection.FLAGS);
    assertThat(mission.getFlagsVersion()).isEqualTo(1L);

    mission.setFlagsVersion(Long.MAX_VALUE);
    assertThatThrownBy(
            () -> MissionSectionVersions.bumpSectionVersion(mission, MissionSection.FLAGS))
        .isInstanceOf(ArithmeticException.class);
    assertThat(mission.getFlagsVersion()).isEqualTo(Long.MAX_VALUE);
  }
}
