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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link MissionRefineryOrderRelease}. */
@ExtendWith(MockitoExtension.class)
class MissionRefineryOrderReleaseTest {

  private static final UUID MISSION_ID = UUID.randomUUID();

  @Mock private RefineryOrderRepository refineryOrderRepository;

  @InjectMocks private MissionRefineryOrderRelease release;

  @Test
  void everyLinkedOrderLosesItsMission_andTheCountIsReturned() {
    RefineryOrder first = linked();
    RefineryOrder second = linked();
    when(refineryOrderRepository.findAllByMissionId(MISSION_ID)).thenReturn(List.of(first, second));

    int released = release.beforeMissionDelete(MISSION_ID);

    assertEquals(2, released);
    assertNull(first.getMissionId());
    assertNull(second.getMissionId());
  }

  @Test
  void aMissionWithoutOrders_releasesNothing() {
    when(refineryOrderRepository.findAllByMissionId(MISSION_ID)).thenReturn(List.of());

    assertEquals(0, release.beforeMissionDelete(MISSION_ID));
  }

  private static RefineryOrder linked() {
    RefineryOrder order = new RefineryOrder();
    order.setMissionId(MISSION_ID);
    return order;
  }
}
