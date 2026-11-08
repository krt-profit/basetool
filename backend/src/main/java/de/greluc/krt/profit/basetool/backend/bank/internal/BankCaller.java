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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankActors;
import de.greluc.krt.profit.basetool.backend.kernel.RequestMemo;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The web side of the bank's actor: the {@link BankActor} of the request's authenticated member,
 * read once per request and member through {@link BankActors}. Knows who calls, never what they may
 * do.
 */
@Component
@RequiredArgsConstructor
public class BankCaller {

  /** Request memo of the actors read in this request, keyed by member id. */
  private static final RequestMemo.Key<Map<UUID, BankActor>> CACHE_KEY_ACTORS =
      RequestMemo.Key.of(BankCaller.class, "actors");

  private final AuthHelperService authHelperService;
  private final BankActors bankActors;

  /**
   * The actor of the current request.
   *
   * @return the authenticated member's actor, or {@link BankActor#NOBODY} without one
   */
  @NotNull
  public BankActor actor() {
    return authHelperService.currentUserId().map(this::actorOf).orElse(BankActor.NOBODY);
  }

  /**
   * The memoised actor of one member.
   *
   * @param userId the member
   * @return the member's actor
   */
  @NotNull
  private BankActor actorOf(@NotNull UUID userId) {
    Map<UUID, BankActor> memo = RequestMemo.getIfBound(CACHE_KEY_ACTORS, HashMap::new);
    return memo == null ? bankActors.of(userId) : memo.computeIfAbsent(userId, bankActors::of);
  }
}
