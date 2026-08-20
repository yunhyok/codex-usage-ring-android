plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.cyclonedx.bom)
}

group = "io.github.yunhyok"
version = "0.1.0"

tasks.register<Exec>("nativeGateReport") {
    group = "verification"
    description = "Writes the fail-closed native feasibility report without claiming release readiness."
    commandLine(
        "pwsh",
        "-NoProfile",
        "-File",
        layout.projectDirectory.file("native/gate.ps1").asFile.absolutePath,
        "-ReportPath",
        layout.projectDirectory.file("native/gate-report.local.json").asFile.absolutePath,
        "-ProbeUpstream",
    )
    isIgnoreExitValue = true
    doLast {
        val report = layout.projectDirectory.file("native/gate-report.local.json").asFile
        check(report.isFile) { "Native gate did not produce ${report.absolutePath}" }
        logger.lifecycle("Native feasibility report written to ${report.absolutePath}; release readiness is evaluated only by nativeGate.")
    }
}

tasks.register<Exec>("nativeGate") {
    group = "verification"
    description = "Hard release gate: exits non-zero unless the Codex Android runtime is fully proven."
    commandLine(
        "pwsh",
        "-NoProfile",
        "-File",
        layout.projectDirectory.file("native/gate.ps1").asFile.absolutePath,
        "-ReportPath",
        layout.projectDirectory.file("native/gate-report.local.json").asFile.absolutePath,
        "-ProbeUpstream",
    )
}
