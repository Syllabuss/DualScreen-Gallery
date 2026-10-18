# Reporting security concerns

Contact the maintainer privately at https://t.me/syllamisu. Describe the version,
Android/device, prerequisites and a minimal reproduction. Do not publish passwords,
signed stream URLs, personal files, signing material or an exploit against other
people's devices. Public issues can describe non-sensitive functional defects.

FTP is opt-in, password protected and intended for trusted local networks. It is
not encrypted. Uploads are capped at 8 GiB each and preserve at least 128 MiB of
internal storage. Do not forward its port to the Internet. SMB requires signing;
signing alone is not a promise of payload encryption.

Album PINs are an interface safeguard, not file encryption. Online TV uses external
sources; rights-related reports follow docs/TV_RIGHTS.md. No claim is made that
passing tests constitutes a penetration-test certification.
