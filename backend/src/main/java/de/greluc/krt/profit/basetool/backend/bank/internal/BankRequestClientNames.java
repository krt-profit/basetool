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

import de.greluc.krt.profit.basetool.backend.platform.api.ClientDirectory;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Names the connected application a booking request was raised through, for the staff badge
 * (REQ-BANK-067), with one registry read per batch of requests (REQ-DATA-003).
 */
@Component
@RequiredArgsConstructor
public class BankRequestClientNames {

  private final ClientDirectory clientDirectory;

  /**
   * Names the request's client.
   *
   * @param request the request
   * @return the registry's display name, the stored client id for a client the registry no longer
   *     holds, or {@code null} for a request raised in the Basetool
   */
  @Nullable
  public String nameOf(@NotNull BankBookingRequest request) {
    return forRequests(List.of(request)).apply(request);
  }

  /**
   * Reads the client names of a batch of requests at once, for mapping a page or list.
   *
   * @param requests the requests about to be mapped
   * @return names a request as {@link #nameOf(BankBookingRequest)} does, from the one read
   */
  @NotNull
  public Function<BankBookingRequest, String> forRequests(
      @NotNull Collection<BankBookingRequest> requests) {
    Set<String> clientIds =
        requests.stream()
            .map(BankBookingRequest::getClientId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<String, String> names =
        clientIds.isEmpty() ? Map.of() : clientDirectory.displayNames(clientIds);
    return request -> {
      String clientId = request.getClientId();
      return clientId == null ? null : names.getOrDefault(clientId, clientId);
    };
  }
}