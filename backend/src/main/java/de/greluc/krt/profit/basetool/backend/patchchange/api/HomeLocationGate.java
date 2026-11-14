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

package de.greluc.krt.profit.basetool.backend.patchchange.api;

import de.greluc.krt.profit.basetool.backend.kernel.ProblemCode;
import de.greluc.krt.profit.basetool.backend.platform.api.SessionGate;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * The home-location gate (REQ-PATCH-020): a member who must still choose a new home location in
 * the open phase is refused with {@code HOME_LOCATION_REQUIRED}.
 *
 * <p>Runs after the consent gate. An exchange client's relayed request passes: the exchange keeps
 * reading and booking new stock, and the relocation lock refuses its writes on locked rows and
 * ships instead (REQ-PATCH-023).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class HomeLocationGate implements SessionGate {

  /** The prompt and the choice of the caller's own home location. */
  public static final String PROMPT_PATH = "/api/v1/patch-changes/me/home-location";

  /** The cheap check the frontend's gate asks on every request it does not cache. */
  public static final String STATUS_PATH = PROMPT_PATH + "/status";

  /**
   * The endpoints a pending member may still reach: the gate's own and the caller's identity read
   * the frontend's role sync makes.
   */
  public static final List<String> EXEMPT_PATHS =
      List.of(PROMPT_PATH, STATUS_PATH, "/api/v1/users/me");

  /** Tells which members must still choose. */
  private final HomeLocationGateCheck gateCheck;

  @Override
  public @NotNull ProblemCode problemCode() {
    return PatchChangeProblemCode.HOME_LOCATION_REQUIRED;
  }

  @Override
  public @NotNull @Unmodifiable List<String> exemptPaths() {
    return EXEMPT_PATHS;
  }

  @Override
  public boolean holds(@NotNull Authentication authentication, @NotNull UUID userId) {
    boolean exchangeClient =
        authentication instanceof SubjectAuthentication subject
            && subject.externalClient() != null;
    return !exchangeClient && gateCheck.mustChoose(userId);
  }

  @Override
  public @NotNull String fallbackDetail() {
    return "A new home location must be chosen first.";
  }
}
