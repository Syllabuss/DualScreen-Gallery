# Security and safe reporting

Use GitHub Issues for non-sensitive functional defects. Include the application version, device/Android version and a minimal reproduction. Remove passwords, signed stream URLs, personal files and other private data before sharing logs or screenshots.

Do not publish an exploit, signing material or credentials in a public issue. This project currently has no dedicated private vulnerability-reporting channel. If a report needs confidential details, ask for a private reporting route without including those details.

FTP is opt-in, password protected and intended for trusted local networks. It is
not encrypted. Uploads are capped at 8 GiB each and preserve at least 128 MiB of
internal storage. Do not forward its port to the Internet. SMB requires signing;
signing alone is not a promise of payload encryption.

Album PINs are an interface safeguard, not file encryption. Online TV uses external
sources; rights-related reports follow docs/TV_RIGHTS.md. No claim is made that
passing tests constitutes a penetration-test certification.
