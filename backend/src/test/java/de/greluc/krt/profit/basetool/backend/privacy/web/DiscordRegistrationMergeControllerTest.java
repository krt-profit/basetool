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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.PendingRegistrationDto;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRegistrationService;
import de.greluc.krt.profit.basetool.backend.privacy.api.MergeAccountRequest;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserAccountMergeService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link DiscordRegistrationMergeController}. */
@ExtendWith(MockitoExtension.class)
class DiscordRegistrationMergeControllerTest {

  private static final UUID ADMIN = UUID.fromString("ad311111-0000-4000-8000-000000000001");

  @Mock private UserRegistrationService userRegistrationService;
  @Mock private UserAccountMergeService userAccountMergeService;
  @InjectMocks private DiscordRegistrationMergeController controller;

  private static User survivor(UUID id) {
    User user = new User();
    user.setId(id);
    user.setUsername("Callsign");
    user.setVersion(4L);
    return user;
  }

  @Test
  void merge_passesSourceTargetAndAdmin_andMapsTheSurvivorWithItsCollision() {
    UUID source = UUID.randomUUID();
    UUID target = UUID.randomUUID();
    User merged = survivor(target);
    when(userAccountMergeService.merge(source, target, ADMIN)).thenReturn(merged);
    when(userRegistrationService.findCollidingCallsigns(List.of(merged)))
        .thenReturn(Set.of("callsign"));

    PendingRegistrationDto dto =
        controller.merge(target, ADMIN, new MergeAccountRequest(source, 4L));

    verify(userAccountMergeService).merge(source, target, ADMIN);
    assertEquals(target, dto.id());
    assertEquals(4L, dto.version());
    assertTrue(dto.callsignCollision());
  }

  @Test
  void merge_withoutACollision_reportsNone() {
    UUID source = UUID.randomUUID();
    UUID target = UUID.randomUUID();
    User merged = survivor(target);
    when(userAccountMergeService.merge(source, target, ADMIN)).thenReturn(merged);
    when(userRegistrationService.findCollidingCallsigns(List.of(merged))).thenReturn(Set.of());

    assertFalse(
        controller.merge(target, ADMIN, new MergeAccountRequest(source, null)).callsignCollision());
  }
}
