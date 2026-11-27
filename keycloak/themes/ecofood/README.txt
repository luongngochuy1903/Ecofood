# EcoFood email theme

Presentation only. Onboarding still calls Keycloak `sendVerifyEmail()` with the existing realm, user, SMTP configuration, verification link and expiry. No Java onboarding or authentication flow is changed.

Copy this `ecofood` directory into the Keycloak server's `themes` directory, then select **Realm Settings → Themes → Email Theme → ecofood** for the intended realm. Install the theme before selecting it. Do not replace the Login Theme. These repository changes do not deploy files to Keycloak or update existing realm configuration.

The HTML verification email uses Keycloak's existing `user`, `realmName`, `link`, `linkExpiration` and `linkExpirationFormatter` values. It preserves the signed verification link exactly. Email subject, plain-text alternative and other email types inherit from Keycloak. Text branding remains readable when images are blocked. No MongoDB tenant information is queried from Keycloak.

Existing realms and shared master/sub-tenant realms keep their current verification behavior. Select the Email Theme on the realm that actually sends the email; sub-tenants do not acquire separate realms.

Before activation, render/test with the deployed Keycloak version in a controlled realm and mocked/test SMTP. The repository uses the Keycloak 25.0.4 Admin client; this does not establish the deployed server version.

Reference: https://www.keycloak.org/ui-customization/themes
