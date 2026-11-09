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

package de.greluc.krt.profit.basetool.frontend.bank.client;

import de.greluc.krt.profit.basetool.frontend.bank.model.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.frontend.bank.model.BankBookingRequestViewDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Typed backend client of the org-unit booking-request reads ({@code GET
 * /api/v1/bank/org-units/requests**}, REQ-BANK-022, REQ-BANK-041) over {@link BackendApiClient}.
 */
@Service
@RequiredArgsConstructor
public class OrgUnitBankRequestsBackendClient {

  private static final String REQUESTS = "/api/v1/bank/org-units/requests";

  private static final ParameterizedTypeReference<List<BankBookingRequestDto>>
      BOOKING_REQUEST_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<BankBookingRequestDto>>
      BOOKING_REQUEST_PAGE = new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Reads the caller's own org-unit booking requests (REQ-BANK-022).
   *
   * @return the requests, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<BankBookingRequestDto> own() {
    return backendApiClient.get(REQUESTS, BOOKING_REQUEST_LIST);
  }

  /**
   * Reads one page of the caller's own org-unit booking requests in the given states, newest first
   * (REQ-BANK-022).
   *
   * @param statuses the lifecycle states to include, each sent as its own {@code status} value;
   *     empty for every state
   * @param size the page size
   * @return the page with the total over all pages, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<BankBookingRequestDto> ownPage(@NotNull List<String> statuses, int size) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath(REQUESTS + "/page").queryParam("size", size);
    statuses.forEach(s -> uri.queryParam("status", s));
    return backendApiClient.get(uri.toUriString(), BOOKING_REQUEST_PAGE);
  }

  /**
   * Reads the booking requests on the accounts the caller is responsible for.
   *
   * @return the requests, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<BankBookingRequestDto> foreign() {
    return backendApiClient.get(REQUESTS + "/foreign", BOOKING_REQUEST_LIST);
  }

  /**
   * Reads one booking request the caller raised, sees as bank staff or approves (REQ-BANK-068).
   *
   * @param id the request
   * @return the request with the caller's roles on it, or {@code null} when it is unknown or hidden
   *     from the caller
   */
  @Nullable
  public BankBookingRequestViewDto view(@NotNull UUID id) {
    return backendApiClient.get(REQUESTS + "/{id}", BankBookingRequestViewDto.class, id);
  }
}
