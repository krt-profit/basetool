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

package de.greluc.krt.profit.basetool.backend.bank.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.identity.api.UserDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserReferenceSearchService;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.orgunit.api.UserDirectoryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

/**
 * Unit tests for {@link BankMemberSearchController}: both endpoints delegate to the shared
 * searches.
 */
@ExtendWith(MockitoExtension.class)
class BankMemberSearchControllerTest {

  @Mock private UserDirectoryService userDirectoryService;
  @Mock private UserReferenceSearchService userReferenceSearchService;
  @InjectMocks private BankMemberSearchController controller;

  @Test
  void searchUsersForBank_delegatesToTheDirectory() {
    PageResponse<UserDto> page = PageResponse.of(new PageImpl<>(List.of()));
    when(userDirectoryService.search("ali", 1, 20, "rank")).thenReturn(page);

    assertSame(page, controller.searchUsersForBank("ali", 1, 20, "rank"));
  }

  @Test
  void searchUserReferencesForBank_delegatesToTheReferenceSearch() {
    PageResponse<UserReferenceDto> page = PageResponse.of(new PageImpl<>(List.of()));
    when(userReferenceSearchService.search(null, null, null, null)).thenReturn(page);

    assertSame(page, controller.searchUserReferencesForBank(null, null, null, null));
  }
}
