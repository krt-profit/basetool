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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The scope module's active org unit for the {@code orgUnitId} MDC field. */
class ScopeActiveOrgUnitProviderTest {

  private final RequestScopeResolver requestScopeResolver = mock(RequestScopeResolver.class);

  private final ScopeActiveOrgUnitProvider provider =
      new ScopeActiveOrgUnitProvider(requestScopeResolver);

  @Test
  void answersTheOrgUnitTheRequestIsFilteredBy() {
    UUID orgUnitId = UUID.randomUUID();
    when(requestScopeResolver.currentSquadronId()).thenReturn(Optional.of(orgUnitId));

    assertThat(provider.activeOrgUnitId()).contains(orgUnitId);
  }

  @Test
  void isEmptyWhenNoFilterApplies() {
    when(requestScopeResolver.currentSquadronId()).thenReturn(Optional.empty());

    assertThat(provider.activeOrgUnitId()).isEmpty();
  }
}
