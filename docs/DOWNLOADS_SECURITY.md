# Download request security

Phormi's browser download service uses request headers from the download URL itself.
Cookies are queried for that destination URL only; cookies from the referring page are
never used as a fallback. This avoids forwarding a signed-in site's cookie to a linked
file host, including over cleartext HTTP.

Redirect handling is explicit. Credentials, cookie headers, `Referer`, and `Origin` are
retained only for a same-origin redirect. A cross-origin redirect or HTTPS-to-HTTP
downgrade drops them before the next request. Redirects are limited to ten hops.

Download records keep ordinary headers only. Cookie, authorization, token, session,
CSRF, secret, origin, and referrer headers are held in process memory while a transfer
is active and are not written to the download preferences. They are removed from
legacy records the next time those records are read. If Android kills Phormi while an
authenticated transfer is paused, its in-memory credentials are lost; resume may require
starting the download again from the page. This is intentional rather than persisting
session credentials in a plain preferences file.

Range recovery is bounded. A 416 or a mismatched 206 response truncates the partial
file and permits one clean retry without a byte range. A second invalid range response
fails instead of recursively retrying or appending unverified bytes.

JVM regression coverage lives in `PhormiDownloadRequestPolicyTest` and
`PhormiDownloadResumePolicyTest`; these tests validate the pure policy decisions. A
connected Android transfer/device test is still desirable for platform-specific
MediaStore truncation and redirect behavior.
