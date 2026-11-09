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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.OfferedLocations;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeRules;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.refinery.api.events.RefineryNotices;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The game-data and finances rules the web's and the exchange's writes of a refinery order share
 * (REQ-REFINERY-022, REQ-REFINERY-023, REQ-REFINERY-029).
 *
 * <p>Stateless; every method takes what it needs.
 */
final class RefineryOrderEdits {

  private RefineryOrderEdits() {}

  /**
   * Refuses a status change of a stored order.
   *
   * @param order the locked order
   * @param requested the status a write sets, or {@code null} when it sets none
   * @throws BadRequestException when the order is stored and the status would change
   */
  static void requireStatusUnlocked(
      @NotNull RefineryOrder order, @Nullable RefineryOrderStatus requested) {
    if (order.getStoredAt() != null && requested != null && requested != order.getStatus()) {
      throw new BadRequestException("error.refinery_order.stored_status_locked");
    }
  }

  /**
   * Loads the location a write moves the order to: an offered one, or the order's current one.
   *
   * @param repository the location repository
   * @param order the locked order
   * @param locationId the requested location
   * @return the location
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when it does not exist
   * @throws de.greluc.krt.profit.basetool.backend.catalogue.api.LocationNotOfferedException when it
   *     is neither offered nor the current one
   */
  static @NotNull Location offeredOrCurrent(
      @NotNull LocationRepository repository,
      @NotNull RefineryOrder order,
      @NotNull UUID locationId) {
    return OfferedLocations.requireOfferedOrCurrent(
        Entities.require(repository.findById(locationId), "error.location.not_found"),
        order.getLocation());
  }

  /**
   * Refuses a location without a refinery (REQ-REFINERY-020).
   *
   * @param location the location
   * @return the same location
   * @throws BadRequestException {@code error.refinery_order.location_without_refinery} when it
   *     hosts no refinery
   */
  static @NotNull Location requireRefinery(@NotNull Location location) {
    if (!RefineryExchangeRules.hostsRefinery(location)) {
      throw new BadRequestException("error.refinery_order.location_without_refinery");
    }
    return location;
  }

  /**
   * Refuses a good's input material that may not be refined (REQ-REFINERY-020).
   *
   * @param material the material
   * @return the same material
   * @throws BadRequestException {@code error.refinery_order.input_material_not_raw} when it is
   *     neither raw nor manual-raw
   */
  static @NotNull Material requireRefinable(@NotNull Material material) {
    if (!RefineryExchangeRules.refinable(material)) {
      throw new BadRequestException("error.refinery_order.input_material_not_raw");
    }
    return material;
  }

  /**
   * Sets the run's start and length; a moved run loses its ready marker so it is announced again
   * (REQ-REFINERY-023).
   *
   * @param order the locked order
   * @param startedAt the start, or {@code null}
   * @param durationMinutes the length in minutes, or {@code null}
   * @return {@code true} when the start or the length changed
   */
  static boolean setRunTime(
      @NotNull RefineryOrder order, @Nullable Instant startedAt, @Nullable Long durationMinutes) {
    boolean changed =
        !Objects.equals(order.getStartedAt(), startedAt)
            || !Objects.equals(order.getDurationMinutes(), durationMinutes);
    order.setStartedAt(startedAt);
    order.setDurationMinutes(durationMinutes);
    if (changed) {
      order.setReadyNotifiedAt(null);
    }
    return changed;
  }

  /**
   * Sets the three money fields as {@link RefineryExchangeRules#money} stores them.
   *
   * @param order the order
   * @param expenses the refining costs
   * @param otherExpenses further costs
   * @param oreSales the raw-ore revenue
   */
  static void setFinances(
      @NotNull RefineryOrder order,
      @Nullable Double expenses,
      @Nullable Double otherExpenses,
      @Nullable Double oreSales) {
    order.setExpenses(RefineryExchangeRules.money(expenses));
    order.setOtherExpenses(RefineryExchangeRules.money(otherExpenses));
    order.setOreSales(RefineryExchangeRules.money(oreSales));
  }

  /**
   * Withdraws the order's ready notice when its run moved or it is closed (REQ-REFINERY-024).
   *
   * @param publisher the event publisher
   * @param saved the saved order
   * @param runTimeChanged whether the write moved the run
   */
  static void clearReadyNoticeIfDue(
      @NotNull ApplicationEventPublisher publisher,
      @NotNull RefineryOrder saved,
      boolean runTimeChanged) {
    if (runTimeChanged
        || saved.getStatus() == RefineryOrderStatus.COMPLETED
        || saved.getStatus() == RefineryOrderStatus.CANCELED) {
      publisher.publishEvent(RefineryNotices.readyCleared(saved.getId()));
    }
  }
}
