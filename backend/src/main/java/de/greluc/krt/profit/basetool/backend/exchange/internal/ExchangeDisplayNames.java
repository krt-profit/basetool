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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

/**
 * The rules for a registry client's display name, which Keycloak's consent page, the member page
 * and the connection notification show (REQ-XCH-003): Latin letters, ASCII digits, spaces and plain
 * punctuation, already in NFKC form, and never a name that poses as the Basetool.
 */
public final class ExchangeDisplayNames {

  /** The message key for a name with characters outside the allowed set. */
  public static final String INVALID = "error.exchange.client.displayNameInvalid";

  /** The message key for a name that poses as the Basetool. */
  public static final String RESERVED = "error.exchange.client.displayNameReserved";

  /**
   * A letter or digit first, then Latin letters, ASCII digits, the plain space and the punctuation
   * {@code .,:;!?'&()+/_-}; no control, format, bidi or other invisible character matches.
   */
  private static final Pattern ALLOWED =
      Pattern.compile("^[\\p{IsLatin}0-9][\\p{IsLatin}0-9 .,:;!?'&()+/_-]*$");

  /** Combining marks, dropped before the reserved-word comparison. */
  private static final Pattern MARKS = Pattern.compile("\\p{M}+");

  /** Everything but ASCII letters and digits, dropped before the reserved-word comparison. */
  private static final Pattern NOT_ALNUM = Pattern.compile("[^a-z0-9]+");

  /** Words only the Basetool itself may carry, compared without case, accents or separators. */
  private static final List<String> RESERVED_WORDS = List.of("basetool");

  /** Not instantiable. */
  private ExchangeDisplayNames() {}

  /**
   * Checks a display name.
   *
   * @param displayName the stripped name
   * @return the message key of the broken rule, or empty when the name is acceptable
   */
  public static @NotNull Optional<String> violation(@NotNull String displayName) {
    if (!Normalizer.isNormalized(displayName, Normalizer.Form.NFKC)
        || !ALLOWED.matcher(displayName).matches()) {
      return Optional.of(INVALID);
    }
    String folded =
        NOT_ALNUM
            .matcher(
                MARKS
                    .matcher(Normalizer.normalize(displayName, Normalizer.Form.NFD))
                    .replaceAll("")
                    .toLowerCase(Locale.ROOT))
            .replaceAll("");
    return RESERVED_WORDS.stream().anyMatch(folded::contains)
        ? Optional.of(RESERVED)
        : Optional.empty();
  }
}
