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

package de.greluc.krt.profit.basetool.frontend.support;

import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Renders a handle snapshot for a human, mapping the Art. 17 erasure sentinel onto a localized
 * label (REQ-SEC-062).
 *
 * <p>Registered as the Thymeleaf-visible bean {@code handles}, so a template writes {@code
 * ${@handles.display(row.actorHandle)}} — the same shape {@code @markdown.render(...)} already
 * uses. One seam rather than a ternary at each of the ~30 places a handle is printed: a site that
 * forgot the ternary would print the raw token, and the sites are spread over the audit viewer,
 * four bank screens and the job-order handover receipts.
 *
 * <p><b>Why the stored value is a token and not a word.</b> It is written once and read in two
 * languages, and the project's i18n rule admits no hardcoded user-visible string. So the column
 * carries {@code #ANONYMISED#} and this class resolves {@code general.anonymisedHandle} from the
 * message bundles.
 *
 * <p><b>This class is a mirror of {@code HandleAnonymisation} in the backend module</b>, which
 * holds the same constant. The duplication has the same cause as {@code CLIENT_IDS} mirroring
 * {@code ClientAttribution}: this module holds no backend beans. The two are a <b>mirror pair</b> —
 * changing the token means changing both.
 */
@Component("handles")
@RequiredArgsConstructor
public class HandleDisplay {

  /**
   * The stored replacement for an erased handle snapshot. Mirrors {@code
   * HandleAnonymisation#SENTINEL} in the backend module.
   */
  public static final String SENTINEL = "#ANONYMISED#";

  /** Message key for the label an erased handle renders as. */
  private static final String ANONYMISED_KEY = "general.anonymisedHandle";

  private final MessageSource messageSource;

  /**
   * The handle as it should be shown to a person.
   *
   * @param handle the stored handle snapshot, possibly {@code null}
   * @return the localized placeholder when the value is the erasure sentinel, otherwise the handle
   *     unchanged (including {@code null}, which every call site already renders as a dash)
   */
  public @Nullable String display(@Nullable String handle) {
    if (!SENTINEL.equals(handle)) {
      return handle;
    }
    Locale locale = LocaleContextHolder.getLocale();
    return messageSource.getMessage(ANONYMISED_KEY, null, SENTINEL, locale);
  }

  /**
   * Whether a handle snapshot has been erased on request.
   *
   * <p>Exposed for the few templates that need to style the placeholder differently rather than
   * merely print it.
   *
   * @param handle the stored handle snapshot, possibly {@code null}
   * @return {@code true} when the value is the erasure sentinel
   */
  public boolean isAnonymised(@Nullable String handle) {
    return SENTINEL.equals(handle);
  }
}
