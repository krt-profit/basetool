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

import de.greluc.krt.profit.basetool.backend.identity.api.ErasedAccountRegister;
import de.greluc.krt.profit.basetool.backend.identity.api.events.AccountErasedEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The {@code erased_account} table behind {@link ErasedAccountRegister} (REQ-DATA-048). */
@Service
@RequiredArgsConstructor
public class ErasedAccountRegisterService implements ErasedAccountRegister {

  private final ErasedAccountRepository repository;
  private final ApplicationEventPublisher eventPublisher;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void record(@NotNull UUID userId) {
    repository.insertIfAbsent(userId, Instant.now());
    eventPublisher.publishEvent(new AccountErasedEvent(userId));
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isErased(@NotNull UUID userId) {
    return repository.existsById(userId);
  }

  @Override
  @Transactional(readOnly = true)
  public @NotNull @Unmodifiable List<UUID> erasedAccounts() {
    return List.copyOf(repository.findAllIds());
  }

  @Override
  @Transactional(readOnly = true)
  public long pendingReapplications() {
    return repository.countWithAccountRow();
  }

  @Override
  @Transactional
  public int expireBefore(@NotNull Instant cutoff) {
    return repository.deleteErasedBefore(cutoff);
  }
}
