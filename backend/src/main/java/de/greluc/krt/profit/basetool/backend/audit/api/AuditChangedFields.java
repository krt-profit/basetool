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

package de.greluc.krt.profit.basetool.backend.audit.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Collects the names of the fields an update changes, for the {@code changed=a,b} payload of an
 * update audit event; the values themselves never enter the payload (REQ-AUDIT-001).
 */
public final class AuditChangedFields {

  /** The names of the differing fields, in the order they were compared. */
  private final List<String> names = new ArrayList<>();

  /** Creates an empty collector; callers start through {@link #create()}. */
  private AuditChangedFields() {}

  /**
   * Starts an empty collector.
   *
   * @return a collector that names no field yet
   */
  @Contract(" -> new")
  public static @NotNull AuditChangedFields create() {
    return new AuditChangedFields();
  }

  /**
   * Names a field when its value before the update differs from the value after it.
   *
   * @param field the field name as the payload shows it
   * @param before the stored value, or {@code null}
   * @param after the incoming value, or {@code null}
   * @return this collector, for chaining
   */
  @Contract("_, _, _ -> this")
  public @NotNull AuditChangedFields compare(
      @NotNull String field, @Nullable Object before, @Nullable Object after) {
    if (!Objects.equals(before, after)) {
      names.add(field);
    }
    return this;
  }

  /**
   * Whether no compared field differs, in which case the update records no event.
   *
   * @return {@code true} when nothing changed
   */
  public boolean isEmpty() {
    return names.isEmpty();
  }

  /**
   * The payload naming the changed fields.
   *
   * @return {@code changed=} followed by the comma-separated field names
   */
  public @NotNull AuditDetails toDetails() {
    return AuditDetails.of("changed", String.join(",", names));
  }
}
