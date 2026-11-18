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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import de.greluc.krt.profit.basetool.backend.kernel.DomainProblem;
import java.io.Serial;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a book-in would put a material that is not {@linkplain Material#isSelectable()
 * selectable} on a stock entry: one that is invisible, that the P4K retired or that is hidden as a
 * commodity no game source knows (REQ-P4K-013, REQ-P4K-075).
 *
 * <p>Answers {@code 400} with code {@code CATALOGUE_MATERIAL_RETIRED} and the extension property
 * {@code materialId}; nothing is written.
 */
@Getter
public final class RetiredMaterialException extends DomainProblem {

  @Serial private static final long serialVersionUID = 1L;

  /** The bundle key the localised {@code detail} resolves from. */
  private static final String DETAIL_KEY = "problem.catalogue_material_retired.detail";

  /** The unselectable material the book-in named. */
  @NotNull private final UUID materialId;

  /**
   * Creates the exception; its {@code detail} is the localised text of {@link #DETAIL_KEY}.
   *
   * @param materialId the unselectable material the book-in named
   */
  public RetiredMaterialException(@NotNull UUID materialId) {
    super(DETAIL_KEY);
    this.materialId = materialId;
  }

  /**
   * The status the refusal is answered with.
   *
   * @return {@code 400}
   */
  @NotNull
  @Override
  public HttpStatus status() {
    return CatalogueProblemCode.CATALOGUE_MATERIAL_RETIRED.status();
  }

  /**
   * The stable code of the refusal.
   *
   * @return {@code CATALOGUE_MATERIAL_RETIRED}
   */
  @NotNull
  @Override
  public String code() {
    return CatalogueProblemCode.CATALOGUE_MATERIAL_RETIRED.code();
  }

  /**
   * The label the handler's log line names the refusal by.
   *
   * @return {@code "Retired catalogue material"}
   */
  @NotNull
  @Override
  public String logLabel() {
    return "Retired catalogue material";
  }

  /**
   * Adds the unselectable material's id to the problem body.
   *
   * @return {@code materialId}
   */
  @NotNull
  @Unmodifiable
  @Override
  public Map<String, Object> extraProperties() {
    return Map.of("materialId", materialId.toString());
  }
}
