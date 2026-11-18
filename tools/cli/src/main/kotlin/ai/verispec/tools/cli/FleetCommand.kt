package ai.verispec.tools.cli

import ai.verispec.core.evidence.BundleCodec
import ai.verispec.tools.analysis.ComputeDivergenceUseCase
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.table.table
import com.malinskiy.adam.AndroidDebugBridgeClientFactory
import com.malinskiy.adam.interactor.StartAdbInteractor
import com.malinskiy.adam.request.shell.v1.ShellCommandRequest
import com.malinskiy.adam.request.device.ListDevicesRequest
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Base64

/**
 * Fleet orchestration over ADB. The CLI collects bundles and verifies them; it
 * never edits evidence.
 *
 * Bundles live in app-scoped storage, so they are read through `run-as`, which
 * the ADB sync (pull) protocol cannot reach. We stream them out base64-encoded
 * through the shell instead, then verify the decoded bytes locally. That means
 * collection needs a debuggable build of the app.
 */
class FleetCommand : CliktCommand(name = "fleet") {
    override fun help(context: Context) = "Drive a device fleet over ADB: list devices, collect bundles, compare them."

    init {
        subcommands(FleetDevicesCommand(), FleetCollectCommand(), FleetCompareCommand())
    }

    override fun run() = Unit
}

internal object Adb {
    val client by lazy { AndroidDebugBridgeClientFactory().build() }

    suspend fun serials(): List<String> = client.execute(ListDevicesRequest()).map { it.serial }

    suspend fun shell(serial: String, command: String): String =
        client.execute(ShellCommandRequest(command), serial).output.trim()

    suspend fun prop(serial: String, key: String): String = shell(serial, "getprop $key")
}

internal data class DeviceInfo(
    val serial: String,
    val manufacturer: String,
    val model: String,
    val soc: String,
    val android: String,
    val abi: String,
)

private suspend fun describe(serial: String) = DeviceInfo(
    serial = serial,
    manufacturer = Adb.prop(serial, "ro.product.manufacturer"),
    model = Adb.prop(serial, "ro.product.model"),
    soc = Adb.prop(serial, "ro.soc.model").ifBlank { Adb.prop(serial, "ro.board.platform") },
    android = Adb.prop(serial, "ro.build.version.release"),
    abi = Adb.prop(serial, "ro.product.cpu.abi"),
)

class FleetDevicesCommand : CliktCommand(name = "devices") {
    override fun help(context: Context) = "List attached devices with the SoC and Android version VeriSpec cares about."

    override fun run() {
        val devices = runBlocking {
            StartAdbInteractor().execute()
            Adb.serials().map { describe(it) }
        }
        if (devices.isEmpty()) {
            terminal.println(TextColors.yellow("No devices attached."))
            return
        }
        terminal.println(
            table {
                header { row("serial", "manufacturer", "model", "SoC", "android", "abi") }
                body {
                    devices.forEach { d -> row(d.serial, d.manufacturer, d.model, d.soc, d.android, d.abi) }
                }
            },
        )
    }
}

class FleetCollectCommand : CliktCommand(name = "collect") {
    override fun help(context: Context) = """
        Pull evidence bundles off every attached device into a local directory.

        Needs a debuggable build of the app, because bundles live in app-scoped
        storage and are read through run-as. Each bundle is verified after
        download; one that fails verification is still kept and reported, since
        a failed verification is itself a finding.
    """.trimIndent()

    private val outputDir by option("--out", help = "Directory to collect bundles into.")
        .file(canBeFile = false).default(File("bundles"))
    private val applicationId by option("--app-id", help = "Application id on the device.")
        .default("ai.verispec.app.debug")
    private val serialFilter by option("--serial", help = "Only collect from this device serial.")
    private val skipVerify by option("--no-verify", help = "Do not verify collected bundles.").flag()

    private data class Collected(val device: String, val fileName: String, val status: String)

