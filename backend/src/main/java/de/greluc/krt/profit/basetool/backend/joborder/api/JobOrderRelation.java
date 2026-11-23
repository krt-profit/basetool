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

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A current own relation of a member to a job order, one of which puts the order into the member's
 * read set on the exchange (REQ-OXCH-001); the client read carries them in lower case.
 */
public enum JobOrderRelation {

  /** A unit the member directly belongs to is the order's requesting unit. */
  @JsonProperty("requesting")
  REQUESTING,

  /** A unit the member directly belongs to is the order's responsible unit. */
  @JsonProperty("responsible")
  RESPONSIBLE,

  /** A unit the member directly belongs to actively supports the order. */
  @JsonProperty("supporting")
  SUPPORTING,

  /** The member holds an assignee entry on the order. */
  @JsonProperty("assignee")
  ASSIGNEE
}
