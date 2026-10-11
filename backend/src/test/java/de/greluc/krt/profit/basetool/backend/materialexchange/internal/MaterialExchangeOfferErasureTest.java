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

package de.greluc.krt.profit.basetool.backend.materialexchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant.Phase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The Materialbörse's part of the account deletion. */
@ExtendWith(MockitoExtension.class)
class MaterialExchangeOfferErasureTest {

  @Mock private MaterialExchangeOfferRatchet offerRatchet;

  @InjectMocks private MaterialExchangeOfferErasure participant;

  private final UUID userId = UUID.randomUUID();
  private final UUID adminId = UUID.randomUUID();

  @Test
  void runsInTheDeletePhaseAtItsPosition() {
    assertThat(participant.phase()).isEqualTo(Phase.DELETE);
    assertThat(participant.position()).isEqualTo(10);
  }

  @Test
  void auditsTheOffersOnTheMembersRowsAndReportsNoCount() {
    assertThat(participant.erase(userId, adminId)).isEmpty();

    verify(offerRatchet).removeOffersOnRowsOf(userId);
    verifyNoMoreInteractions(offerRatchet);
  }
}
