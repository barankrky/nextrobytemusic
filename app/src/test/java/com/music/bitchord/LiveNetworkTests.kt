package com.music.bitchord

import org.junit.Assume

/**
 * The gate for tests that talk to the real internet instead of a local fake.
 *
 * A live test earns its place — it is the only thing that notices when Genius
 * changes its HTML or starts answering differently — but it must not decide
 * whether a pull request is mergeable. `genius.com` answers GitHub's runner
 * IPs with a block or a captcha, so the live case in [GeniusTest] failed on
 * every Check run up to 42134066513 while passing on a developer machine, and
 * the Android job could never go green (18 failed, 972 completed, same set of
 * tests on six consecutive runs) even though the code was fine.
 *
 * So the default unit suite skips these tests and reports them as skipped,
 * and one property opts in:
 *
 *     ./gradlew testDevDebugUnitTest -PincludeLiveNetworkTests
 *
 * `app/build.gradle.kts` copies the Gradle property into this system property;
 * the test reads it here because Gradle's model is not visible from test code.
 */
internal object LiveNetworkTests {

    const val PROPERTY: String = "includeLiveNetworkTests"

    val enabled: Boolean
        get() = System.getProperty(PROPERTY)?.toBoolean() ?: false

    /**
     * Marks the calling test skipped unless the run opted in. JUnit reports a
     * failed assumption as a skip rather than a failure, which is the signal
     * wanted here: not covered by this run, and visibly so in the report.
     */
    fun requireEnabled() {
        Assume.assumeTrue(
            "Live network test: reaches the real internet and only runs when the build " +
                "is asked for it with -P$PROPERTY.",
            enabled,
        )
    }
}
