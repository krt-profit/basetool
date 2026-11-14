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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.bank.api.BankPatchLabels;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The patch change a currency wipe cancelled a booking request for, named by its current label with
 * one read per page and none for a page without such a request (REQ-PATCH-042, REQ-DATA-003).
 */
@ExtendWith(MockitoExtension.class)
class BankRequestPatchLabelsTest {

  @Mock private BankPatchLabels port;
  @InjectMocks private BankRequestPatchLabels patchLabels;

  @Test
  void aPageOfRequestsNamesTheCancellingPatchChangeWithOneRead() {
    UUID patchChange = UUID.randomUUID();
    BankBookingRequest wiped = cancelledBy(patchChange);
    BankBookingRequest alsoWiped = cancelledBy(patchChange);
    BankBookingRequest withdrawn = cancelledBy(null);
    when(port.labels(Set.of(patchChange))).thenReturn(Map.of(patchChange, "4.4"));

    Function<BankBookingRequest, String> labels =
        patchLabels.forRequests(List.of(wiped, alsoWiped, withdrawn));

    assertThat(labels.apply(wiped)).isEqualTo("4.4");
    assertThat(labels.apply(alsoWiped)).isEqualTo("4.4");
    assertThat(labels.apply(withdrawn)).isNull();
    verify(port).labels(Set.of(patchChange));
  }

  @Test
  void aPageWithoutAPatchCancellationReadsNoLabel() {
    Function<BankBookingRequest, String> labels =
        patchLabels.forRequests(List.of(cancelledBy(null), cancelledBy(null)));

    assertThat(labels.apply(cancelledBy(null))).isNull();
    assertThat(patchLabels.labelOf(cancelledBy(null))).isNull();
    verifyNoInteractions(port);
  }

  @Test
  void aSingleRequestIsNamedByItsPatchChange() {
    UUID patchChange = UUID.randomUUID();
    when(port.labels(Set.of(patchChange))).thenReturn(Map.of(patchChange, "4.4"));

    assertThat(patchLabels.labelOf(cancelledBy(patchChange))).isEqualTo("4.4");
  }

  /**
   * A request in the state a cancellation leaves it.
   *
   * @param patchChange the patch change whose currency wipe cancelled it, or {@code null} for one
   *     its requester withdrew
   * @return the cancelled request
   */
  private static @NotNull BankBookingRequest cancelledBy(@Nullable UUID patchChange) {
    BankBookingRequest request = new BankBookingRequest();
    request.setStatus(BankBookingRequestStatus.PENDING);
    if (patchChange == null) {
      request.setStatus(BankBookingRequestStatus.CANCELLED);
    } else {
      request.cancelByPatchChange(patchChange, Instant.now());
    }
    return request;
  }
}
