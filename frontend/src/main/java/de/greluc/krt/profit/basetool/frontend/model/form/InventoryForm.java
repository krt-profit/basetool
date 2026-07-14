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

package de.greluc.krt.profit.basetool.frontend.model.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Data;

/** Form-binding object for Inventory input. */
@Data
public class InventoryForm {
  @NotNull private UUID materialId;

  @NotNull private UUID locationId;

  @NotNull
  @Min(0)
  @Max(1000)
  private Integer quality;

  @NotNull
  @Min(0)
  private Double amount;

  private UUID jobOrderId;

  private UUID missionId;

  private UUID userId;

  private Boolean isGlobal;
  private Boolean personal = false;

  private String source;

  private Long version;

  /**
   * R5.d owner-picker output: the {@code OrgUnit} the new inventory row should land on. {@code
   * null} when the target user has at most one org-unit membership (in that case the fragment stays
   * hidden and the backend stamps the user's home Staffel via the legacy path).
   */
  private UUID owningOrgUnitId;

  /**
   * Per-action stock-merge opt-in (REQ-INV-026): the modal checkbox the user ticks to merge this
   * SCU book-in into a matching existing stack. Ignored for a {@code PIECE} material (which always
   * merges); defaults to {@code false} so an untouched SCU book-in stays append-only.
   */
  private Boolean mergeStock = false;

  /**
   * Variante-C split-at-check-in (REQ-INV-027, R4): the per-target job-order earmarks the user
   * entered in the create form, bound from indexed params (e.g. {@code
   * jobOrderAllocations[0].targetId} / {@code jobOrderAllocations[0].amount}). Empty when no split
   * was entered — the backend then falls back to the single {@link #jobOrderId}.
   */
  private List<AllocationRow> jobOrderAllocations = new ArrayList<>();

  /** The mission counterpart of {@link #jobOrderAllocations} (falls back to {@link #missionId}). */
  private List<AllocationRow> missionAllocations = new ArrayList<>();

  /**
   * One split-at-check-in earmark row: a job-order / mission target and the amount of the new entry
   * to assign to it. Mutable (Lombok {@code @Data} no-arg constructor) so Spring MVC can grow the
   * list during indexed form binding.
   */
  @Data
  public static class AllocationRow {
    private UUID targetId;
    private Double amount;
  }
}
