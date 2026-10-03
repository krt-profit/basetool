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

package de.greluc.krt.profit.basetool.backend.model.dto.exchange;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A client's batch of blueprint changes, as the ingest gateway relays it after checking it against
 * {@code change-set.schema.json#/$defs/blueprintChangeSet} (REQ-XCH-015).
 *
 * @param ops the changes, at most 500
 * @param dryRun whether to report what would happen without writing
 */
public record ExchangeBlueprintChangeSet(
    @NotNull @Size(min = 1, max = 500) List<@NotNull @Valid Op> ops, boolean dryRun) {

  /**
   * One change.
   *
   * @param opId the client's id for the op, echoed in the result, or {@code null}
   * @param op {@code add} or {@code remove}
   * @param ref the product, for an add and optionally a remove
   * @param key the feed key of the product to remove, or {@code null}
   * @param acquiredAt when the member acquired it, for an add, or {@code null}
   * @param provenance where the client has it from, for an add, or {@code null}
   * @param override whether an add may re-add an entry removed elsewhere, after asking the member
   */
  @Schema(name = "ExchangeBlueprintOp")
  public record Op(
      @Nullable @Size(min = 1, max = 64) String opId,
      @NotNull @Pattern(regexp = "^(add|remove)$") String op,
      @Nullable @Valid ExchangeItemRef ref,
      @Nullable @Size(min = 1, max = 128) String key,
      @Nullable Instant acquiredAt,
      @Nullable @Valid Provenance provenance,
      @Nullable Boolean override) {}

  /**
   * Where a client has an entry from, in the shape of {@code provenance.schema.json}.
   *
   * @param source {@code log}, {@code manual}, {@code import}, {@code default} or {@code other}
   * @param observedAt when the client observed it, or {@code null}
   */
  @Schema(name = "Provenance")
  public record Provenance(
      @NotNull @Pattern(regexp = "^(log|manual|import|default|other)$") String source,
      @Nullable Instant observedAt) {}
}
