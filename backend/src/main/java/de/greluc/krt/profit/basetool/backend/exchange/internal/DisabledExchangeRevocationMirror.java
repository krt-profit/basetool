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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The revocation mirror used while Redis mirroring is off: it stores nothing. */
final class DisabledExchangeRevocationMirror implements ExchangeRevocationMirror {

  /**
   * Always {@code false}.
   *
   * @return {@code false}
   */
  @Override
  public boolean isActive() {
    return false;
  }

  @Override
  public void deny(@NotNull String keyThumbprint, @NotNull Instant revokedAt) {}

  @Override
  public void revoke(@NotNull String clientId, @NotNull UUID member, @NotNull Instant revokedAt) {}

  /**
   * Always {@code true}, so the reconcile has nothing to repair.
   *
   * @param keyThumbprint ignored
   * @return {@code true}
   */
  @Override
  public boolean isDenied(@NotNull String keyThumbprint) {
    return true;
  }

  /**
   * Always {@code true}, so the reconcile has nothing to repair.
   *
   * @param clientId ignored
   * @param member ignored
   * @param revokedAt ignored
   * @return {@code true}
   */
  @Override
  public boolean isRevoked(
      @NotNull String clientId, @NotNull UUID member, @NotNull Instant revokedAt) {
    return true;
  }

  /**
   * Always {@code null}: nothing is mirrored; the backend's gate reads the stored revocations
   * instead, and the gateway finds no registry or one {@link ExchangeRegistryMirrorClosure}
   * switched off.
   *
   * @param clientId ignored
   * @param member ignored
   * @return {@code null}
   */
  @Override
  public @Nullable Instant revokedAt(@NotNull String clientId, @NotNull UUID member) {
    return null;
  }
}
