package ai.verispec.tools.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

class Verispec : CliktCommand(name = "verispec") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = """
        VeriSpec-AI — conformance evidence toolkit for on-device AI.

        Verify, compare and report on evidence bundles produced by the Android
        operator console, and drive a device fleet over ADB.
    """.trimIndent()

    override fun run() = Unit
}

fun main(args: Array<String>) = Verispec()
    .subcommands(
        VerifyCommand(),
        DivergeCommand(),
        ReportCommand(),
        InspectCommand(),
        FleetCommand(),
    )
    .main(args)
