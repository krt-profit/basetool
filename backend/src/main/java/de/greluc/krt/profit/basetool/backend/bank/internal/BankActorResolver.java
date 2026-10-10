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

import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/** Names the member whose request is running, for the bank notices. */
@Component
@RequiredArgsConstructor
class BankActorResolver {

  private final AuthHelperService authHelperService;
  private final UserRepository userRepository;

  /**
   * The acting member with the name notifications show.
   *
   * @return the caller, or the system actor when no member is authenticated
   */
  @NotNull
  ActorRef current() {
    return authHelperService
        .currentUserId()
        .map(
            id ->
                new ActorRef(
                    id,
                    userRepository
                        .findById(id)
                        .map(User::getEffectiveName)
                        .map(StringNormalization::trimToNull)
                        .orElse(ActorRef.UNKNOWN_NAME)))
        .orElseGet(ActorRef::system);
  }
}
