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

package de.greluc.krt.profit.basetool.frontend.kernel.layout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Adds {@code draftScope} to every layout model: the opaque per-member key that scopes the form
 * drafts a tab keeps in its session storage, so another member signing in in the same tab never
 * sees them (REQ-FE-041, ADR-0411). The key is derived from the identity provider's stable subject,
 * never from a name or an e-mail address, and stays the same across sessions, so a draft survives a
 * re-authentication.
 */
@ControllerAdvice(annotations = UsesLayoutModel.class)
public class DraftScopeAdvice {

  /** Length of the scope key in hex characters: 32 characters, 128 bits of the digest. */
  static final int SCOPE_HEX_CHARS = 32;

  /** The prefix hashed before the subject, so the key matches no other digest of the subject. */
  private static final String DOMAIN_PREFIX = "basetool-form-draft:";

  /**
   * Resolves the draft scope of the signed-in member.
   *
   * @param principal the signed-in member, or {@code null} for an anonymous request
   * @return the first {@value #SCOPE_HEX_CHARS} lowercase hex characters of the SHA-256 of the
   *     prefixed subject, or {@code null} without a member or a subject
   */
  @Nullable
  @ModelAttribute("draftScope")
  public String draftScope(@AuthenticationPrincipal @Nullable OidcUser principal) {
    if (principal == null) {
      return null;
    }
    return scopeOf(principal.getSubject());
  }

  /**
   * Derives the scope key of a subject.
   *
   * @param subject the identity provider's subject, or {@code null}
   * @return the scope key, or {@code null} for a missing or blank subject
   * @throws IllegalStateException when the platform offers no SHA-256
   */
  @Contract(pure = true)
  static @Nullable String scopeOf(@Nullable String subject) {
    if (subject == null || subject.isBlank()) {
      return null;
    }
    return HexFormat.of().formatHex(sha256(DOMAIN_PREFIX + subject)).substring(0, SCOPE_HEX_CHARS);
  }

  /**
   * Hashes a text with SHA-256.
   *
   * @param text the text to hash
   * @return the digest
   * @throws IllegalStateException when the platform offers no SHA-256
   */
  private static byte @NotNull [] sha256(@NotNull String text) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every Java platform", e);
    }
  }
}
