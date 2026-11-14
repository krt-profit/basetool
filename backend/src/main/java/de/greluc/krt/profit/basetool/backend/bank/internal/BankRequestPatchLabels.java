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

import de.greluc.krt.profit.basetool.backend.bank.api.BankPatchLabels;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Names the patch change whose currency wipe cancelled a booking request (REQ-PATCH-042), with one
 * label read per batch of requests and none for a batch without such a request (REQ-DATA-003).
 */
@Component
@RequiredArgsConstructor
public class BankRequestPatchLabels {

  private final BankPatchLabels patchLabels;

  /**
   * Names the patch change that cancelled the request.
   *
   * @param request the request
   * @return the patch change's current label, or {@code null} when no currency wipe cancelled it
   */
  @Nullable
  public String labelOf(@NotNull BankBookingRequest request) {
    return forRequests(List.of(request)).apply(request);
  }

  /**
   * Reads the patch labels of a batch of requests at once, for mapping a page or list.
   *
   * @param requests the requests about to be mapped
   * @return names a request as {@link #labelOf(BankBookingRequest)} does, from the one read
   */
  @NotNull
  public Function<BankBookingRequest, String> forRequests(
      @NotNull Collection<BankBookingRequest> requests) {
    Set<UUID> patchChanges =
        requests.stream()
            .map(BankBookingRequest::getCancelledByPatchChange)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<UUID, String> labels = patchChanges.isEmpty() ? Map.of() : patchLabels.labels(patchChanges);
    return request -> {
      UUID patchChange = request.getCancelledByPatchChange();
      return patchChange == null ? null : labels.get(patchChange);
    };
  }
}
