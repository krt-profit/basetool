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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemBlueprintOwnersDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemStockGroupDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronDto;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Builds the page records a stubbed {@link JobOrderPageReader} answers with in the page render
 * tests, so a test states only the parts it asserts on.
 */
final class JobOrderPageStubs {

  private JobOrderPageStubs() {}

  /**
   * An available order list page holding the envelope's orders and the default thresholds.
   *
   * @param page the order page envelope
   * @return the list page
   */
  @NotNull
  static JobOrderPageReader.OrderListPage list(@NotNull PageResponse<JobOrderDto> page) {
    return new JobOrderPageReader.OrderListPage(
        true, page.content(), Optional.of(page), 30, 90, List.of());
  }

  /**
   * An available order list page with squadrons for the filter picker.
   *
   * @param page the order page envelope
   * @param squadrons the active squadrons
   * @return the list page
   */
  @NotNull
  static JobOrderPageReader.OrderListPage list(
      @NotNull PageResponse<JobOrderDto> page, @NotNull List<SquadronDto> squadrons) {
    return new JobOrderPageReader.OrderListPage(
        true, page.content(), Optional.of(page), 30, 90, squadrons);
  }

  /**
   * Starts a detail page of a caller who may not assign, with no optional part.
   *
   * @param order the order
   * @return the builder
   */
  @NotNull
  static DetailBuilder detail(@NotNull JobOrderDto order) {
    return new DetailBuilder(order);
  }

  /** Builds a {@link JobOrderPageReader.OrderDetailPage} from the parts a test sets. */
  static final class DetailBuilder {

    private final JobOrderDto order;
    private boolean canAssign;
    private Optional<UUID> currentUserId = Optional.empty();
    private Optional<List<MaterialDto>> materials = Optional.empty();
    private Optional<List<SquadronDto>> squadrons = Optional.empty();
    private Optional<List<OrgUnitMembershipOptionDto>> requestingOptions = Optional.empty();
    private JobOrderPageReader.Part<UserDto> actingUser = JobOrderPageReader.Part.absent();
    private JobOrderPageReader.Part<JobOrderItemBlueprintOwnersDto> blueprintOwners =
        JobOrderPageReader.Part.absent();
    private JobOrderPageReader.Part<Map<UUID, JobOrderItemStockGroupDto>> itemStock =
        JobOrderPageReader.Part.absent();
    private JobOrderPageReader.Part<List<InventoryItemDto>> orphanedInventory =
        JobOrderPageReader.Part.absent();

    private DetailBuilder(JobOrderDto order) {
      this.order = order;
    }

    /**
     * Marks the caller as assigning and sets the catalogs of the parallel read.
     *
     * @param materialCatalog the material catalog
     * @param squadronCatalog the squadron catalog
     * @param options the requesting org-unit options
     * @return this builder
     */
    DetailBuilder assigning(
        List<MaterialDto> materialCatalog,
        List<SquadronDto> squadronCatalog,
        List<OrgUnitMembershipOptionDto> options) {
      this.canAssign = true;
      this.materials = Optional.of(materialCatalog);
      this.squadrons = Optional.of(squadronCatalog);
      this.requestingOptions = Optional.of(options);
      return this;
    }

    /**
     * Sets the material catalog of a requester view.
     *
     * @param materialCatalog the material catalog
     * @return this builder
     */
    DetailBuilder requesterMaterials(List<MaterialDto> materialCatalog) {
      this.materials = Optional.of(materialCatalog);
      return this;
    }

    /**
     * Sets the caller's user id.
     *
     * @param id the id
     * @return this builder
     */
    DetailBuilder currentUserId(UUID id) {
      this.currentUserId = Optional.of(id);
      return this;
    }

    /**
     * Sets the production modal's acting user.
     *
     * @param user the user, or {@code null} for a failed lookup
     * @return this builder
     */
    DetailBuilder actingUser(UserDto user) {
      this.actingUser = JobOrderPageReader.Part.of(user);
      return this;
    }

    /**
     * Sets the blueprint owners.
     *
     * @param owners the owners
     * @return this builder
     */
    DetailBuilder blueprintOwners(JobOrderItemBlueprintOwnersDto owners) {
      this.blueprintOwners = JobOrderPageReader.Part.of(owners);
      return this;
    }

    /**
     * Sets the stock groups keyed by game item.
     *
     * @param stock the stock groups
     * @return this builder
     */
    DetailBuilder itemStock(Map<UUID, JobOrderItemStockGroupDto> stock) {
      this.itemStock = JobOrderPageReader.Part.of(stock);
      return this;
    }

    /**
     * Sets the orphaned inventory.
     *
     * @param inventory the rows
     * @return this builder
     */
    DetailBuilder orphanedInventory(List<InventoryItemDto> inventory) {
      this.orphanedInventory = JobOrderPageReader.Part.of(inventory);
      return this;
    }

    /**
     * Builds the page with the default aging thresholds.
     *
     * @return the detail page
     */
    JobOrderPageReader.OrderDetailPage build() {
      return new JobOrderPageReader.OrderDetailPage(
          order,
          canAssign,
          currentUserId,
          materials,
          squadrons,
          requestingOptions,
          actingUser,
          30,
          90,
          blueprintOwners,
          itemStock,
          orphanedInventory);
    }
  }

  /**
   * An editable item-order edit page.
   *
   * @param order the order
   * @param materials the material catalog
   * @param requestingOptions the requesting org-unit options
   * @return the edit page
   */
  @NotNull
  static JobOrderPageReader.ItemEditPage editable(
      @NotNull JobOrderDto order,
      @NotNull List<MaterialDto> materials,
      @NotNull List<OrgUnitMembershipOptionDto> requestingOptions) {
    return new JobOrderPageReader.ItemEditPage(
        JobOrderPageReader.ItemEditAccess.EDITABLE,
        Optional.of(order),
        materials,
        true,
        List.of(),
        requestingOptions);
  }

  /**
   * A blocked item-order edit page.
   *
   * @param access why the form is not offered
   * @param order the order, when it was read
   * @return the edit page
   */
  @NotNull
  static JobOrderPageReader.ItemEditPage blocked(
      @NotNull JobOrderPageReader.ItemEditAccess access, @NotNull Optional<JobOrderDto> order) {
    return new JobOrderPageReader.ItemEditPage(
        access, order, List.of(), true, List.of(), List.of());
  }

  /**
   * A create page with the given catalogs.
   *
   * @param materials the material catalog
   * @param requestingOptions the requesting org-unit options
   * @return the create page
   */
  @NotNull
  static JobOrderPageReader.OrderCreatePage create(
      @NotNull List<MaterialDto> materials,
      @NotNull List<OrgUnitMembershipOptionDto> requestingOptions) {
    return new JobOrderPageReader.OrderCreatePage(materials, true, List.of(), requestingOptions);
  }
}
