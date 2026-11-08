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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.platform.api.ClientDirectory;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderProvenanceDto;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RefineryOrderProvenanceView} names an order's clients by their registry display names
 * (REQ-REFINERY-027) and carries the pickup and store markers (REQ-REFINERY-028).
 */
class RefineryOrderProvenanceViewTest {

  private static final Instant CREATED = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant PICKED_UP = Instant.parse("2026-11-01T12:00:00Z");

  private ClientDirectory clientDirectory;
  private RefineryOrderProvenanceView view;

  @BeforeEach
  void setUp() {
    clientDirectory = mock(ClientDirectory.class);
    when(clientDirectory.displayNames(anyCollection())).thenReturn(Map.of("versekit", "VerseKit"));
    view = new RefineryOrderProvenanceView(clientDirectory, mock(EntityManager.class));
  }

  /** A web order names no client and carries its last change. */
  @Test
  void aWebOrderNamesNoClient() {
    RefineryOrder order = new RefineryOrder();
    order.setProvenance(RefineryOrderProvenance.createdInWeb(CREATED));

    assertThat(view.provenance(order))
        .isEqualTo(new RefineryOrderProvenanceDto(null, null, CREATED, null, null, null));
  }

  /** A client's pickup names the client by its display name as creator, changer and picker. */
  @Test
  void aPickedUpClientOrderNamesTheClient() {
    RefineryOrder order = new RefineryOrder();
    RefineryOrderProvenance provenance =
        RefineryOrderProvenance.createdByClient("versekit", CREATED);
    provenance.recordPickup("versekit", PICKED_UP);
    order.setProvenance(provenance);

    assertThat(view.provenance(order))
        .isEqualTo(
            new RefineryOrderProvenanceDto(
                "VerseKit", "VerseKit", PICKED_UP, PICKED_UP, "VerseKit", null));
  }

  /** A client the registry no longer holds is shown by its client id; the store marker passes. */
  @Test
  void anUnregisteredClientIsShownByItsId() {
    RefineryOrder order = new RefineryOrder();
    order.setProvenance(RefineryOrderProvenance.createdByClient("gone-app", CREATED));
    order.setStoredAt(PICKED_UP);

    RefineryOrderProvenanceDto dto = view.provenance(order);

    assertThat(dto).isNotNull();
    assertThat(dto.createdByClient()).isEqualTo("gone-app");
    assertThat(dto.storedAt()).isEqualTo(PICKED_UP);
  }

  /** A missing order has no provenance. */
  @Test
  void aMissingOrderHasNone() {
    assertThat(view.provenance(null)).isNull();
  }
}
