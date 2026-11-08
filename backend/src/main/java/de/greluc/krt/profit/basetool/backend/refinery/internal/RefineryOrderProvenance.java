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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Where a refinery order came from, who changed it last and which client reported its pickup
 * (REQ-REFINERY-027, REQ-REFINERY-028).
 *
 * <p>The creator never changes; every write of the order or its goods moves the last change. A
 * {@link RefineryChangeSource#CLIENT} source always names its client, a {@link
 * RefineryChangeSource#WEB} source never does.
 */
@Embeddable
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefineryOrderProvenance {

  /** The channel that created the order. */
  @Enumerated(EnumType.STRING)
  @Column(name = "created_source", nullable = false, length = 8)
  @NotNull
  private RefineryChangeSource createdSource = RefineryChangeSource.WEB;

  /** The exchange client that created the order, or {@code null} for the web. */
  @Column(name = "created_client", length = 64)
  @Nullable
  private String createdClient;

  /** The channel of the last change. */
  @Enumerated(EnumType.STRING)
  @Column(name = "last_change_source", nullable = false, length = 8)
  @NotNull
  private RefineryChangeSource lastChangeSource = RefineryChangeSource.WEB;

  /** The exchange client of the last change, or {@code null} for the web. */
  @Column(name = "last_change_client", length = 64)
  @Nullable
  private String lastChangeClient;

  /** When the order or its goods last changed. */
  @Column(name = "last_changed_at", nullable = false)
  private Instant lastChangedAt;

  /** When an exchange client reported the pickup, or {@code null} while none did. */
  @Column(name = "picked_up_at")
  @Nullable
  private Instant pickedUpAt;

  /** The exchange client that reported the pickup, or {@code null} while none did. */
  @Column(name = "picked_up_client", length = 64)
  @Nullable
  private String pickedUpClient;

  /**
   * The provenance of an order created in the web.
   *
   * @param at the creation time, also the first last change
   * @return a web-created provenance
   */
  @Contract("_ -> new")
  public static @NotNull RefineryOrderProvenance createdInWeb(@NotNull Instant at) {
    RefineryOrderProvenance provenance = new RefineryOrderProvenance();
    provenance.recordWebChange(at);
    return provenance;
  }

  /**
   * The provenance of an order an exchange client created.
   *
   * @param clientId the client's id in the exchange registry
   * @param at the creation time, also the first last change
   * @return a client-created provenance
   */
  @Contract("_, _ -> new")
  public static @NotNull RefineryOrderProvenance createdByClient(
      @NotNull String clientId, @NotNull Instant at) {
    RefineryOrderProvenance provenance = new RefineryOrderProvenance();
    provenance.createdSource = RefineryChangeSource.CLIENT;
    provenance.createdClient = clientId;
    provenance.recordClientChange(clientId, at);
    return provenance;
  }

  /**
   * Records a change made in the web; the creator is kept.
   *
   * @param at the time of the change
   */
  public void recordWebChange(@NotNull Instant at) {
    lastChangeSource = RefineryChangeSource.WEB;
    lastChangeClient = null;
    lastChangedAt = at;
  }

  /**
   * Records a change made by an exchange client; the creator is kept.
   *
   * @param clientId the client's id in the exchange registry
   * @param at the time of the change
   */
  public void recordClientChange(@NotNull String clientId, @NotNull Instant at) {
    lastChangeSource = RefineryChangeSource.CLIENT;
    lastChangeClient = clientId;
    lastChangedAt = at;
  }

  /**
   * Records an exchange client's pickup, which is also that client's change.
   *
   * @param clientId the client's id in the exchange registry
   * @param at the time of the pickup
   */
  public void recordPickup(@NotNull String clientId, @NotNull Instant at) {
    pickedUpAt = at;
    pickedUpClient = clientId;
    recordClientChange(clientId, at);
  }

  /** Removes the pickup marker; the last change is left to the write that removes it. */
  public void clearPickup() {
    pickedUpAt = null;
    pickedUpClient = null;
  }
}
