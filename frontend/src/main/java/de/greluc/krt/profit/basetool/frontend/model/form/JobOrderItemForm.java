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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Data;

/**
 * Backing form for the item-order create page. Mirrors {@link JobOrderForm}'s shape (org-unit
 * picker, handle, comment) but carries finished-item lines instead of raw materials. The nested
 * line + material lists are populated by indexed binding from the dynamically-built item editor
 * (Spring auto-grows the lists on bind), so they start empty.
 */
@Data
public class JobOrderItemForm {

  /**
   * The profit-eligible org unit that will process the order (responsible picker output); ignored
   * for guests, who are routed to the intake SK.
   */
  private UUID responsibleOrgUnitId;

  /** The customer org unit the order is placed for (requesting picker output). */
  private UUID requestingOrgUnitId;

  /** Optional contact handle. */
  private String handle;

  /** Optional free-text comment. */
  private String comment;

  /** Optimistic-lock version (unused on create). */
  private Long version;

  /** Optional create-source marker carried through the form (mirrors {@link JobOrderForm}). */
  private String source;

  /** Ordered finished-item lines, bound by index from the item editor. */
  private List<JobOrderItemLineForm> items = new ArrayList<>();

  /**
   * One ordered finished-item line: the item, its chosen blueprint, amount, and quality choices.
   */
  @Data
  public static class JobOrderItemLineForm {

    /**
     * The persistent id of the existing line this row edits, or {@code null} for a newly added row.
     * Rendered as a hidden input by the item editor in edit mode and passed straight through to the
     * backend, which uses it to update the line in place so its booked production survives
     * (REQ-ORDERS-032).
     */
    private UUID id;

    /** The finished item to order. */
    private UUID gameItemId;

    /** The blueprint chosen to produce the item. */
    private UUID blueprintId;

    /** Whole-unit count to order. */
    private Integer amount;

    /** Transient client id of this line, used to link adopted sub-assembly lines. */
    private Integer clientLineId;

    /** Transient client id of the line this one was adopted from, or {@code null}. */
    private Integer parentClientLineId;

    /** Per-material quality choices derived for this line. */
    private List<JobOrderItemMaterialForm> materials = new ArrayList<>();
  }

  /** One per-material quality choice on an item line. */
  @Data
  public static class JobOrderItemMaterialForm {

    /** The material the choice applies to. */
    private UUID materialId;

    /** The chosen quality requirement name ({@code GOOD} or {@code NONE}). */
    private String quality;
  }
}
