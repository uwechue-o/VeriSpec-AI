package ai.verispec.tools.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import kotlin.system.exitProcess

class Verispec : CliktCommand(name = "verispec") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = """
        VeriSpec-AI — conformance evidence toolkit for on-device AI.

        Verify, compare and report on evidence bundles produced by the Android
        operator console, and drive a device fleet over ADB.
    """.trimIndent()

    override fun run() = Unit
}

fun main(args: Array<String>) {
    Verispec()
        .subcommands(
            VerifyCommand(),
            DivergeCommand(),
            ReportCommand(),
            InspectCommand(),
            FleetCommand(),
        )
        .main(args)
    // The adam ADB client leaves non-daemon socket threads running, so after a fleet
    // command the JVM would never exit. Clikt has already exited on any error.
    exitProcess(0)
}
