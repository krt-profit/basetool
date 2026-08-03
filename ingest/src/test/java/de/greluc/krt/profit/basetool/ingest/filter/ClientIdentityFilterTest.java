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

package de.greluc.krt.profit.basetool.ingest.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import de.greluc.krt.profit.basetool.ingest.config.ClientIdentityProperties;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.support.LogCapture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the {@link ClientIdentityFilter} client-identity gate (REQ-INGEST-011): the {@code
 * azp} allowlist, the required ingest scope and the DPoP requirement, each inert until configured
 * and each fail-closed once it is.
 *
 * <p>The tests pin three properties that are easy to regress and expensive to discover in
 * production: a rejection never reaches the chain, a <em>missing</em> claim is refused just like an
 * unknown one, and the {@code client_id} metric label never carries a raw token claim.
 */
class ClientIdentityFilterTest {

  private static final String ALLOWED_CLIENT = "basetool-sc-extractor";
  private static final String INGEST_SCOPE = "extractor-ingest";

  private SimpleMeterRegistry registry;
  private FilterChain chain;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    chain = mock(FilterChain.class);
    request = new MockHttpServletRequest("POST", "/v1/refinery-extract");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token-value");
    response = new MockHttpServletResponse();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  /**
   * Builds a filter over the given configuration.
   *
   * @param properties the client-identity configuration under test
   * @return the filter wired to the test meter registry
   */
  private ClientIdentityFilter filter(ClientIdentityProperties properties) {
    return new ClientIdentityFilter(properties, registry, JsonMapper.builder().build());
  }

  /**
   * Configuration with the allowlist and scope enabled and enforcement on.
   *
   * @return a fully-configured, enforcing client-identity configuration
   */
  private static ClientIdentityProperties enforcing() {
    ClientIdentityProperties properties = new ClientIdentityProperties();
    properties.setAllowedClientIds(List.of(ALLOWED_CLIENT));
    properties.setRequiredScope(INGEST_SCOPE);
    return properties;
  }

