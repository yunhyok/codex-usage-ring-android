/*
 * BuildConfig shim for the vendored rustls-platform-verifier source.
 * The upstream AAR generates this class; native flavor source integration
 * supplies the release-only equivalent without resolving a Cargo AAR.
 */
package org.rustls.platformverifier;

public final class BuildConfig {
    private BuildConfig() {}

    /** Test-only hooks are not part of this application build. */
    public static final boolean TEST = false;
}
