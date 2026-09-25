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

package de.greluc.krt.profit.basetool.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PiiMasker} — the regex-based PII / secret masker behind every log appender
 * of all three applications. A regex bug here silently leaks PII into the centralized log files of
 * every module at once, which is exactly the failure mode the project's "Never log names, emails or
 * tokens" rule is designed to prevent. Until ADR-0205 the backend and the ingest gateway each kept
 * their own copy of this test (the frontend kept none); both are folded in here.
 *
 * <p>The masker is a pure static function: tests are framework-free.
 */
class PiiMaskerTest {

  @Nested
  class EarlyReturnTests {

    @Test
    void nullInput_returnedAsIs() {
      assertNull(PiiMasker.mask(null));
    }

    @Test
    void emptyInput_returnedAsIs() {
      assertEquals("", PiiMasker.mask(""));
    }

    @Test
    void inputWithoutPii_returnedAsIs() {
      String safe = "hello world without any sensitive content";
      assertSame(
          safe,
          PiiMasker.mask(safe),
          "PII-free input must short-circuit without allocating a new String");
    }
  }

  @Nested
  class JwtMaskingTests {

    @Test
    void singleJwt_isReplacedByPlaceholder() {
      String input = "Authorization header: eyJabcde.eyJfghij.signature1234";
      String masked = PiiMasker.mask(input);

      assertFalse(
          masked.contains("eyJabcde"), "raw JWT prefix must not survive masking: " + masked);
      assertFalse(
          masked.contains("signature1234"), "raw JWT signature must not survive: " + masked);
    }

    @Test
    void jwtAloneInString_replacedExactly() {
      String jwt = "eyJabcde.eyJfghij.signature1234";
      assertEquals("JWT_***", PiiMasker.mask(jwt));
    }

    @Test
    void realShapedJwt_replacedExactly() {
      String jwt =
          "eyJhbGciOiJIUzI1NiIsInR5cCI.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZ"
              + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
      assertEquals("relaying JWT_***", PiiMasker.mask("relaying " + jwt));
    }

    @Test
    void shortJwtSegments_doNotMatch() {
      String input = "eyJ12.eyJ34.56";
      assertEquals(
          input,
          PiiMasker.mask(input),
          "segments shorter than 5 chars must not match the JWT pattern");
    }

    @Test
    void jwtEmbeddedInSentence_replacedInPlace() {
      String masked = PiiMasker.mask("got token eyJabcde.eyJfghij.signature1234 OK");
      assertFalse(masked.contains("eyJabcde.eyJfghij.signature1234"));
    }
  }

  @Nested
  class EmailMaskingTests {

    @Test
    void simpleEmail_isReplaced() {
      assertEquals("contact: ***@***.***", PiiMasker.mask("contact: alice@example.com"));
    }

    @Test
    void emailWithPlusAndDot_isReplaced() {
      assertEquals("***@***.***", PiiMasker.mask("alice.lid+notes@example.co.uk"));
    }

    @Test
    void multipleEmails_allReplaced() {
      String masked = PiiMasker.mask("from: a@b.co to: c@d.co");
      assertEquals("from: ***@***.*** to: ***@***.***", masked);
    }

    @Test
    void atSignWithoutTld_leftAloneWithoutQuadraticBacktracking() {
      String line = "queue@" + "a".repeat(200);
      assertEquals(line, PiiMasker.mask(line));
    }

    @Test
    void singleLetterTld_doesNotMatch() {
      String input = "wrong: alice@example.x";
      assertEquals(
          input,
          PiiMasker.mask(input),
          "TLDs shorter than 2 chars must not match the email pattern");
    }
  }

  @Nested
  class KeywordTokenTests {

    @Test
    void bearerToken_secretReplaced_keywordKept() {
      String masked = PiiMasker.mask("Bearer abcDEF123_-.value");
      assertEquals("Bearer ***", masked, "the 'Bearer ' prefix is kept, the value is wiped");
    }

    @Test
    void tokenColonEquals_secretReplaced() {
      assertEquals("token=***", PiiMasker.mask("token=abcDEF.123_-"));
      assertEquals("token: ***", PiiMasker.mask("token: abcDEF.123_-"));
      assertEquals("token  ***", PiiMasker.mask("token  abcDEF.123_-"));
    }

    @Test
    void base64Token_secretReplacedInFull_keywordKept() {
      assertEquals("token=***", PiiMasker.mask("token=ab+cd/ef12=="));
      assertEquals("bearer ***", PiiMasker.mask("bearer aGVsbG8+d29ybGQ/Zm9v=="));
    }