  /**
   * Puts an authenticated JWT into the security context.
   *
   * @param authorizedParty the {@code azp} claim, or {@code null} to omit it
   * @param scope the granted {@code SCOPE_} authority suffix, or {@code null} for no authorities
   */
  private static void authenticate(String authorizedParty, String scope) {
    Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").claim("sub", "sub-1");
    if (authorizedParty != null) {
      builder.claim("azp", authorizedParty);
    }
    Jwt jwt = builder.build();
    var authorities =
        scope == null
            ? List.<SimpleGrantedAuthority>of()
            : List.of(new SimpleGrantedAuthority("SCOPE_" + scope));
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, authorities));
  }

  /**
   * Reads a bounded reject counter.
   *
   * @param reason the bounded reason tag value
   * @return the counter value, or {@code 0.0} when the series does not exist
   */
  private double rejected(String reason) {
    var counter =
        registry
            .find(MetricNames.INGEST_CLIENT_REJECTED)
            .tag(MetricNames.TAG_REASON, reason)
            .counter();
    return counter == null ? 0.0 : counter.count();
  }

  /**
   * Reads the accepted-client counter for one bounded label.
   *
   * @param clientId the bounded {@code client_id} tag value
   * @return the counter value, or {@code 0.0} when the series does not exist
   */
  private double accepted(String clientId) {
    var counter =
        registry.find(MetricNames.INGEST_CLIENT).tag(MetricNames.TAG_CLIENT_ID, clientId).counter();
    return counter == null ? 0.0 : counter.count();
  }

  @Test
  void shouldStayInertWhenNothingIsConfigured() throws Exception {
    // A build that ships the gate must be a no-op until the operator has run the Keycloak setup —
    // otherwise deploying it rejects every real extractor token (REQ-INGEST-008 sequencing).
    authenticate("some-unregistered-client", null);

    filter(new ClientIdentityProperties()).doFilter(request, response, chain);

    verify(chain, times(1)).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(rejected(MetricNames.REASON_UNKNOWN_CLIENT)).isZero();
  }

  @Test
  void shouldPassAnAllowlistedClientCarryingTheScope() throws Exception {
    authenticate(ALLOWED_CLIENT, INGEST_SCOPE);

    filter(enforcing()).doFilter(request, response, chain);

    verify(chain, times(1)).doFilter(request, response);
    assertThat(accepted(ALLOWED_CLIENT)).isEqualTo(1.0d);
  }

  @Test
  void shouldRejectAClientThatIsNotOnTheAllowlist() throws Exception {
    authenticate("some-other-tool", INGEST_SCOPE);

    filter(enforcing()).doFilter(request, response, chain);

    // The chain must never run: a rejected client may not reach the relay.
    verify(chain, never()).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains(MetricNames.CODE_CLIENT_NOT_ALLOWED);
    assertThat(rejected(MetricNames.REASON_UNKNOWN_CLIENT)).isEqualTo(1.0d);
  }

  @Test
  void shouldFailClosedWhenTheAzpClaimIsAbsentEntirely() throws Exception {
    // A MISSING claim must not be the lenient branch. If it were, a realm change that stopped
    // stamping azp would silently disable the gate instead of failing loudly.
    authenticate(null, INGEST_SCOPE);

    filter(enforcing()).doFilter(request, response, chain);

    verify(chain, never()).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(rejected(MetricNames.REASON_MISSING_AZP)).isEqualTo(1.0d);
    // Distinct from unknown_client: this one points at Keycloak, not at a foreign caller.
    assertThat(rejected(MetricNames.REASON_UNKNOWN_CLIENT)).isZero();
  }

  @Test
  void shouldRejectAnAllowlistedClientWithoutTheIngestScope() throws Exception {
    authenticate(ALLOWED_CLIENT, null);

    filter(enforcing()).doFilter(request, response, chain);

    verify(chain, never()).doFilter(request, response);
    assertThat(rejected(MetricNames.REASON_MISSING_SCOPE)).isEqualTo(1.0d);
  }

  @Test
  void shouldNeverUseTheRawAzpAsAMetricLabel() throws Exception {
    // Deriving a label from a token claim is the shape of an unbounded-cardinality bug
    // (REQ-OBS-011). An unknown client must collapse to the bounded `other` literal.
    authenticate("wildcard-client-name", INGEST_SCOPE);

    filter(enforcing()).doFilter(request, response, chain);

    assertThat(
            registry
                .find(MetricNames.INGEST_CLIENT)
                .tag(MetricNames.TAG_CLIENT_ID, "wildcard-client-name")
                .counter())
        .isNull();
  }

  @Test
  void shouldServeTheRequestButStillCountAndLogWhileAuditOnly() throws Exception {
    // The safe rollout path: measure the real client population before enforcing.
    ClientIdentityProperties properties = enforcing();
    properties.setAuditOnly(true);
    authenticate("some-other-tool", INGEST_SCOPE);

    List<ILoggingEvent> events =
        LogCapture.capture(
            ClientIdentityFilter.class,
            Level.WARN,
            () -> {
              try {
                filter(properties).doFilter(request, response, chain);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });

    verify(chain, times(1)).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(200);
    // Counted even though it was served — that counter is what the operator watches.
    assertThat(rejected(MetricNames.REASON_UNKNOWN_CLIENT)).isEqualTo(1.0d);
    assertThat(events).isNotEmpty();
    assertThat(events.getFirst().getFormattedMessage()).contains("audit-only");
    assertThat(accepted(MetricNames.CLIENT_ID_OTHER)).isEqualTo(1.0d);
  }

  @Test
  void shouldRejectAPlainBearerWhenDpopIsRequired() throws Exception {
    ClientIdentityProperties properties = new ClientIdentityProperties();
    properties.setDpopRequired(true);
    authenticate(ALLOWED_CLIENT, null);

    filter(properties).doFilter(request, response, chain);

    verify(chain, never()).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(rejected(MetricNames.REASON_DPOP_REQUIRED)).isEqualTo(1.0d);
  }

  @Test
  void shouldAcceptADpopSchemeRequestWhenDpopIsRequired() throws Exception {
    ClientIdentityProperties properties = new ClientIdentityProperties();
    properties.setDpopRequired(true);
    request = new MockHttpServletRequest("POST", "/v1/refinery-extract");
    // RFC 9110 makes the auth-scheme case-insensitive; a hand-written client may well send "dpop".
    request.addHeader(HttpHeaders.AUTHORIZATION, "dpop token-value");
    authenticate(ALLOWED_CLIENT, null);

    filter(properties).doFilter(request, response, chain);

    verify(chain, times(1)).doFilter(request, response);
    assertThat(rejected(MetricNames.REASON_DPOP_REQUIRED)).isZero();
  }

  @Test
  void shouldWarnWhenASenderConstrainedTokenIsPresentedAsAPlainBearer() throws Exception {
    // The DPoP downgrade: the token is bound to a key (cnf.jkt) but arrived without a proof. During
    // the dual-mode window this is never benign — a client holding a bound token has the key.
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .claim("sub", "sub-1")
            .claim("azp", ALLOWED_CLIENT)
            .claim("cnf", java.util.Map.of("jkt", "thumbprint-abc"))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

    List<ILoggingEvent> events =
        LogCapture.capture(
            ClientIdentityFilter.class,
            Level.WARN,
            () -> {
              try {
                filter(new ClientIdentityProperties()).doFilter(request, response, chain);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });

    verify(chain, times(1)).doFilter(request, response);
    assertThat(events).isNotEmpty();
    assertThat(events.getFirst().getFormattedMessage()).contains("presented as a plain bearer");
  }

  @Test
  void shouldLetAnUnauthenticatedRequestThroughSoTheChainCanAnswer401() throws Exception {
    // Turning a missing token into a 403 would tell a client to stop retrying when
    // re-authenticating
    // is exactly what it should do.
    filter(enforcing()).doFilter(request, response, chain);

    verify(chain, times(1)).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void shouldNotFilterOutsideTheIngestEndpoints() throws Exception {
    // Gating the actuator would break the container healthcheck.
    MockHttpServletRequest health = new MockHttpServletRequest("GET", "/actuator/health");

    assertThat(filter(enforcing()).shouldNotFilter(health)).isTrue();
    assertThat(filter(enforcing()).shouldNotFilter(request)).isFalse();
  }
}
