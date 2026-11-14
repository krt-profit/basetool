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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The no-mixing window (REQ-PATCH-004, ADR-0381): a holder's pre-patch rows stay apart only while
 * the holder's relocation is pending in the open home-location phase.
 */
@ExtendWith(MockitoExtension.class)
class PatchChangeMergeCutoffTest {

  private static final Instant LIVE_AT = Instant.parse("2000-06-01T12:00:00Z");

  private static final HomeLocationPhase PHASE =
      new HomeLocationPhase(UUID.randomUUID(), "4.4", LIVE_AT);

  private final UUID holder = UUID.randomUUID();

  @Mock private HomeLocationPhases phases;

  private PatchChangeMergeCutoff cutoff;

  @BeforeEach
  void setUp() {
    cutoff = new PatchChangeMergeCutoff(phases);
  }

  @Test
  void aHolderPendingInTheOpenPhaseIsCutOffAtItsLiveTime() {
    when(phases.open()).thenReturn(Optional.of(PHASE));
    when(phases.pendingAmong(PHASE, List.of(holder))).thenReturn(Set.of(holder));

    assertThat(cutoff.cutoffFor(holder)).contains(LIVE_AT);
  }

  @Test
  void aHolderWhoRelocatedMergesAgain() {
    when(phases.open()).thenReturn(Optional.of(PHASE));
    when(phases.pendingAmong(PHASE, List.of(holder))).thenReturn(Set.of());

    assertThat(cutoff.cutoffFor(holder)).isEmpty();
  }

  @Test
  void withoutAnOpenPhaseEveryHolderMerges() {
    when(phases.open()).thenReturn(Optional.empty());

    assertThat(cutoff.cutoffFor(holder)).isEmpty();
  }
}