    override fun run() {
        val results = mutableListOf<Collected>()
        val verify = if (skipVerify) null else verifier(emptySet())

        runBlocking {
            StartAdbInteractor().execute()
            val serials = Adb.serials().filter { serialFilter == null || it == serialFilter }
            if (serials.isEmpty()) {
                terminal.println(TextColors.yellow("No matching devices attached."))
                throw ProgramResult(1)
            }
            outputDir.mkdirs()

            for (serial in serials) {
                val info = describe(serial)
                val label = "${info.model} ($serial)"
                val listing = Adb.shell(serial, "run-as $applicationId ls files/bundles 2>/dev/null")
                val names = listing.lines().map { it.trim() }.filter { it.endsWith(".json") }
                if (names.isEmpty()) {
                    results += Collected(label, "-", TextColors.yellow("no bundles (is $applicationId installed and debuggable?)"))
                    continue
                }

                val deviceDir = File(outputDir, serial.replace(Regex("[^A-Za-z0-9._-]"), "_")).also { it.mkdirs() }
                for (name in names) {
                    val encoded = Adb.shell(serial, "run-as $applicationId cat files/bundles/$name | base64 -w 0")
                        .filter { !it.isWhitespace() }
                    val status = runCatching {
                        val bytes = Base64.getDecoder().decode(encoded)
                        val target = File(deviceDir, name)
                        target.writeBytes(bytes)
                        if (verify == null) {
                            "collected (${bytes.size} bytes)"
                        } else {
                            val report = verify(BundleCodec.decode(bytes))
                            if (report.isTrustworthy) TextColors.green("verified") else TextColors.red("VERIFICATION FAILED")
                        }
                    }.getOrElse { TextColors.red("failed: ${it.message}") }
                    results += Collected(label, name, status)
                }
            }
        }

        terminal.println(
            table {
                header { row("device", "bundle", "status") }
                body { results.forEach { row(it.device, it.fileName, it.status) } }
            },
        )
        terminal.println("Bundles are under ${outputDir.absolutePath}")
    }
}

class FleetCompareCommand : CliktCommand(name = "compare") {
    override fun help(context: Context) = "Group every bundle under a directory by manifest and report divergence for each group."

    private val directory by option("--dir", help = "Directory containing collected bundles.")
        .file(mustExist = true, canBeFile = false).default(File("bundles"))

    override fun run() {
        val files = directory.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        if (files.isEmpty()) {
            terminal.println(TextColors.yellow("No bundle files under ${directory.path}"))
            throw ProgramResult(1)
        }
        val bundles = files.mapNotNull { f -> runCatching { BundleCodec.decode(f.readText()) }.getOrNull() }
        val groups = bundles.groupBy { it.manifest.manifestId.value }.filterValues { it.size >= 2 }

        if (groups.isEmpty()) {
            terminal.println(
                TextColors.yellow(
                    "Found ${bundles.size} bundle(s) but no manifest is present on two or more devices. " +
                        "Divergence needs the same manifest run on different hardware.",
                ),
            )
            return
        }

        groups.forEach { (manifestId, group) ->
            val report = ComputeDivergenceUseCase()(group)
            terminal.println(TextStyles.bold("${report.label.ifBlank { "unlabelled" }}  manifest ${manifestId.take(16)}…"))
            terminal.println(
                table {
                    header { row("device", "delegate", "agreement", "first diverge", "mean cosine") }
                    body {
                        report.pairwise.forEach { d ->
                            row(
                                d.candidate.socModel,
                                d.candidate.delegate.name,
                                "%.2f %%".format(d.digestAgreementRate * 100),
                                d.firstDivergentIteration?.toString() ?: "-",
                                d.meanCosineSimilarity?.let { "%.6f".format(it) } ?: "n/a",
                            )
                        }
                    }
                },
            )
            report.warnings.forEach { terminal.println(TextColors.yellow("• $it")) }
        }
    }
}
