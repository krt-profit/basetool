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

package de.greluc.krt.profit.basetool.frontend.personalinventory.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.Data;

/**
 * Form-backing bean for the personal inventory create/update modal. Mirrors backend validation
 * rules so feedback can be rendered before the request hits the backend.
 */
@Data
public class PersonalInventoryForm {

  /** Optional: only present when editing an existing item. */
  private UUID id;

  @NotBlank(message = "{personalInventory.validation.name.required}")
  @Size(max = 120, message = "{personalInventory.validation.name.tooLong}")
  private String name;

  @Size(max = 2000, message = "{personalInventory.validation.note.tooLong}")
  private String note;

  @NotNull(message = "{personalInventory.validation.location.required}")
  private Integer locationUexId;

  @NotNull(message = "{personalInventory.validation.location.required}")
  private PersonalInventoryLocationType locationType;

  /** Display snapshot used to repopulate the typeahead input on validation re-render. */
  private String locationName;

  @NotNull(message = "{personalInventory.validation.quantity.required}")
  @Min(value = 1, message = "{personalInventory.validation.quantity.min}")
  private Integer quantity;

  /** Optimistic-locking version, populated on update only. */
  private Long version;
}
