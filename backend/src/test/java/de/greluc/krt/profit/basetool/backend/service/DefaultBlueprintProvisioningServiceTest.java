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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link DefaultBlueprintProvisioningService}. */
@ExtendWith(MockitoExtension.class)
class DefaultBlueprintProvisioningServiceTest {

  /** The {@code app_user.id} the provisioning call is made for. */
  private static final UUID SUB_1 = UUID.fromString("0e000001-0000-4000-8000-000000000001");

  @Mock private PersonalBlueprintRepository repository;
  @InjectMocks private DefaultBlueprintProvisioningService service;

  @Test
  void grantDefaultsToUser_delegatesToRepositoryAndReturnsCount() {
    when(repository.grantDefaultBlueprintsToUser(SUB_1)).thenReturn(3);

    assertEquals(3, service.grantDefaultsToUser(SUB_1));
    verify(repository).grantDefaultBlueprintsToUser(SUB_1);
  }

  @Test
  void grantDefaultsToAllUsers_delegatesToRepositoryAndReturnsCount() {
    when(repository.grantDefaultBlueprintsToAllUsers()).thenReturn(12);

    assertEquals(12, service.grantDefaultsToAllUsers());
    verify(repository).grantDefaultBlueprintsToAllUsers();
  }
}
