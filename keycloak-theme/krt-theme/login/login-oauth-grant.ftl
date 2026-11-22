<#import "template.ftl" as layout>
<#import "krt-legal.ftl" as legal>
<@layout.registrationLayout bodyClass="oauth"; section>
    <#if section = "header">
        ${msg("krtOauthGrantTitle", (client.name?has_content)?then(advancedMsg(client.name), client.clientId))}
    <#elseif section = "form">
        <div class="login-container" id="kc-oauth">
            <h1>${msg("krtOauthGrantTitle", (client.name?has_content)?then(advancedMsg(client.name), client.clientId))}</h1>
            <#if krtDeviceUserCode??>
                <div class="krt-warning-box" role="note" id="krt-device-consent-warning">
                    <strong>${msg("krtDeviceConsentWarningTitle")}</strong>
                    <p>${msg("krtDeviceConsentWarningText")}</p>
                    <p class="krt-device-code-line">${msg("krtDeviceConsentCodeLabel")} <span class="krt-monospace-text krt-device-code" id="krt-device-user-code" dir="ltr">${krtDeviceUserCode}</span></p>
                </div>
            </#if>
            <p class="krt-info-text">${msg("krtOauthGrantIntro")}</p>
            <ul class="krt-ul krt-consent-list">
                <#if oauth.clientScopesRequested??>
                    <#list oauth.clientScopesRequested as clientScope>
                        <li>${advancedMsg(clientScope.consentScreenText)}<#if clientScope.parameterizedScopeParameter??>: <b>${clientScope.parameterizedScopeParameter}</b></#if></li>
                    </#list>
                </#if>
            </ul>
            <p class="krt-info-text">${msg("krtOauthGrantRevokeHint")}</p>
            <form class="form-actions" action="${url.oauthAction}" method="POST">
                <input type="hidden" name="code" value="${oauth.code}">
                <div class="form-group login-action">
                    <input class="krt-button" name="accept" id="kc-login" type="submit" value="${msg("krtOauthGrantAccept")}"/>
                    <input class="krt-button-secondary" name="cancel" id="kc-cancel" type="submit" value="${msg("krtOauthGrantDeny")}"/>
                </div>
            </form>
            <@legal.links/>
        </div>
    </#if>
</@layout.registrationLayout>
