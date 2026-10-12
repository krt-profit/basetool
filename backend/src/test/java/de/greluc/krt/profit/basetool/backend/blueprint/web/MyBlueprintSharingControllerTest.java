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

package de.greluc.krt.profit.basetool.backend.blueprint.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.blueprint.api.MemberBlueprintSharings;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit tests for {@link MyBlueprintSharingController}: the caller id comes from the JWT, the flag
 * and the setting's version are forwarded and returned.
 */
@ExtendWith(MockitoExtension.class)
class MyBlueprintSharingControllerTest {

  @Mock private UserService userService;
  @Mock private MemberBlueprintSharings memberBlueprintSharings;
  @Mock private Jwt jwt;
  @InjectMocks private MyBlueprintSharingController controller;

  private static final UUID CALLER_ID = UUID.randomUUID();

  @Test
  void getMyBlueprintSharing_resolvesIdFromJwt_andReturnsFlagAndVersion() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    when(memberBlueprintSharings.snapshotOf(CALLER_ID))
        .thenReturn(new MemberBlueprintSharings.Snapshot(true, 4L));

    MyBlueprintSharingController.MyBlueprintSharingResponse result =
        controller.getMyBlueprintSharing(jwt);

    assertTrue(result.shareBlueprintsGlobally());
    assertEquals(4L, result.version());
  }

  @Test
  void updateMyBlueprintSharing_resolvesIdFromJwt_andForwardsFlagAndVersion() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    MyBlueprintSharingController.MyBlueprintSharingRequest req =
        new MyBlueprintSharingController.MyBlueprintSharingRequest(true, 2L);
    when(memberBlueprintSharings.update(CALLER_ID, true, 2L))
        .thenReturn(new MemberBlueprintSharings.Snapshot(true, 3L));

    MyBlueprintSharingController.MyBlueprintSharingResponse result =
        controller.updateMyBlueprintSharing(jwt, req);

    assertTrue(result.shareBlueprintsGlobally());
    assertEquals(3L, result.version());
    verify(memberBlueprintSharings).update(CALLER_ID, true, 2L);
  }
}
