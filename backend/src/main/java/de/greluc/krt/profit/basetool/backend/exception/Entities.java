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

package de.greluc.krt.profit.basetool.backend.exception;

import java.util.Optional;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;

/**
 * Fetch-or-throw helper for the repository-lookup idiom that pervades the service layer (S1, part
 * of #905).
 *
 * <p>Replaced the 287 hand-written {@code repository.find*(id).orElseThrow(() -> new
 * NotFoundException("…"))} sites — the single largest duplication surface in the services — with
 * {@link #require(Optional, String) Entities.require(repository.findById(id), "…")} (BE-SIMP-01);
 * {@code EntitiesRequireRatchetTest} keeps the hand-written count at zero. The value is returned
 * when present; otherwise a {@link NotFoundException} carrying the caller-supplied message is
 * thrown, which {@code GlobalExceptionHandler} maps to an HTTP 404 RFC&nbsp;7807 problem.
 *
 * <p>The message is <b>always caller-supplied, never auto-derived from the type</b>: {@code
 * GlobalExceptionHandler.resolveDetail} treats a not-found message as a translation key (guarded by
 * a message-source sentinel), so auto-deriving it would change the wire {@code detail} of the ~170
 * bare-message sites and break the future i18n-key migration seam. Two overloads mirror the only
 * two message styles that exist in the codebase: a constant string (e.g. {@code "Mission not
 * found"}) and a lazily-built one that interpolates the id (e.g. {@code () -> "Mission not found: "
 * + id}). Lives in the {@code exception} package next to {@link NotFoundException} rather than the
 * {@code support} leaf on purpose — {@code exception} already depends on {@code support}, so a
 * {@code support} helper throwing {@code NotFoundException} would close a package cycle (ADR-0047).
 */
public final class Entities {

  /** Non-instantiable static-helper holder. */
  private Entities() {}

  /**
   * Returns the value of a present {@code optional}, or throws {@link NotFoundException} with the
   * given constant {@code message} when it is empty. Use this overload for the bare {@code "X not
   * found"} style; the message is a literal, so eager evaluation costs nothing.
   *
   * @param optional the repository-lookup result to unwrap
   * @param message the RFC&nbsp;7807 {@code detail} to raise when the entity is absent
   * @param <T> the entity type
   * @return the contained value when present
   * @throws NotFoundException when {@code optional} is empty
   */
  public static <T> @NotNull T require(@NotNull Optional<T> optional, @NotNull String message) {
    return optional.orElseThrow(() -> new NotFoundException(message));
  }

  /**
   * Returns the value of a present {@code optional}, or throws {@link NotFoundException} with the
   * lazily-built {@code message} when it is empty. Use this overload for the id-suffixed {@code ()
   * -> "X not found: " + id} style so the string is only interpolated on the (rare) miss, exactly
   * as the hand-written {@code orElseThrow(() -> …)} lambda did.
   *
   * @param optional the repository-lookup result to unwrap
   * @param message supplies the RFC&nbsp;7807 {@code detail} to raise when the entity is absent;
   *     invoked only on a miss
   * @param <T> the entity type
   * @return the contained value when present
   * @throws NotFoundException when {@code optional} is empty
   */
  public static <T> @NotNull T require(
      @NotNull Optional<T> optional, @NotNull Supplier<String> message) {
    return optional.orElseThrow(() -> new NotFoundException(message.get()));
  }
}
