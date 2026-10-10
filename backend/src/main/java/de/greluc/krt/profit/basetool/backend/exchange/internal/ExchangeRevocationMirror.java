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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Where denied installation keys and client revocations are mirrored for the gateway, which reads
 * them per request (REQ-XCH-008). Every entry expires {@link #RETENTION} after its revocation, when
 * no token issued before it can still be valid.
 */
public interface ExchangeRevocationMirror {

  /**
   * How long a denial or revocation is enforced: the 90-day maximum lifespan the provisioner pins
   * on every exchange client's online and offline session, so no session that existed at the
   * revocation outlives it (ADR-0217).
   */
  Duration RETENTION = Duration.ofDays(90);

  /**
   * Tells whether this mirror stores anything.
   *
   * @return {@code true} when writes reach Redis
   */
  boolean isActive();

  /**
   * Denies a key until {@link #RETENTION} after the revocation.
   *
   * @param keyThumbprint the DPoP key thumbprint
   * @param revokedAt when the installation was revoked
   * @throws RuntimeException when the write did not reach Redis
   */
  void deny(@NotNull String keyThumbprint, @NotNull Instant revokedAt);

  /**
   * Records a client revocation until {@link #RETENTION} after it.
   *
   * @param clientId the Keycloak client id
   * @param member the member
   * @param revokedAt when the member disconnected the client
   * @throws RuntimeException when the write did not reach Redis
   */
  void revoke(@NotNull String clientId, @NotNull UUID member, @NotNull Instant revokedAt);

  /**
   * Tells whether a key's denial is mirrored.
   *
   * @param keyThumbprint the DPoP key thumbprint
   * @return {@code true} when the mirror holds it
   * @throws RuntimeException when Redis could not be read
   */
  boolean isDenied(@NotNull String keyThumbprint);

  /**
   * Tells whether a client revocation is mirrored with exactly this time.
   *
   * @param clientId the Keycloak client id
   * @param member the member
   * @param revokedAt the revocation time the database holds
   * @return {@code true} when the mirror holds that time
   * @throws RuntimeException when Redis could not be read
   */
  boolean isRevoked(@NotNull String clientId, @NotNull UUID member, @NotNull Instant revokedAt);

  /**
   * Reads when a member last disconnected a client, as the gateway reads it.
   *
   * @param clientId the Keycloak client id
   * @param member the member
   * @return the mirrored revocation time, or {@code null} when none is mirrored
   * @throws RuntimeException when Redis could not be read or holds an unreadable entry
   */
  @Nullable
  Instant revokedAt(@NotNull String clientId, @NotNull UUID member);
}
