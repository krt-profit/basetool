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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.AccessGateService;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mockito.quality.Strictness;

/**
 * The real {@link SupplyAccessPolicy} over the real {@link AccessGateService} and a mocked request
 * scope, so a test runs the supply module's manage rule (REQ-ORG-032, REQ-SUP-003) instead of
 * stubbing it. The scope mocks are lenient, as a test may never ask the question they answer.
 */
final class SupplyAccessPolicies {

  /** Non-instantiable fixture holder. */
  private SupplyAccessPolicies() {}

  /**
   * The policy of an admin, who belongs to the governing leadership under any pin.
   *
   * @param pin the honoured pin, or {@code null} for an admin without one
   * @return the policy
   */
  static @NotNull SupplyAccessPolicy admin(@Nullable UUID pin) {
    AuthHelperService auth = lenient(AuthHelperService.class);
    when(auth.isAdmin()).thenReturn(true);
    RequestScopeResolver resolver = lenient(RequestScopeResolver.class);
    when(resolver.currentScopePredicate())
        .thenReturn(
            pin == null
                ? new ScopePredicate(true, null, Set.of())
                : new ScopePredicate(false, pin, Set.of()));
    return policy(resolver, auth);
  }

  /**
   * The policy of a member outside the governing leadership, without a pin, whose lead or
   * Logistician grants cover the given units.
   *
   * @param units the units the member manages
   * @return the policy
   */
  static @NotNull SupplyAccessPolicy manager(@NotNull Set<UUID> units) {
    return manager(units, units);
  }

  /**
   * The policy of a member outside the governing leadership whose grants cover {@code units}, of
   * which {@code inActiveContext} remain under the member's pin.
   *
   * @param units the units the member manages in any context
   * @param inActiveContext the units the member manages in the active context
   * @return the policy
   */
  static @NotNull SupplyAccessPolicy manager(
      @NotNull Set<UUID> units, @NotNull Set<UUID> inActiveContext) {
    RequestScopeResolver resolver = lenient(RequestScopeResolver.class);
    when(resolver.currentGrantedOrgUnitIds(SupplyAccessPolicy.UNIT_MANAGER_ROLE)).thenReturn(units);
    when(resolver.currentGrantedOrgUnitIdsInActiveContext(SupplyAccessPolicy.UNIT_MANAGER_ROLE))
        .thenReturn(inActiveContext);
    return policy(resolver, lenient(AuthHelperService.class));
  }

  /**
   * Wires the policy over the scope mocks; the settings name no governing Bereich.
   *
   * @param resolver the mocked request scope
   * @param auth the mocked caller
   * @return the policy
   */
  private static @NotNull SupplyAccessPolicy policy(
      @NotNull RequestScopeResolver resolver, @NotNull AuthHelperService auth) {
    return new SupplyAccessPolicy(
        resolver,
        new AccessGateService(resolver, auth),
        auth,
        mock(SupplySettingsService.class),
        mock());
  }

  /**
   * Creates a lenient mock.
   *
   * @param type the mocked type
   * @param <T> the mocked type
   * @return the mock
   */
  private static <T> @NotNull T lenient(@NotNull Class<T> type) {
    return mock(type, withSettings().strictness(Strictness.LENIENT));
  }
}
