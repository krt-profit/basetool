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

package de.greluc.krt.profit.basetool.backend.support;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The sentinel that replaces a handle snapshot an Art. 17 request has erased (REQ-SEC-062).
 *
 * <p><b>Why a sentinel and not {@code NULL}.</b> Four of the six columns are {@code NOT NULL} —
 * {@code audit_event.actor_handle}, {@code bank_audit_event.actor_handle}, {@code
 * bank_booking_request.requester_handle} and the two {@code recipient_handle}s — and that
 * constraint is the guarantee that a row always says who acted (REQ-AUDIT-001). Relaxing it to make
 * room for an erasure would weaken the invariant for every row that will ever be written, so that a
 * future bug could insert {@code NULL} silently. A sentinel keeps the constraint and makes "this
 * was erased on request" a distinguishable state rather than an absence.
 *
 * <p><b>Why the value is not a word.</b> It is stored once and rendered in two languages, so it
 * cannot be German or English text without breaking the i18n rule that no user-visible string is
 * hardcoded. {@code #ANONYMISED#} is a discriminator: every human-facing surface maps it to {@code
 * general.anonymisedHandle} from the message bundles, and the hash marks make it impossible for a
 * real handle to collide with it.
 *
 * <p><b>Machine-readable exports keep the raw token.</b> A JSON audit export is evidence rather
 * than prose; a translated placeholder there would vary by the exporting admin's locale and stop
 * being comparable between two exports of the same rows.
 *
 * <p>This constant is mirrored by {@code HandleDisplay} in the frontend module, which holds no
 * backend beans. The two are a <b>mirror pair</b>: changing the token means changing both.
 */
public final class HandleAnonymisation {

  /**
   * The stored replacement for an erased handle snapshot. Deliberately not a word in any language,
   * and deliberately impossible for a real handle to equal.
   */
  public static final String SENTINEL = "#ANONYMISED#";

  /** Not instantiable. */
  private HandleAnonymisation() {}

  /**
   * Tells whether a handle snapshot has been erased on request.
   *
   * @param handle the stored handle snapshot, possibly {@code null}
   * @return {@code true} when the value is the erasure sentinel
   */
  @Contract(value = "null -> false", pure = true)
  public static boolean isAnonymised(@Nullable String handle) {
    return SENTINEL.equals(handle);
  }

  /**
   * The handle as it should appear to a person, for the backend's human-facing renderings (the PDF
   * reports).
   *
   * <p>Only for output a human reads. A machine-readable export keeps the raw token deliberately: a
   * translated placeholder would vary with the exporting admin's locale and stop two exports of the
   * same rows from being comparable.
   *
   * @param handle the stored handle snapshot, possibly {@code null}
   * @param anonymisedLabel the resolved {@code general.anonymisedHandle} label
   * @return the label when the value is the erasure sentinel, otherwise the handle unchanged
   */
  @Contract(pure = true)
  public static @Nullable String humanise(
      @Nullable String handle, @NotNull String anonymisedLabel) {
    return SENTINEL.equals(handle) ? anonymisedLabel : handle;
  }
}
