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

package de.greluc.krt.profit.basetool.backend.privacy.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.UserMapper;
import de.greluc.krt.profit.basetool.backend.privacy.api.AccountConsolidationService;
import de.greluc.krt.profit.basetool.backend.privacy.api.ConsolidateAccountRequest;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserDeletionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link AccountRemovalController}: the delete forwards the id to the deletion
 * service and the consolidation maps the survivor.
 */
@ExtendWith(MockitoExtension.class)
class AccountRemovalControllerTest {

  @Mock private UserDeletionService userDeletionService;
  @Mock private UserMapper userMapper;
  @Mock private AccountConsolidationService accountConsolidationService;
  @InjectMocks private AccountRemovalController controller;

  @Test
  void deleteUser_delegatesIdToService() {
    UUID id = UUID.randomUUID();

    controller.deleteUser(id);

    verify(userDeletionService).deleteUser(id);
    verifyNoMoreInteractions(userDeletionService, userMapper);
  }

  @Test
  void consolidateAccount_foldsTheDuplicateIntoTheSurvivorAndMapsTheSurvivor() {
    UUID duplicate = UUID.randomUUID();
    UUID survivorId = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    User survivor = new User();
    UserDto dto = mockDto(survivorId);
    when(accountConsolidationService.consolidate(duplicate, survivorId, 3L, admin))
        .thenReturn(survivor);
    when(userMapper.toDto(survivor)).thenReturn(dto);

    UserDto result =
        controller.consolidateAccount(
            duplicate, admin, new ConsolidateAccountRequest(survivorId, 3L));

    assertSame(dto, result);
    verify(accountConsolidationService).consolidate(duplicate, survivorId, 3L, admin);
  }

  private static UserDto mockDto(UUID id) {
    return new UserDto(
        id,
        "u",
        "U",
        "U",
        "u@example.com",
        5,
        "desc",
        java.util.Set.of(),
        java.util.Set.of(),
        null,
        false,
        false,
        true,
        null,
        java.util.List.of(),
        1L,
        null,
        false);
  }
}
