<#import "template.ftl" as layout>
<@layout.registrationLayout displayInfo=true displayMessage=!messagesPerField.existsError('username'); section>
    <#if section = "header">
        ${msg("emailForgotTitle")}
    <#elseif section = "form">
        <div class="login-container">
            <h1>${msg("emailForgotTitle")}</h1>
            <div class="krt-info-text-center">
                ${msg("krtResetPasswordInstruction")}
            </div>
            <form id="kc-reset-password-form" action="${url.loginAction}" method="post">
                <div class="form-group">
                    <label for="username" class="krt-label"><#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("krtResetPasswordLabel")}<#else>${msg("email")}</#if></label>
                    <input tabindex="1" type="text" id="username" name="username" class="krt-input" autofocus value="${(auth.attemptedUsername!'')}" aria-describedby="krt-reset-password-hint" aria-invalid="<#if messagesPerField.existsError('username')>true</#if>"/>
                    <p id="krt-reset-password-hint" class="krt-info-text">${msg("krtResetPasswordHint")}</p>
                    <#if messagesPerField.existsError('username')>
                        <span id="input-error-username" class="kc-error-message krt-error-message" aria-live="polite">
                            ${kcSanitize(messagesPerField.get('username'))?no_esc}
                        </span>
                    </#if>
                </div>

                <div class="form-group login-action">
                    <input tabindex="2" class="krt-button" type="submit" value="${msg("doSubmit")}"/>
                    <div class="krt-form-footer">
                        <a tabindex="3" href="${url.loginUrl}" class="krt-link">${kcSanitize(msg("backToLogin"))?no_esc}</a>
                    </div>
                </div>
            </form>
        </div>
    </#if>
</@layout.registrationLayout>