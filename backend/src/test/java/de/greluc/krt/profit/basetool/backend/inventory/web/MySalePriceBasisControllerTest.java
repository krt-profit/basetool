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

package de.greluc.krt.profit.basetool.backend.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.MemberSaleValueSettings;
import de.greluc.krt.profit.basetool.backend.inventory.internal.MySalePriceBasisRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.MySalePriceBasisResponse;
import de.greluc.krt.profit.basetool.backend.inventory.internal.SalePriceBasis;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit tests for {@link MySalePriceBasisController} (REQ-INV-072): the caller comes from the JWT,
 * the basis and the setting's version are forwarded and returned.
 */
@ExtendWith(MockitoExtension.class)
class MySalePriceBasisControllerTest {

  private static final UUID CALLER = UUID.randomUUID();

  @Mock private UserService userService;
  @Mock private MemberSaleValueSettings memberSaleValueSettings;
  @Mock private Jwt jwt;
  @InjectMocks private MySalePriceBasisController controller;

  @Test
  void readsTheCallersBasisAndVersion() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER);
    when(memberSaleValueSettings.snapshotOf(CALLER))
        .thenReturn(new MemberSaleValueSettings.Snapshot(SalePriceBasis.AVERAGE, 0L));

    assertThat(controller.getMySalePriceBasis(jwt))
        .isEqualTo(new MySalePriceBasisResponse(SalePriceBasis.AVERAGE, 0L));
  }

  @Test
  void writesTheCallersBasisWithTheEchoedVersion() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER);
    when(memberSaleValueSettings.update(CALLER, SalePriceBasis.HIGHEST, 2L))
        .thenReturn(new MemberSaleValueSettings.Snapshot(SalePriceBasis.HIGHEST, 3L));

    assertThat(
            controller.updateMySalePriceBasis(
                jwt, new MySalePriceBasisRequest(SalePriceBasis.HIGHEST, 2L)))
        .isEqualTo(new MySalePriceBasisResponse(SalePriceBasis.HIGHEST, 3L));
  }
}
