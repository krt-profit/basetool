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

package de.greluc.krt.profit.basetool.backend.mission.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.identity.api.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.mission.api.MemberPayoutSettings;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit tests for {@link MyPayoutPreferenceController}: the caller id comes from the JWT, the
 * preference and the setting's version are forwarded and returned.
 */
@ExtendWith(MockitoExtension.class)
class MyPayoutPreferenceControllerTest {

  @Mock private UserService userService;
  @Mock private MemberPayoutSettings memberPayoutSettings;
  @Mock private Jwt jwt;
  @InjectMocks private MyPayoutPreferenceController controller;

  private static final UUID CALLER_ID = UUID.randomUUID();

  @Test
  void getMyPayoutPreference_resolvesIdFromJwt_andReturnsPreferenceAndVersion() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    when(memberPayoutSettings.snapshotOf(CALLER_ID))
        .thenReturn(new MemberPayoutSettings.Snapshot(PayoutPreference.DONATE, 4L));

    MyPayoutPreferenceController.MyPayoutPreferenceResponse result =
        controller.getMyPayoutPreference(jwt);

    assertEquals(PayoutPreference.DONATE, result.defaultPayoutPreference());
    assertEquals(4L, result.version());
  }

  @Test
  void getMyPayoutPreference_returnsNullPreference_whenUserNeverChose() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    when(memberPayoutSettings.snapshotOf(CALLER_ID))
        .thenReturn(new MemberPayoutSettings.Snapshot(null, 0L));

    MyPayoutPreferenceController.MyPayoutPreferenceResponse result =
        controller.getMyPayoutPreference(jwt);

    assertNull(
        result.defaultPayoutPreference(), "no explicit choice surfaces as a null preference");
    assertEquals(0L, result.version());
  }

  @Test
  void updateMyPayoutPreference_resolvesIdFromJwt_andForwardsPreferenceAndVersion() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    MyPayoutPreferenceController.MyPayoutPreferenceRequest req =
        new MyPayoutPreferenceController.MyPayoutPreferenceRequest(PayoutPreference.DONATE, 2L);
    when(memberPayoutSettings.update(CALLER_ID, PayoutPreference.DONATE, 2L))
        .thenReturn(new MemberPayoutSettings.Snapshot(PayoutPreference.DONATE, 3L));

    MyPayoutPreferenceController.MyPayoutPreferenceResponse result =
        controller.updateMyPayoutPreference(jwt, req);

    assertEquals(PayoutPreference.DONATE, result.defaultPayoutPreference());
    assertEquals(3L, result.version());
    verify(memberPayoutSettings).update(CALLER_ID, PayoutPreference.DONATE, 2L);
  }
}
