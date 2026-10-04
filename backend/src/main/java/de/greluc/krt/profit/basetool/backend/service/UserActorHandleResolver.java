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

import de.greluc.krt.profit.basetool.backend.audit.api.ActorHandleResolver;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * The identity module's {@link ActorHandleResolver}: the audit actor snapshot is the user's {@link
 * User#getEffectiveName() effective name}.
 */
@Service
@RequiredArgsConstructor
public class UserActorHandleResolver implements ActorHandleResolver {

  private final UserRepository userRepository;

  /**
   * Loads the user and returns their effective name, in the caller's transaction.
   *
   * @param userId the acting user's {@code sub}
   * @return the effective name, or empty when no user has this id
   */
  @Override
  @NotNull
  public Optional<String> handleOf(@NotNull UUID userId) {
    return userRepository.findById(userId).map(User::getEffectiveName);
  }
}
