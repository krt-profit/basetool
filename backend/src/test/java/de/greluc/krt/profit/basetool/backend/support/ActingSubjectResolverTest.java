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

package de.greluc.krt.profit.basetool.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Behaviour of the ingest gateway's on-behalf-of trust boundary (ADR-0129).
 *
 * <p>{@link #refusesTheHeaderFromACallerThatIsNotAGateway} and {@link
 * #honoursNobodyWhenNoGatewayIsConfigured} are the ones that matter. This header is the only way a
 * caller can act as somebody else in the whole application, so the interesting cases are not the
 * happy path — they are every route by which the wrong party might reach it.
 */
class ActingSubjectResolverTest {

  private static final String GATEWAY_CLIENT = "basetool-ingest-gateway";
  private static final String CALLER_SUB = "11111111-1111-1111-1111-111111111111";
  private static final String VICTIM_SUB = "22222222-2222-2222-2222-222222222222";

  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  private ActingSubjectResolver resolver(String... gatewayClientIds) {
    IngestGatewayProperties properties = new IngestGatewayProperties();
    properties.setClientIds(List.of(gatewayClientIds));
    return new ActingSubjectResolver(properties, meterRegistry);
  }

  private Jwt token(String sub, String azp) {
    Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject(sub);
    if (azp != null) {
      builder = builder.claim("azp", azp);
    }
    return builder.build();
  }

  /** With no header the answer is the caller's own subject — the ordinary path is untouched. */
  @Test
  void resolvesTheCallersOwnSubjectWhenNoHeaderIsPresent() {
    ActingSubjectResolver resolver = resolver(GATEWAY_CLIENT);

    String acting =
        resolver.resolve(token(CALLER_SUB, "basetool-frontend"), new MockHttpServletRequest());

    assertThat(acting).isEqualTo(CALLER_SUB);
  }

  /** The gateway may name the member it acts for. */
  @Test
  void honoursTheHeaderForAnApprovedGateway() {
    ActingSubjectResolver resolver = resolver(GATEWAY_CLIENT);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(ActingSubjectResolver.ON_BEHALF_OF_HEADER, VICTIM_SUB);

    String acting = resolver.resolve(token(CALLER_SUB, GATEWAY_CLIENT), request);

    assertThat(acting).isEqualTo(VICTIM_SUB);
  }

  /**
   * Anyone else presenting the header is refused, not silently ignored.
   *
   * <p>Ignoring would be the tempting lenient choice and it is the wrong one twice over: the caller
   * would write under their own identity while believing they wrote under someone else's, and an
   * attempt to impersonate would leave no trace at all. This is the single most important assertion
   * in the file — without it the header is an impersonation primitive for any authenticated user.
   */
  @Test
  void refusesTheHeaderFromACallerThatIsNotAGateway() {
    ActingSubjectResolver resolver = resolver(GATEWAY_CLIENT);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(ActingSubjectResolver.ON_BEHALF_OF_HEADER, VICTIM_SUB);

    assertThatThrownBy(() -> resolver.resolve(token(CALLER_SUB, "basetool-frontend"), request))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(refusals("not_a_gateway")).isEqualTo(1.0);
  }

  /** A token with no {@code azp} at all cannot pass the allowlist either. */
  @Test
  void refusesTheHeaderWhenTheTokenCarriesNoAzp() {
    ActingSubjectResolver resolver = resolver(GATEWAY_CLIENT);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(ActingSubjectResolver.ON_BEHALF_OF_HEADER, VICTIM_SUB);

    assertThatThrownBy(() -> resolver.resolve(token(CALLER_SUB, null), request))
        .isInstanceOf(AccessDeniedException.class);
  }

  /**
   * An unconfigured deployment trusts nobody.
   *
   * <p>The dangerous default would be to treat "no allowlist" as "no restriction". A backend that
   * has not been told which client is the gateway must refuse every on-behalf-of header, so
   * shipping the code before the realm is configured cannot open the boundary.
   */
  @Test
  void honoursNobodyWhenNoGatewayIsConfigured() {
    ActingSubjectResolver resolver = resolver();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(ActingSubjectResolver.ON_BEHALF_OF_HEADER, VICTIM_SUB);

    assertThatThrownBy(() -> resolver.resolve(token(CALLER_SUB, GATEWAY_CLIENT), request))
        .isInstanceOf(AccessDeniedException.class);
  }

  /** A named subject that is not a UUID is refused rather than passed to the persistence layer. */
  @Test
  void refusesAMalformedSubject() {
    ActingSubjectResolver resolver = resolver(GATEWAY_CLIENT);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(ActingSubjectResolver.ON_BEHALF_OF_HEADER, "not-a-uuid");

    assertThatThrownBy(() -> resolver.resolve(token(CALLER_SUB, GATEWAY_CLIENT), request))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(refusals("malformed_subject")).isEqualTo(1.0);
  }

  /** A blank header value is treated as absent, not as an attempt. */
  @Test
  void treatsABlankHeaderAsAbsent() {
    ActingSubjectResolver resolver = resolver(GATEWAY_CLIENT);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(ActingSubjectResolver.ON_BEHALF_OF_HEADER, "   ");

    assertThat(resolver.resolve(token(CALLER_SUB, GATEWAY_CLIENT), request)).isEqualTo(CALLER_SUB);
    assertThat(meterRegistry.find("basetool.on.behalf.of.refused").counters()).isEmpty();
  }

  /**
   * Reads the refusal counter for one reason.
   *
   * @param reason the bounded reason tag
   * @return the current count, or 0 when the series does not exist
   */
  private double refusals(String reason) {
    var counter =
        meterRegistry.find("basetool.on.behalf.of.refused").tag("reason", reason).counter();
    return counter == null ? 0.0 : counter.count();
  }

  /** The header literal must match the ingest module's, or every write is attributed to nobody. */
  @Test
  void headerNameMatchesTheOneTheGatewaySends() {
    assertThat(ActingSubjectResolver.ON_BEHALF_OF_HEADER).isEqualTo("X-Ingest-On-Behalf-Of");
    assertThat(UUID.fromString(VICTIM_SUB)).isNotNull();
  }
}
