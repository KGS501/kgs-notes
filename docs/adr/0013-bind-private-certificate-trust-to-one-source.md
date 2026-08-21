# Bind private certificate trust to one Source

KGS Notes requires HTTPS and system trust by default, but an advanced setup may import an out-of-band private CA or exact self-signed certificate for one canonical `https://host:port` Source. The dedicated client retains normal hostname verification and explicit certificate review and replacement; KGS Notes will not use trust-all, hostname bypass, blind trust-on-first-use, certificate pinning, or the entire Android user-CA store because those alternatives either broaden trust or make certificate rotation unsafe.
