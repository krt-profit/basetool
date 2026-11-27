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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AccountPresence;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Answers {@link AccountPresence} from the {@code app_user} table. */
@Component
@RequiredArgsConstructor
public class UserAccountPresence implements AccountPresence {

  private final UserRepository userRepository;

  @Override
  @Transactional(readOnly = true)
  public @NotNull @Unmodifiable Set<UUID> withoutAccount(@NotNull Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Set.of();
    }
    Set<UUID> missing = new HashSet<>(ids);
    missing.removeAll(userRepository.findExistingIds(ids));
    return Set.copyOf(missing);
  }
}
