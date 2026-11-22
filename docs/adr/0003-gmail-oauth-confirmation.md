# Gmail API OAuth2 with send+read and confirmation gate

SMTP app-passwords are simpler for a demo but weaker for security review and lack scoped revocation. The 9AM "check important emails and prepare a summary" needs READ, not just send. We use Gmail API OAuth2 (PKCE, Settings connect) with least-privilege `gmail.send` + `gmail.readonly`, tokens in EncryptedSharedPreferences, and require explicit whole-Plan Confirmation before any send, satisfying the assignment's sensitive-action and secure-storage rules.
