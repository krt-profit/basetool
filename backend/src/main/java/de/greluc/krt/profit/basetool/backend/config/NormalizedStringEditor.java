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

package de.greluc.krt.profit.basetool.backend.config;

import de.greluc.krt.profit.basetool.backend.support.StringNormalization;
import java.beans.PropertyEditorSupport;
import lombok.RequiredArgsConstructor;

/**
 * Spring {@link PropertyEditorSupport} that performs the same trim + NFC-normalize + length-cap as
 * {@link NormalizedStringDeserializer}, but for form-bound (non-JSON) string fields.
 *
 * <p>Registered globally via {@link GlobalBindingAdvice} so a controller never has to repeat the
 * normalization, and so a form post and a JSON post of the same value always reach the database in
 * the same canonical form. The {@code emptyAsNull} flag controls whether a blank input becomes
 * {@code null} (the default for write-DTOs) or stays the empty string (rare; only useful when a
 * client genuinely needs to distinguish "not set" from "explicitly cleared").
 */
@RequiredArgsConstructor
public class NormalizedStringEditor extends PropertyEditorSupport {

  private final int maxLength;
  private final boolean emptyAsNull;

  @Override
  public void setAsText(String text) throws IllegalArgumentException {
    setValue(StringNormalization.normalize(text, maxLength, emptyAsNull));
  }
}
