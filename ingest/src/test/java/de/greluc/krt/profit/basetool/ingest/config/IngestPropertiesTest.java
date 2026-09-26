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

package de.greluc.krt.profit.basetool.ingest.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.ingest.support.TestProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the defaults of the gateway's four configuration records, bound through the real
 * {@code Binder}, including the handoff TTL (REQ-INGEST-003) and the property names.
 */
class IngestPropertiesTest {

  /**
   * The handoff TTL defaults to 30 minutes, leaving time between the extractor's Send and opening
   * the pre-filled page (REQ-INGEST-003).
   */
  @Test
  void handoffTtlDefaultsToThirtyMinutes() {
    IngestProperties properties = TestProperties.ingest();

    assertThat(properties.handoffTtl()).isEqualTo(Duration.ofMinutes(30));
  }

  /** Every other {@code app.ingest} default survives the move from a mutable bean to a record. */
  @Test
  void ingestDefaultsMatchTheDocumentedValues() {
    IngestProperties properties = TestProperties.ingest();

    assertThat(properties.publicBaseUrl()).isEmpty();
    assertThat(properties.refineryPath()).isEqualTo("/refinery-orders/create");
    assertThat(properties.blueprintPath()).isEqualTo("/personal-inventory/blueprints");
    assertThat(properties.maxPayloadBytes()).isEqualTo(2L * 1024 * 1024);
    assertThat(properties.maxHandoffBytes()).isEqualTo(256L * 1024);
    assertThat(properties.maxHandoffsPerSubject()).isEqualTo(10);
  }

  /**
   * The per-IP budget defaults to four times the per-subject one, so a household or office sharing
   * one public address is not throttled at the budget of a single member (REQ-INGEST-005).
   */
  @Test
  void rateLimitDefaultsGiveTheIpBucketItsOwnLooserBudget() {
    RateLimitProperties properties = TestProperties.rateLimit();

    assertThat(properties.enabled()).isTrue();
    assertThat(properties.capacity()).isEqualTo(30);
    assertThat(properties.refillTokens()).isEqualTo(30);
    assertThat(properties.refillPeriod()).isEqualTo(Duration.ofMinutes(1));
    assertThat(properties.ipCapacity()).isEqualTo(120);
    assertThat(properties.ipRefillTokens()).isEqualTo(120);
  }

  /** The IP budget is overridable on its own, under the documented property name. */
  @Test
  void ipBudgetBindsFromItsOwnPropertyNames() {
    RateLimitProperties properties =
        TestProperties.rateLimit("ip-capacity", "300", "ip-refill-tokens", "240");

    assertThat(properties.ipCapacity()).isEqualTo(300);
    assertThat(properties.ipRefillTokens()).isEqualTo(240);
    assertThat(properties.capacity()).as("the subject budget is untouched").isEqualTo(30);
  }

  /** Every client-identity gate is inert by default; nothing ships pre-enabled (REQ-INGEST-011). */
  @Test
  void clientIdentityDefaultsAreInert() {
    ClientIdentityProperties properties = TestProperties.clientIdentity();

    assertThat(properties.allowedClientIds()).isEmpty();
    assertThat(properties.requiredScope()).isEmpty();
    assertThat(properties.allowedTools()).isEmpty();
    assertThat(properties.auditOnly()).isFalse();
  }

  /** A comma-separated environment value binds to the allowlist exactly as it did before. */
  @Test
  void clientIdentityListsBindFromACommaSeparatedValue() {
    ClientIdentityProperties properties =
        TestProperties.clientIdentity(
            "allowed-client-ids", "basetool-sc-extractor,other-client", "audit-only", "true");

    assertThat(properties.allowedClientIds())
        .containsExactly("basetool-sc-extractor", "other-client");
    assertThat(properties.auditOnly()).isTrue();
  }

  /** The service account is off by default, and its secret never appears in the string form. */
  @Test
  void serviceAccountDefaultsAreEmptyAndTheSecretIsNeverRendered() {
    assertThat(TestProperties.serviceAccount().tokenUri()).isEmpty();
    assertThat(TestProperties.serviceAccount().refreshSkew()).isEqualTo(Duration.ofSeconds(30));
    assertThat(TestProperties.serviceAccount().timeoutMillis()).isEqualTo(5_000L);

    ServiceAccountProperties configured =
        TestProperties.serviceAccount(
            "token-uri", "https://kc/token", "client-id", "gw", "client-secret", "s3cret-value");

    assertThat(configured.toString()).doesNotContain("s3cret-value").contains("<set>");
  }
}
