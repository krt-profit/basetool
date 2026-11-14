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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRelocationLock;
import de.greluc.krt.profit.basetool.backend.hangar.internal.HangarRelocationGuard.LockedShips;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the bulk-write side of {@link HangarRelocationGuard}: which owners' ships a fitted
 * reset leaves out for its caller (REQ-PATCH-023).
 */
@ExtendWith(MockitoExtension.class)
class HangarRelocationGuardTest {

  private static final Instant LIVE_AT = Instant.parse("2026-01-01T00:00:00Z");

  private final UUID free = UUID.randomUUID();
  private final UUID refused = UUID.randomUUID();

  @Mock private ShipRelocationLock lock;

  @Mock private HangarRelocationRepository relocations;

  @InjectMocks private HangarRelocationGuard guard;

  @Test
  void lockedForNamesTheOwnersTheLockRefusesWithTheLiveTime() {
    when(lock.openPhaseLiveAt()).thenReturn(Optional.of(LIVE_AT));
    when(lock.refusedAmong(List.of(free, refused))).thenReturn(Set.of(refused));

    assertThat(guard.lockedFor(List.of(free, refused)))
        .isEqualTo(new LockedShips(Set.of(refused), LIVE_AT));
  }

  @Test
  void lockedForLocksNothingWhileNoPhaseIsOpenOrNobodyIsRefused() {
    when(lock.openPhaseLiveAt()).thenReturn(Optional.empty(), Optional.of(LIVE_AT));
    when(lock.refusedAmong(List.of(free))).thenReturn(Set.of());

    assertThat(guard.lockedFor(List.of(free))).isSameAs(LockedShips.NONE);
    assertThat(guard.lockedFor(List.of(free))).isSameAs(LockedShips.NONE);
    assertThat(guard.lockedFor(List.of())).isSameAs(LockedShips.NONE);
    verify(lock, never()).refusedAmong(List.of());
    verify(relocations, never()).findOwnersOfShipsBefore(any(), any());
  }
}
