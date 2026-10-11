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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Data;

/** Form-binding object for Job Order input. */
@Data
public class JobOrderForm {
  /**
   * UUID of the profit-eligible org unit that will <em>process</em> the order (the responsible org
   * unit). Bound by the responsible picker on the create form; forwarded as {@code
   * responsibleOrgUnitId} on the backend {@code CreateJobOrderDto}. Ignored for guests, who are
   * routed onto the configured intake SK.
   */
  private UUID responsibleOrgUnitId;

  /**
   * UUID of the customer org unit the order is placed for (the requesting org unit). Bound by the
   * requesting picker; forwarded as {@code requestingOrgUnitId}. Any squadron or Spezialkommando.
   */
  private UUID requestingOrgUnitId;

  private String handle;

  /** Optional free-text comment captured from the order creator; HTML-escaped on display. */
  private String comment;

  private Long version;
  private String source;
  private List<JobOrderMaterialForm> materials = new ArrayList<>();

  /** Seeds the form with one empty material row so the "add material" UI starts non-empty. */
  public JobOrderForm() {
    materials.add(new JobOrderMaterialForm());
  }

  /** Form-binding object for Job Order Material input. */
  @Data
  public static class JobOrderMaterialForm {
    private UUID materialId;
    private Integer minQuality = 650;
    private Double amount;
  }
}
