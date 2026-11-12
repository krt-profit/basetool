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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RetiredBlueprintException;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RetiredGameItemException;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Defines ITEM recipe snapshots and derived material views for owning module callers. */
public interface JobOrderItemService {

  /**
   * Builds one new, detached ordered-item line with its derived material snapshot; the caller
   * attaches it to the {@link JobOrder}. To re-derive an existing line use {@link
   * #applyItemLine(JobOrderItem, CreateJobOrderItemLineDto)}.
   *
   * @param line the line payload (game item, blueprint, amount, quality choices)
   * @return a populated {@link JobOrderItem} with its {@link JobOrderItemMaterial} children
   * @throws NotFoundException when the game item or blueprint id is unknown
   * @throws BadRequestException when the chosen blueprint does not produce the ordered game item
   * @throws RetiredGameItemException when the ordered game item is retired or unobtainable
   * @throws RetiredBlueprintException when the P4K retired the chosen blueprint
   */
  @NotNull
  JobOrderItem buildItemLine(@NotNull CreateJobOrderItemLineDto line);

  /**
   * Builds one detached line from already validated immutable requirements.
   *
   * @param prepared the exact frozen line to build
   * @return the detached item with its prepared material snapshot
   */
  @NotNull
  JobOrderItem buildItemLine(@NotNull JobOrderItemPlan.Line prepared);

  /**
   * Re-derives {@code item} from the payload in place, replacing its {@link JobOrderItemMaterial}
   * snapshot while keeping its identity and its manufactured and delivered amounts
   * (REQ-ORDERS-032).
   *
   * <p>The caller must keep {@code deliveredAmount <= manufacturedAmount <= amount} intact.
   *
   * @param item the line to re-derive, managed or freshly built
   * @param line the payload with game item, blueprint, amount and quality choices
   * @throws NotFoundException when the game item or blueprint id is unknown
   * @throws BadRequestException when the chosen blueprint does not produce the ordered game item
   * @throws RetiredGameItemException when the ordered game item is retired or unobtainable, also
   *     on a line that keeps it
   * @throws RetiredBlueprintException when the P4K retired the chosen blueprint, also on a line
   *     that keeps it
   */
  void applyItemLine(JobOrderItem item, @NotNull CreateJobOrderItemLineDto line);

  /**
   * Applies one already validated prepared line while preserving its identity and booked counters.
   *
   * @param item the existing or detached line
   * @param prepared its exact frozen requirements
   */
  void applyItemLine(@NotNull JobOrderItem item, @NotNull JobOrderItemPlan.Line prepared);

  /**
   * Prepares the exact selected requirements before an action takes its complete resource guards.
   *
   * @param lines the desired item lines in payload order
   * @param keptItems the current order's existing lines
   * @return immutable requirements and the current scalar facts they depend on
   * @throws IllegalArgumentException when a selected quantity cannot be represented exactly
   */
  @NotNull
  JobOrderItemPlan prepareItemLines(
      @NotNull List<CreateJobOrderItemLineDto> lines, @NotNull Collection<JobOrderItem> keptItems);

  /**
   * Refuses a prepared action whose source facts changed while it waited for resource guards.
   *
   * @param prepared the original immutable preparation
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException when a current
   *     recipe, quality, identity or kept-line fact differs
   */
  void validateItemPlan(@NotNull JobOrderItemPlan prepared);

  /**
   * Applies the original prepared requirements without choosing another recipe after a lock wait.
   *
   * @param targets existing or detached item lines in the prepared payload order
   * @param prepared the immutable preparation already validated under the action's resource guards
   */
  void applyItemLines(@NotNull List<JobOrderItem> targets, @NotNull JobOrderItemPlan prepared);

  /**
   * Maps an item order's ordered lines to DTOs, sorted by item name then id for a stable table.
   *
   * @param order the (item) job order whose {@code items} to project
   * @return the ordered-item line DTOs; empty for a material order
   */
  @NotNull
  List<JobOrderItemDto> toItemDtos(@NotNull JobOrder order);

  /**
   * Aggregates the outstanding material demand of all ordered lines into one row per {@code
   * (material, quality tier)}, counting only each line's not-yet-manufactured share. Sorted SCU
   * first, then by name, then by descending tier floor.
   *
   * @param order the job order to aggregate
   * @return the outstanding-material rows; empty for a material order
   */
  @NotNull
  List<AggregatedMaterialDto> aggregateMaterials(@NotNull JobOrder order);

  /**
   * Collects the distinct material ids an order requires: the snapshotted item materials of an
   * {@code ITEM} order, or the material lines of a {@code MATERIAL} order.
   *
   * <p>This is the authoritative set of materials that may be linked to the order. Must run inside
   * a transaction.
   *
   * @param order the order whose required materials to collect.
   * @return the required material ids in insertion order; never {@code null}, possibly empty.
   */
  @NotNull
  Set<UUID> requiredMaterialIds(@NotNull JobOrder order);

  /**
   * Collects the distinct game-item ids an {@code ITEM} order requests; empty for a {@code
   * MATERIAL} order.
   *
   * <p>This is the authoritative set of game items whose stock may be linked to the order
   * (REQ-INV-031). Must run inside a transaction.
   *
   * @param order the order whose requested game items to collect.
   * @return the requested game-item ids in insertion order; never {@code null}.
   */
  @NotNull
  Set<UUID> requiredGameItemIds(@NotNull JobOrder order);

  /**
   * Resolves the available blueprint references for a game item, by output name. Exposed for the
   * create UI's blueprint picker (shown when an item has more than one recipe).
   *
   * @param gameItemId the ordered item
   * @return blueprint references producing that item; empty when none exist
   */
  @NotNull
  List<BlueprintReferenceDto> blueprintsForItem(@NotNull UUID gameItemId);

  /**
   * Previews the material derivation for a chosen blueprint at a given amount: the resolved
   * material requirements (scaled, with their default quality), the adoptable sub-assembly
   * suggestions, and the names of unresolved ingredient lines (for the create-form warning banner).
   *
   * @param blueprintId the chosen blueprint
   * @param amount the whole-unit amount to scale by (clamped to at least 1)
   * @return the derivation preview
   * @throws NotFoundException when the blueprint id is unknown
   * @throws IllegalArgumentException when a selected quantity or unit count is unrepresentable
   */
  @NotNull
  ItemDerivationDto deriveForPreview(@NotNull UUID blueprintId, int amount);
}