    @Test
    void sessionIdVariants_replaced() {
      assertEquals("session-id=***", PiiMasker.mask("session-id=qWeRtY12345"));
      assertEquals("session_id: ***", PiiMasker.mask("session_id: qWeRtY12345"));
      assertEquals("sessionid=***", PiiMasker.mask("sessionid=qWeRtY12345"));
    }

    @Test
    void authorizationKeyword_replaced() {
      assertEquals("Authorization: ***", PiiMasker.mask("Authorization: abcDEF12345_-"));
    }

    @Test
    void authorizationWithBearer_keywordAndPrefixKept_valueReplaced() {
      String masked = PiiMasker.mask("Authorization: Bearer abcDEF12345");
      assertEquals(
          "Authorization: Bearer ***",
          masked,
          "both 'Authorization:' and 'Bearer ' must be preserved as the keyword");
    }

    @Test
    void caseInsensitive_keywordMatch() {
      assertEquals("BEARER ***", PiiMasker.mask("BEARER abc12345"));
      assertEquals("Bearer ***", PiiMasker.mask("Bearer abc12345"));
      assertEquals("bearer ***", PiiMasker.mask("bearer abc12345"));
      assertEquals("Token: ***", PiiMasker.mask("Token: abc12345"));
    }
  }

  @Nested
  class MixedTests {

    @Test
    void emailAndBearer_bothMasked() {
      String masked =
          PiiMasker.mask("user alice@example.com asked Bearer abcDEF12345 in the same call");
      assertFalse(masked.contains("alice@example.com"));
      assertFalse(masked.contains("abcDEF12345"));
      assertTrue(masked.contains("***@***.***"));
      assertTrue(masked.contains("Bearer ***"));
    }

    @Test
    void jwtPlusEmail_bothMasked() {
      String masked =
          PiiMasker.mask("log{jwt=eyJabcde.eyJfghij.signature1234, mail=bob@example.org}");
      assertFalse(masked.contains("eyJabcde.eyJfghij.signature1234"));
      assertFalse(masked.contains("bob@example.org"));
    }
  }

  @Nested
  class JsonSafetyTests {

    @Test
    void emailInsideJsonString_surroundingQuotesIntact() {
      String json = "{\"email\":\"alice@example.com\",\"id\":1}";
      String masked = PiiMasker.mask(json);

      assertEquals("{\"email\":\"***@***.***\",\"id\":1}", masked);
    }

    @Test
    void bearerInsideJsonString_surroundingQuotesIntact() {
      String json = "{\"authHeader\":\"Bearer abc123\"}";
      String masked = PiiMasker.mask(json);

      assertEquals("{\"authHeader\":\"Bearer ***\"}", masked);
    }

    @Test
    void backslashInInput_notInjectedByReplacement() {
      String input = "path = C:\\Users\\token: abc123";
      String masked = PiiMasker.mask(input);

      assertTrue(
          masked.contains("C:\\Users\\"),
          "preceding backslashes in the input must survive intact: " + masked);
      assertTrue(
          masked.contains("token: ***"), "expected 'token: ***' in masked output, got: " + masked);
    }
  }

  @Nested
  class FuzzyMatchingTests {

    @Test
    void bearerWithLotsOfSpaces() {
      assertEquals("Bearer ***", PiiMasker.mask("Bearer abc"));
      assertEquals("Bearer  ***", PiiMasker.mask("Bearer  abc"));
    }

    @Test
    void tokenWithoutSeparator_isNotMasked() {
      assertEquals(
          "tokenabc",
          PiiMasker.mask("tokenabc"),
          "a keyword with no separator introduces no value and must be left alone");
    }
  }

  @Nested
  class KeywordInsideIdentifierTests {

    @Test
    void stackFrameCarryingTokenInAClassName_survivesIntact() {
      String frame =
          "at org.springframework.security.oauth2.server.resource.web.authentication."
              + "BearerTokenAuthenticationFilter.doFilterInternal"
              + "(BearerTokenAuthenticationFilter.java:60)";
      assertEquals(frame, PiiMasker.mask(frame));
    }

    @Test
    void stackFrameCarryingAuthorizationInAClassName_survivesIntact() {
      String frame =
          "at org.springframework.security.web.access.intercept.AuthorizationFilter.doFilter"
              + "(AuthorizationFilter.java:101)";
      assertEquals(frame, PiiMasker.mask(frame));
    }

    @Test
    void exceptionTypeNameCarryingAuthorization_survivesIntact() {
      String message = "AuthorizationDeniedException while evaluating the @PreAuthorize gate";
      assertEquals(message, PiiMasker.mask(message));
    }

    @Test
    void keywordSuffixedIdentifierStillMasksItsValue() {
      assertEquals("guestEditToken=***", PiiMasker.mask("guestEditToken=abcDEF123"));
    }
  }
}
