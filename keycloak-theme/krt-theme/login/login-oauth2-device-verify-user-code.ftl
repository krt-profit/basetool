<#import "template.ftl" as layout>
<@layout.registrationLayout; section>
    <#if section = "header">
        ${msg("oauth2DeviceVerificationTitle")}
    <#elseif section = "form">
        <div class="login-container">
            <h1>${msg("oauth2DeviceVerificationTitle")}</h1>
            <div class="krt-warning-box" role="note" id="krt-device-phishing-warning">
                <strong>${msg("krtDeviceWarningTitle")}</strong>
                <p>${msg("krtDeviceWarningText")}</p>
            </div>
            <form id="kc-user-verify-device-user-code-form" action="${url.oauth2DeviceVerificationAction}" method="post">
                <div class="form-group">
                    <label for="device-user-code" class="krt-label">${msg("verifyOAuth2DeviceUserCode")}</label>
                    <input id="device-user-code" name="device_user_code" autocomplete="off" type="text" class="krt-input krt-monospace-text" autofocus dir="ltr" />
                </div>
                <div class="form-group login-action">
                    <input class="krt-button" type="submit" value="${msg("doSubmit")}"/>
                </div>
            </form>
        </div>
    </#if>
</@layout.registrationLayout>
