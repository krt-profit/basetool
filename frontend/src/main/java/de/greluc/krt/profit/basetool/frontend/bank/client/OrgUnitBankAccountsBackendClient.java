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

import de.greluc.krt.profit.basetool.frontend.bank.model.OrgUnitBankVisibleAccountDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the collective read of the viewable org-unit bank accounts ({@code GET
 * /api/v1/bank/org-units/accounts/visible}, REQ-BANK-064) over {@link BackendApiClient}.
 */
@Service
@RequiredArgsConstructor
public class OrgUnitBankAccountsBackendClient {

  private static final ParameterizedTypeReference<PageResponse<OrgUnitBankVisibleAccountDto>>
      VISIBLE_ACCOUNT_PAGE = new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Reads the first page of the org-unit accounts the caller may view, in the backend's order.
   *
   * @param size the page size, the number of accounts wanted
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<OrgUnitBankVisibleAccountDto> firstPage(int size) {
    return backendApiClient.get(
        "/api/v1/bank/org-units/accounts/visible?page=0&size={size}", VISIBLE_ACCOUNT_PAGE, size);
  }
}
