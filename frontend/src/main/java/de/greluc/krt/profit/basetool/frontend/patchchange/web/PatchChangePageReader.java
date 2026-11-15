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

package de.greluc.krt.profit.basetool.frontend.patchchange.web;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import de.greluc.krt.profit.basetool.frontend.patchchange.client.PatchChangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeFormDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeOptions;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchKind;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;

/**
 * Reads the backend data of the admin's patch-change page and returns it as an immutable page
 * record (REQ-PATCH-005, REQ-PATCH-008, ADR-0291).
 *
 * <p>The history and the form are each optional: a part that cannot be read is logged once through
 * {@link SectionFallback} and shows as unavailable, so the other part still renders. A lost session
 * propagates as {@link ReauthenticationRequiredException}. It reads only; writes go through {@link
 * PatchChangeBackendClient}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PatchChangePageReader {

  /** The kind the form starts with, and returns to after a trigger. */
  public static final PatchKind DEFAULT_KIND = PatchKind.REBUILD;

  /** The history shown when it cannot be read. */
  private static final History UNAVAILABLE_HISTORY = new History(false, List.of());

  private final PatchChangeBackendClient patchChangeClient;

  /**
   * The history of every patch change.
   *
   * @param available whether it could be read; {@code false} shows the load-error banner
   * @param entries the patch changes, newest first; empty when unavailable or none was recorded
   */
  public record History(boolean available, @NotNull @Unmodifiable List<PatchChangeDto> entries) {}

  /**
   * The form's defaults.
   *
   * @param form the suggested label, the previous patch change and the presets
   * @param defaults the preset of {@link #DEFAULT_KIND}, or {@code null} when the backend sent none
   *     for it
   */
  public record Form(@NotNull PatchChangeFormDto form, @Nullable PatchChangeOptions defaults) {}

  /**
   * Everything the full page renders.
   *
   * @param history the history
   * @param form the form's defaults; empty when they cannot be read or the backend sent none
   */
  public record AdminPage(@NotNull History history, @NotNull Optional<Form> form) {}

  /**
   * Reads the history alone, for the in-place swap of the history fragment.
   *
   * @return the history, unavailable when it cannot be read
   * @throws ReauthenticationRequiredException when the user's session can no longer reach the
   *     backend
   */
  public @NotNull History history() {
    return SectionFallback.load(
        log,
        "patch-change-history",
        () -> {
          List<PatchChangeDto> entries = patchChangeClient.history();
          return new History(true, entries == null ? List.of() : List.copyOf(entries));
        },
        UNAVAILABLE_HISTORY);
  }

  /**
   * Reads the full page: the history first, then the form's defaults.
   *
   * @return the page
   * @throws ReauthenticationRequiredException when the user's session can no longer reach the
   *     backend
   */
  public @NotNull AdminPage adminPage() {
    History history = history();
    return new AdminPage(history, form());
  }

  /**
   * Reads the form's defaults and the default kind's preset.
   *
   * @return the form, empty when it cannot be read or the backend sent none
   */
  private @NotNull Optional<Form> form() {
    return SectionFallback.load(
        log,
        "patch-change-form",
        () ->
            Optional.ofNullable(patchChangeClient.form())
                .map(form -> new Form(form, form.presetFor(DEFAULT_KIND))),
        Optional.empty());
  }
}
