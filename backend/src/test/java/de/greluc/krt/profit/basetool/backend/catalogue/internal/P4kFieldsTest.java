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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The fill and lead rules the P4K import writes fields with (REQ-P4K-017). */
class P4kFieldsTest {

  @Test
  void aBlankStoredTextIsFilledLikeAMissingOne() {
    AtomicReference<String> written = new AtomicReference<>();

    assertThat(P4kFields.fillIfNull("  ", "Catalogue text", written::set, true)).isTrue();
    assertThat(written.get()).isEqualTo("Catalogue text");
  }

  @Test
  void aStoredValueIsNotFilled_andABlankCatalogueValueFillsNothing() {
    AtomicReference<String> written = new AtomicReference<>();

    assertThat(P4kFields.fillIfNull("Stored", "Catalogue", written::set, true)).isFalse();
    assertThat(P4kFields.fillIfNull(null, " ", written::set, true)).isFalse();
    assertThat(written.get()).isNull();
  }

  @Test
  void aPreviewReportsTheFillAndWritesNothing() {
    AtomicReference<String> written = new AtomicReference<>();

    assertThat(P4kFields.fillIfNull(null, "Catalogue", written::set, false)).isTrue();
    assertThat(written.get()).isNull();
  }
}
