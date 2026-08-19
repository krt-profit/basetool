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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.model.dto.TermsDocumentDto;
import de.greluc.krt.profit.basetool.backend.service.TermsDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Terms-of-Use wording itself (REQ-SEC-028, ADR-0138).
 *
 * <p><strong>Separate from {@link TermsController} because the access rule is the opposite
 * one.</strong> Consent is recorded against an account, so status and acceptance require
 * authentication. The document does not: a text everybody must be able to read <em>before</em>
 * agreeing to anything cannot require having agreed, and the same wording is already served to the
 * world at {@code /terms} on the web frontend. Keeping the anonymous endpoint in its own class
 * makes that rule visible at the top of the file rather than hidden as a method-level override
 * inside a controller annotated {@code isAuthenticated()}.
 *
 * <p>This is the single source both clients render — the web frontend's {@code /terms} page and its
 * consent gate, and the Android app's terms screen. Before it existed the wording lived in the
 * frontend's message bundle and the app had no way to reach it at all; the only alternatives were
 * shipping a copy inside the APK, which drifts from the version being accepted, or sending members
 * out to a browser mid-consent.
 */
@RestController
@RequestMapping("/api/v1/terms/document")
@RequiredArgsConstructor
@Tag(name = "Terms of Use", description = "Consent to the Terms of Use")
public class TermsDocumentController {

  private final TermsDocumentService termsDocumentService;

  /**
   * Returns the wording in force, in the caller's language.
   *
   * <p>The language comes from {@code Accept-Language} via Spring's resolved locale, so a client
   * gets the bundle it asked for and the German default when it asked for something the bundle does
   * not carry. The response includes the version digest, which lets a client show the document and
   * accept it in one exchange without risking that the two refer to different wordings.
   *
   * @param locale the caller's resolved language
   * @return the structured document, always {@code 200}
   */
  @GetMapping
  @PreAuthorize("permitAll()")
  @Operation(
      summary = "The Terms-of-Use wording in force",
      description =
          "Returns the document as structured sections, together with the version digest an "
              + "acceptance would be recorded against. Anonymous: the text must be readable "
              + "before anyone can agree to it.")
  @ApiResponse(responseCode = "200", description = "The wording in force")
  public ResponseEntity<TermsDocumentDto> document(@NotNull Locale locale) {
    return ResponseEntity.ok(termsDocumentService.document(locale));
  }
}
