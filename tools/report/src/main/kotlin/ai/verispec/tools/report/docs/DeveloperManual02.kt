package ai.verispec.tools.report.docs

import java.io.File
import java.time.LocalDate

/**
 * The developer manual, written as one engineer explaining the system to
 * another. It replaced the original edition (DeveloperManual, retired
 * 2026-11-16) and keeps the _02 file name.
 * Run through the Gradle task:
 *
 *     ./gradlew :tools:report:generateDeveloperManual02
 */
object DeveloperManual02 {

    const val TOOL_VERSION = "0.1.0"

    @JvmStatic
    fun main(args: Array<String>) {
        val target = File(args.firstOrNull() ?: "DOCS/VeriSpec-AI-Developer-Manual_02.pdf")
        target.parentFile?.mkdirs()
        target.writeBytes(render())
        println("Wrote ${target.absolutePath} (${target.length()} bytes)")
    }

    fun render(): ByteArray {
        val doc = DocPdf("VeriSpec-AI Developer Manual")

        doc.coverPage(
            subtitle = "A conformance evidence toolkit for on-device AI: why it exists, how it works, and how to work on it.",
            meta = listOf(
                "Toolkit version $TOOL_VERSION",
                "Bundle schema 1.3.0  ·  clause catalog 2026.1",
                "Generated ${LocalDate.now()}",
                "",
                "For developers who extend or review the toolkit.",
                "The project's own PDF renderer (tools/report) produced this",
                "file, so the manual and the evidence reports share one",
                "layout engine and one set of font constraints.",
            ),
        )
        doc.contentsPlaceholder()

        purpose(doc)
        whatItEmits(doc)
        architecture(doc)
        invariants(doc)
        howARunWorks(doc)
        console(doc)
        cli(doc)
        extending(doc)
        buildAndToolchain(doc)
        testing(doc)
        stateOfTheWork(doc)
        appendixSchema(doc)
        appendixGlossary(doc)

        return doc.finish()
    }

    /* ---------------------------------------------------------------- 1 */

    private fun purpose(doc: DocPdf) {
        doc.h1("Why this exists")
        doc.lede("Anyone can run a model on a phone, but almost nobody can prove how it behaves once it ships to a fleet.")

        doc.p(
            "Running a quantized model on a handset is routine now, but showing someone who does not trust you how " +
                "that model behaved on ten thousand different handsets is not. No accepted artifact answers that " +
                "question, no tooling produces one, and a third party has no way to check an answer somebody else " +
                "hands them. VeriSpec-AI is an attempt at that artifact, and at the tooling around it.",
        )
        doc.p(
            "Regulated industries will soon have to close this gap. The Digital Omnibus on AI pushed the high-risk " +
                "obligations back, to 2 December 2027 for standalone Annex III systems and to 2 August 2028 for AI " +
                "built into regulated products such as medical devices and machinery. The Commission's reason was that " +
                "the conformity-assessment infrastructure the Act assumes had not matured yet, which, read plainly, " +
                "describes a product nobody has built.",
        )
        doc.p(
            "Two years is a useful runway: long enough to build the tooling and be established before demand peaks, " +
                "and short enough that the work is not speculative.",
        )

        doc.h2("The specific claims a bundle supports")
        doc.p(
            "This is not a benchmark, which tells you \"how fast\" once, on one device, in a way nobody can " +
                "reproduce. An evidence bundle answers a narrower set of questions, and its answers survive being " +
                "handed to a reviewer:",
        )
        doc.bullets(
            listOf(
                "This exact model artifact, identified by hash, ran on this exact hardware, identified by build fingerprint and SoC.",
                "It ran through this runtime build, on this delegate, against these driver versions, all of them observed at run time rather than assumed.",
                "Under sustained load it slowed down by this much, starting here, in this thermal state.",
                "Given identical inputs, its outputs on device A and device B agreed to this degree; quantized inference is not bit-identical across vendors, and almost nobody documents that.",
                "These regulatory clauses are evidenced by the above, and these are not.",
            ),
        )

        doc.h2("The last point is the one that gets skipped")
        doc.p(
            "A compliance tool that only ever shows green is a liability, because it invites you to believe a gap is " +
                "closed when it has only been hidden. So wherever this toolkit shows coverage, it shows the gaps at " +
                "least as prominently: the clause screen leads with the count of clauses that are not fully evidenced, " +
                "the PDF prints the gap banner before the per-clause list, and the mapper writes out which evidence is " +
                "missing instead of leaving a blank.",
        )

        doc.h2("Neutrality is structural, not promised")
        doc.p(
            "The numbers are only worth something if a vendor whose hardware looks bad cannot wave the measurement " +
                "away, so the code enforces neutrality by construction, not by policy:",
        )
        doc.bullets(
            listOf(
                "Every backend runs through the same loop in ExecuteRunUseCase, with the same injected clock and the same telemetry sources, and no backend has timing code of its own.",
                "Adapters can't tune the measurement path: an adapter loads a model and returns a result, and never decides how that result is timed or digested.",
                "A missing backend becomes a recorded capability gap in the bundle; it never crashes the run and is never silently skipped, so you see an absent delegate instead of inferring it from missing rows.",
                "Any vendor can submit fixes to their own adapter, but we refuse changes that add vendor-specific tuning to the shared measurement path.",
            ),
        )
    }

    /* ---------------------------------------------------------------- 2 */

    private fun whatItEmits(doc: DocPdf) {
        doc.h1("What the toolkit produces")

        doc.p(
            "Each run produces an evidence bundle: canonical JSON, content-addressed, signed by a hardware-backed key, " +
                "and self-describing. A companion PDF shows the same content in a form you can put in a submission " +
                "binder, but the JSON is the artifact of record and the PDF is just a view of it.",
        )

        doc.h2("Anatomy of a bundle")
        doc.table(
            "Section" to "What it holds and why",
            listOf(
                "manifest" to "The run specification, which never changes, plus its own content hash as manifestId, so two manifests with identical content are the same run by construction.",
                "device" to "Build fingerprint, SoC vendor and model, driver versions, Android release and security patch, RAM, ABIs and thermal HAL, all read at run time and never assumed.",
                "provenance" to "Model hash and format; runtime version as declared and as observed; the delegate you asked for and the one actually used; NPU and GPU driver versions. Notes record any degradation, such as a delegate fallback.",
                "observations" to "Per-iteration timings and thermal state, the thermal and power timelines, the memory envelope, and one output digest per iteration.",
                "clauseCoverage" to "Status for each clause, naming the evidence requirements it satisfies and the ones it is missing, with a written gap description for anything short of satisfied.",
                "signature" to "A detached ECDSA signature over the canonical bytes, with the key id, whether the key was hardware-backed, and whether StrongBox was used.",
                "attestationChain" to "The X.509 chain from the Keystore, leaf first, whose attestation extension binds the key to this manifest hash.",
            ),
        )

        doc.h2("What a bundle deliberately leaves out")
        doc.callout(
            "Privacy constraint",
            "Bundles carry output digests and an optional fixed-size numeric projection, never raw model output. " +
                "Outputs can contain personal data derived from the input, and an evidence file is exactly the kind of " +
                "thing people email around, so the type system enforces this: OutputDigest has a hash and a FloatArray " +
                "and nowhere to put text. This is easy to miss at design time and expensive to retrofit, which is why " +
                "it was the first thing decided.",
        )
        doc.p(
            "The projection squeezes the output vector down to exactly 64 values by averaging fixed buckets, the same " +
                "way every time. That keeps enough signal for the divergence maths (cosine similarity, L-infinity " +
                "distance and symmetric KL) without letting anyone turn it back into content.",
        )

        doc.h2("Identity and tamper evidence")
        doc.p(
            "The bundleId is the SHA-256 of the canonical bytes of everything except the signature and the chain, so " +
                "changing any observation changes the id. If a bundle's id does not match its content, someone altered " +
                "it after signing, and verification tells you exactly that. The manifestId uses the same trick one " +
                "level down, over the manifest content minus the id itself.",
        )
    }

    /* ---------------------------------------------------------------- 3 */

    private fun architecture(doc: DocPdf) {
        doc.h1("Architecture")
        doc.lede("Kotlin, Clean Architecture, MVVM and multi-module Gradle, with dependencies pointing inward only and the build enforcing it.")

        doc.h2("Module topology")
        doc.code(
            "VeriSpec-AI/",
            "|- core/",
            "|  |- domain/          pure Kotlin/JVM. Zero Android, zero framework.",
            "|  |- evidence/        wire model, canonical JSON, hashing, schema, verification",
            "|  '- clauses/         versioned regulatory clause catalog (YAML) + loader",
            "|- data/",
            "|  |- runtime-api/     InferenceBackend registry + capability model",
            "|  |- runtime-litert/  LiteRT adapter (tensor inference)",
            "|  |- runtime-llamacpp/ llama.cpp JNI adapter (GGUF, text generation)",
            "|  |- runtime-executorch/ ExecuTorch .pte adapter",
            "|  |- runtime-mediapipe/  MediaPipe Tasks GenAI adapter",
            "|  |- telemetry/       thermal, power, memory sources",
            "|  |- attestation/     Keystore signing, key attestation, device profiling",
            "|  '- persistence/     Room, DataStore, file-backed bundle store",
            "|- app/",
            "|  |- ui/              Compose + MVVM operator console",
            "|  '- runner/          WorkManager foreground execution engine",
            "|- tools/",
            "|  |- cli/             JVM fleet orchestrator over ADB",
            "|  |- analysis/        divergence + decay analytics (JVM, headless)",
            "|  '- report/          PDF / JSON rendering",
            "'- build-logic/        convention plugins, dependency-rule enforcement",
        )

        doc.h2("The dependency rule, enforced by the build")
        doc.p(
            "core/domain depends on the Kotlin standard library, coroutines and kotlinx-datetime, and nothing else. " +
                "data and app modules may depend on core, and tools may depend on core and on other tools, but nothing " +
                "may depend outward. A convention plugin checks this, because good intentions don't survive a deadline.",
        )
        doc.code(
            "core/*   ->  core/*",
            "data/*   ->  core/*, data/runtime-api",
            "app/*    ->  core/*, data/*, app/*",
            "tools/*  ->  core/*, tools/*        (never Android)",
        )
        doc.p(
            "At configuration time, DependencyRulesPlugin walks every checked configuration of every subproject and " +
                "throws if a project dependency crosses a layer the wrong way, so you can't commit a violation and " +
                "have someone notice it later: the build fails for whoever wrote it. To run the check on its own, use " +
                "gradlew verifyDependencyRules.",
        )

        doc.h2("Why tools/analysis is JVM-only")
        doc.callout(
            "Load-bearing split",
            "Divergence analysis compares results across devices, so it can't run on any one device. Keeping it in a " +
                "JVM-only module is not a style choice but the reason you can trust the analysis: no device ever " +
                "grades its own homework. The on-device Fleet screen shows a quick digest-agreement summary, but the " +
                "full report is built on a host from collected bundles.",
            java.awt.Color(0.07f, 0.20f, 0.23f),
        )

        doc.h2("Presentation layer")
        doc.p(
            "MVVM with a strict contract: ViewModels hold no domain logic and no Android Context, and only turn " +
                "intents into use-case calls and domain results into UI state. Composables get a state object and a " +
                "function of type (RunIntent) -> Unit, never the ViewModel itself, so previews and screenshot tests " +
                "work without a dependency graph.",
        )
        doc.p(
            "State is a sealed interface with one branch per situation, so an impossible combination like \"running " +
                "and complete\" can't be represented at all. One-shot events, such as navigation and error toasts, go " +
                "through a separate effects channel instead of state, so they don't replay when you rotate the screen.",
        )
    }

    /* ---------------------------------------------------------------- 4 */

    private fun invariants(doc: DocPdf) {
        doc.h1("Invariants you must not break")
        doc.lede("Four architectural drivers shape every decision in the codebase, and they are the point of the project, not preferences.")

        doc.h2("Evidence must be reproducible")
        doc.p(
            "Every run is described by a hashed RunManifest that never changes, with no hidden state and no " +
                "dependence on wall-clock time. Elapsed times come from an injected monotonic clock, and the bundle " +
                "records exactly one wall-clock stamp, for provenance. Inputs are derived from the manifest, so the " +
                "same manifest produces the same inputs on every device.",
        )
        doc.bullets(
            listOf(
                "Text generation always uses greedy decoding: ValidateManifestUseCase rejects a manifest with temperature other than 0 or topK other than 1, because sampled output can't produce comparable digests.",
                "Tensor inputs come from SplitMix64, seeded from the manifest seed and the iteration index. We picked SplitMix64 because it ports to C or Python in a few lines, so a reviewer can regenerate the inputs without running this code.",
                "Validation happens before the run, not after, because rejecting a non-reproducible manifest up front saves you an hour of device time spent producing evidence that proves nothing.",
            ),
        )

        doc.h2("Evidence must be tamper-evident")
        doc.p(
            "Bundles are signed by a key generated in the Android Keystore, with setAttestationChallenge bound to the " +
                "manifest hash, and the certificate chain that comes back proves two things: that the key is " +
                "hardware-backed, and which run it was created for. That is stronger than a signature alone, because " +
                "nobody can reuse a key from one run to bless another.",
        )
        doc.p(
            "The code tries StrongBox first and falls back to the TEE when StrongBox isn't available, and the " +
                "downgrade is recorded rather than hidden: strongBox = false in the signature, plus a note in the " +
                "provenance. Honestly recording a weaker guarantee is worth more than implying a stronger one.",
        )

        doc.p(
            "A chain only proves hardware if its root is one you trust, since anyone can mint a chain that is " +
                "consistent with itself. AttestationChainVerifier.GOOGLE_ROOT_SPKI_SHA256 pins Google's two published " +
                "hardware attestation roots by SubjectPublicKeyInfo hash (RSA feb2ea75..., covering its 2016, 2019 " +
                "and 2022 certificates, and ECDSA Key Attestation CA1, 3ee44512...), and both the app and the CLI " +
                "trust them by default. The Pixel 6 and Tab S7+ bundles chain to the RSA root, the Honor's to the " +
                "ECDSA one, and GoogleRootsTest verifies all three. The pins are public, recomputable from " +
                "android.googleapis.com/attestation/root: patching them in a copy of the app or CLI fools only " +
                "whoever runs that copy, so the verification that counts is the reviewer's, on their own build. Not " +
                "yet checked: Google's revocation list and the verified-boot state in the attestation extension, so " +
                "a leaked attestation key or an unlocked bootloader would still pass.",
        )

        doc.h2("Regulation moves, so clause mappings are data")
        doc.p(
            "Clause definitions live in versioned YAML resources under core/clauses, never in Kotlin, so when the " +
                "regulation changes you add a new catalog version directory instead of cutting a code release. " +
                "Catalog versions are immutable, so edit an existing one only to fix a typo. The Digital Omnibus " +
                "deferral proved this was necessary before the toolkit had shipped anything.",
        )

        doc.h2("Runtimes are swappable and resist abstraction")
        doc.p(
            "Each inference backend sits behind the narrow InferenceBackend port, with an explicit capability model, " +
                "and the domain never learns how a delegate is implemented. At startup the app asks each adapter what " +
                "it can do on this device, and if a probe throws, the exception is caught and becomes an unavailable " +
                "capability with the reason attached.",
        )

        doc.h2("Canonical serialization")
        doc.callout(
            "The most common place these tools break",
            "Two runs with identical observations must produce byte-identical JSON before signing. If that fails now " +
                "and then, signatures fail now and then, and the whole artifact stops being trustworthy for reasons " +
                "nobody can reproduce, which is why it is property-tested rather than example-tested.",
        )
        doc.p("Here are the rules, written out so you can build an independent implementation against them:")
        doc.numbered(
            listOf(
                "Object keys are sorted by Unicode code point, ascending, comparing code points rather than UTF-16 units so that surrogate pairs sort correctly.",
                "No insignificant whitespace anywhere.",
                "Strings are escaped minimally, per RFC 8259: quote, backslash and U+0000 to U+001F only, using the short forms where they exist.",
                "Numbers use their shortest round-trip representation and negative zero becomes zero, while NaN and infinity are rejected outright, never encoded.",
                "Nulls are explicit and defaults are never omitted, so you can never confuse a missing field with a null one.",
                "UTF-8, no byte order mark, no trailing newline.",
            ),
        )
        doc.p(
            "The published JSON Schema lives at core/evidence/src/main/resources/schemas/evidence-bundle-1.3.0.schema.json, " +
                "versioned separately from the Kotlin, and downstream tools should target the schema, not this " +
                "implementation. Getting it wrong later costs far more than designing it carefully now.",
        )
        doc.p(
            "1.1.0 only adds things: the optional precondition gate, manifest.preconditions and " +
                "observations.preconditions. Both DTO fields carry @EncodeDefault(NEVER), so when unused they are " +
                "absent, never null, and a 1.0.0 bundle or manifest therefore re-encodes to exactly its original " +
                "bytes and keeps its id and signature. SchemaCompatibilityTest pins this against golden files written " +
                "by the 1.0.0 code, and the 1.0.0 schema file stays published unchanged.",
        )
        doc.callout(
            "Note",
            "Never regenerate the golden files in core/evidence/src/test/resources/golden/. They were written by the " +
                "1.0.0 code, and that is the whole point, because they prove old bundles still verify; if a change " +
                "makes SchemaCompatibilityTest fail, fix the change, not the files.",
        )
        doc.p(
            "1.2.0 adds hardware telemetry the same way. Each power sample gains battery temperature and screen " +
                "state, IterationRecord.clocks holds CPU clocks per core and the GPU clock in kHz, " +
                "observations.telemetry lists which signals this device let the app read and which it did not, and " +
                "there is the ON_DIMMED screen policy. Older schema files stay published unchanged, and the 1.1.0 " +
                "schema rejects a bundle carrying 1.2.0 fields, which is what makes the version bump mean something.",
        )
        doc.p(
            "1.3.0 adds, the same way, the named accelerator behind a delegate: BackendRef.accelerator in the " +
                "manifest (NNAPI only, validated in its init block), and accelerator, acceleratorActuallyUsed and " +
                "acceleratorsAvailable in the provenance. A manifest without a name encodes exactly as before, so its " +
                "id does not move, and the 1.2.0 schema rejects a bundle that carries the new fields.",
        )
    }

    /* ---------------------------------------------------------------- 5 */

    private fun howARunWorks(doc: DocPdf) {
        doc.h1("How a run works")
        doc.lede("From a filled-in form to a signed file on disk, in the order the code runs.")

        doc.numbered(
            listOf(
                "Compose. You fill in the Run Composer, ManifestFormMapper turns the form strings into a draft RunManifest, and ManifestIdentity then hashes its canonical content and stamps the result in as the manifestId.",
                "Validate. ValidateManifestUseCase checks the identity hash, the reproducibility constraints, and whether the chosen backend can actually do what the manifest asks, given the capabilities it reported. Errors block the run; warnings don't.",
                "Schedule. RunScheduler queues a unique foreground worker keyed by manifest id and stores the run as PENDING; the worker's input is just the manifest id.",
                "Gate. If the manifest has a PreconditionPolicy, AwaitPreconditionsUseCase polls DeviceConditionSource every 2 s, waiting until every condition (thermal status, headroom margin, unplugged, battery level, screen state) has held for the whole stabilization window and restarting the window whenever one fails. All of this happens before profiling and model loading, because both heat the device. If the wait times out the run fails, unless the policy says proceedIfUnmet, in which case it runs anyway and records the outcome as unsatisfied. The screen policy is OFF, ON, or ON_DIMMED; under ON_DIMMED the live screen holds the display on at minimum brightness from the wait to the last iteration, as ThermalBench does, because some SoCs clock the GPU down when the screen is off. Minimum here means a small non-zero value, since zero turns some backlights off entirely.",
                "Profile. The worker calls ProfileDeviceUseCase, which reads the build fingerprint, SoC identity, driver versions and thermal HAL, and opens an off-screen EGL context to learn the GPU renderer and driver string.",
                "Execute. ExecuteRunUseCase starts the thermal and power telemetry collectors, loads the model through the adapter, runs the warmup iterations, then the measured ones, reporting progress after each.",
                "Flush. Each completed iteration goes into Room straight away, because a crash at iteration 47 must not lose the first 46.",
                "Map. Once the observations are complete, EvidenceFacts.derive works out what the bundle actually demonstrates, and MapClausesUseCase combines those facts with the clause catalog to produce coverage, with the gaps named.",
                "Assemble. AssembleEvidenceUseCase builds the provenance record, puts the coverage in a fixed order, canonicalises everything and hashes it to get the bundleId.",
                "Sign. SignEvidenceUseCase signs the canonical bytes with a Keystore key whose attestation challenge is the manifest hash, and attaches the certificate chain.",
                "Store. The bundle is written atomically to app-scoped storage as a file, never as a blob in SQLite, and an index row goes into Room.",
            ),
        )
        doc.callout(
            "Note",
            "Two things trip people up at the Gate step. First, unplug the phone: under a policy that requires it, a " +
                "plugged-in phone just waits, because charging adds heat. Second, ON_DIMMED only works while VeriSpec " +
                "stays in the foreground, and if you switch to another app the screen can turn off mid-run, which is " +
                "why every power sample records the screen state.",
        )

        doc.h2("Why the worker holds only a manifest id")
        doc.p(
            "A sustained-load run lasts tens of minutes, and it has to survive process death, screen-off and Doze. " +
                "If the worker carried the whole manifest in its input data, it would have to rebuild partial state " +
                "after a restart, so instead it looks everything up again from persistence. That makes a restart " +
                "idempotent: the worker either finds the run and starts it, or finds nothing and fails cleanly.",
        )

        doc.h2("Wake locks and their effect on measurement")
        doc.callout(
            "Document your own overhead",
            "RunWorker holds a PARTIAL_WAKE_LOCK for the whole run, so the CPU keeps working with the screen off, and " +
                "releases it in a finally block with a four-hour ceiling, so a stuck backend can't pin the device " +
                "forever. The worker itself never keeps the screen on, so power samples reflect compute load without " +
                "the display's draw. Any change here changes what the power numbers mean, so describe it in the manual " +
                "as well as the code.",
        )

        doc.h2("Thermal state and what counts as throttled")
        doc.p(
            "Thermal status comes from PowerManager, both as a change listener and as a one-second tick, so you get a " +
                "timeline even when the status never changes. The app reads headroom where the platform provides it, " +
                "and reads the kernel thermal zones on a best-effort basis: some devices expose them, some don't.",
        )
        doc.p(
            "Since 1.2.0, HardwareTelemetrySource adds clocks and an inventory. After every iteration, outside the " +
                "timed region and the same way for every backend, the loop reads each core's scaling_cur_freq and the " +
                "first readable GPU node (kgsl, Mali or devfreq), normalising each value by its magnitude because " +
                "those nodes report Hz, kHz or MHz. The CPU list is null unless every core reads, so you never mistake " +
                "a partial list for the whole. Thermal zones are found once per process and stay on the 1 Hz thermal " +
                "timeline rather than per iteration, which keeps bundles small.",
        )
        doc.p(
            "At run start, the app writes an inventory of what it could read and what it couldn't: on a Pixel 6 it " +
                "reads all eight core clocks, the Mali clock and battery temperature, but SELinux denies " +
                "/sys/class/thermal. HardwareSummary reduces all of this to ranges for the console, the CLI and the PDF.",
        )
        doc.p(
            "There are two throttling thresholds, because both matter. IterationRecord.wasThrottled, stored in the " +
                "bundle, marks an iteration whose start or end state is MODERATE or worse, meaning the device is " +
                "cutting performance in earnest. The envelope uses an earlier line, dating the first throttle and " +
                "counting throttled iterations from LIGHT (ThermalStatus.isThrottleOnset), the first sign of thermal " +
                "control, and reporting the MODERATE count beside it. Both come from the stored per-iteration " +
                "statuses, so old bundles analyse the same way.",
        )

        doc.h2("Peak, steady state and the envelope verdict")
        doc.p(
            "SustainedLoad holds the rules, which come from ThermalBench. ComputeThermalEnvelopeUseCase on the device " +
                "and ComputeDecayUseCase on the host both call it, so the phone and the report never disagree.",
        )
        doc.bullets(
            listOf(
                "Peak is the best rolling median over 5 iterations, not the single fastest iteration, so one lucky iteration can't set it.",
                "Steady state needs a settled tail. The last window must be steady: its robust spread (1.4826 x MAD over the median) under 5 %, at most 2 iterations in 10 more than 15 % from its median, and no drift (the medians of its two halves within 3 %). The tail then reaches back through every earlier window that stays within 5 % of that level without drifting, and its value is the tail's median. The window is 10 iterations or 1/30 of the run, whichever is larger.",
                "If the last window is not steady, the run never settled, and steady state and decay are null, because reporting the end of a still-falling curve as a floor would be worse than reporting nothing.",
                "The steady-state rule was revised on 2026-11-16. The original (every 10-iteration window with plain CV under 5 % and least-squares slope under 3 %) let a single half-speed stall break the tail, so a 3,000-iteration Pixel 6 run flat at 21.8-22.0 inf/s never settled; it now settles from iteration 0 at 21.9. Clustered stalls, alternating speeds and a decline still running at the end are still rejected, but a very slow drift, well under 0.5 % across a window, isn't detected. Envelopes are recomputed from the stored iterations, so older bundles analyse under the new rule too.",
            ),
        )
        doc.p(
            "EnvelopeValidity is VALID only when the gate was satisfied and the run has at least 10 iterations; " +
                "otherwise it is PRECONDITIONS_UNMET, NO_PRECONDITION_GATE or INSUFFICIENT_DATA. EvidenceFacts marks " +
                "the performance envelope PRESENT only when the gate was satisfied, so an ungated run covers the " +
                "related clauses only partially.",
        )
    }

    /* ---------------------------------------------------------------- 6 */

    private fun console(doc: DocPdf) {
        doc.h1("Using the operator console")
        doc.lede("Seven screens, reached from four tabs, and plain on purpose: content over chrome.")

        doc.h2("Run Composer")
        doc.p(
            "This is where you build and validate a manifest. Every field is on screen, with nothing tucked behind an " +
                "advanced toggle, because every field is part of the hashed identity and you need to see what you're " +
                "committing to.",
        )
        doc.bullets(
            listOf(
                "The backend list at the top comes from live capability discovery, not a fixed list, so after you install or remove a runtime, tap Re-probe backends.",
                "Import model file copies your file into app-scoped storage and hashes it on the way in, so the manifest names the exact bytes; adapters check that hash again at load time, which means a swapped file gets caught twice.",
                "No model file? You don't need one to try the app: Or use a starter model lists default models (StarterModels.kt), namely four small float32 LiteRT tensor models; EfficientNetV2-S for ExecuTorch (XNNPACK fp32 .pte, about 82 MB, the export made for ExecuTorch 1.4, the version the app ships; ExecuTorch gives no forward compatibility, so a newer export may need the runtime raised to match); and one default LLM, Qwen2.5-0.5B-Instruct, in two forms: an int8 MediaPipe task bundle (about 520 MB) and the official Q8_0 GGUF for llama.cpp (about 645 MB), so the same model can be compared across two runtimes. All are Apache-2.0 and the Hugging Face ones are pinned to a commit as well as a hash. None of them ships inside the APK; the one you pick is downloaded on first use. Each is pinned to a SHA-256, the download is hashed as it streams and rejected if the hash doesn't match, and a pinned copy already on disk is reused. Picking one also fills in format, quantization, runtime and workload (input shape for a tensor model; the matching runtime and a text-generation workload for an LLM), and this is the only thing in the app that uses the INTERNET permission. On a Pixel 6 the MediaPipe Qwen has had a 10-iteration functional check and a 500-iteration measured run (VALID, unplugged, 128 tokens per iteration): all digests matched in both, peak decode was 24.6 tok/s and it settled visibly near 17.6 tok/s once the phone throttled (see the state-of-the-work callout for the caveats). The llama.cpp GGUF has had a 30-iteration functional check at about 22 tok/s.",
                "Validate shows errors and warnings without starting anything, along with the manifest id the current form produces, so two operators who type the same values get the same id.",
                "Start run schedules the foreground worker and takes you to the live view.",
            ),
        )

        doc.h2("Live Run")
        doc.p(
            "Shows a real-time decay chart, thermal state and throttle events; the chart plots decode throughput " +
                "against iteration index, with throttled iterations in red, so you watch thermal degradation as it " +
                "happens instead of finding it afterwards. Both axes carry round-number tick labels, the y axis starts at 0 " +
                "so a drop reads in proportion, and the windowed peak shows as a dashed line. Past 60 iterations only the " +
                "line is drawn, but throttled markers always stay. Progress survives leaving the screen, because it comes from " +
                "WorkManager progress and the Room iteration rows, not from in-memory state.",
        )

        doc.h2("Bundles and Bundle Detail")
        doc.p(
            "The list shows every bundle on the device, local and imported, with its signature status, and Detail " +
                "shows provenance, device, the full performance envelope, and the attestation chain one certificate " +
                "at a time. Verify now runs the same verification as the CLI and records the result, and Export JSON " +
                "writes the canonical bytes through the system file picker. Under the envelope, the throughput chart " +
                "marks the peak and steady state as dashed lines, and Telemetry over time draws each readable signal " +
                "(fastest core clock, GPU clock, battery temperature, thermal headroom) against seconds since run " +
                "start, so a clock drop and a temperature rise line up. Temperature gets a tight axis instead of one " +
                "from 0, and a signal that was never read is left out rather than drawn flat.",
        )
        doc.callout(
            "PDF export happens on a host, by design",
            "The device exports canonical JSON, and the CLI renders the PDF on a host, because PDFBox is a desktop " +
                "library. You produce the binder document once, at the end of a campaign, not once per device, and " +
                "keeping it off the phone keeps the app's dependencies smaller.",
        )
        AppScreenFigures.developerSection(doc)

        doc.h2("Clause Coverage")
        doc.p(
            "Clauses are grouped by framework, with gaps first in each group, under a banner that counts the clauses " +
                "not fully evidenced. Each clause shows its reference, its title, which evidence requirements it " +
                "satisfies, and a written description of what is missing.",
        )

        doc.h2("Fleet")
        doc.p(
            "Groups bundles by manifest, so the same run on different devices lines up, and where two or more bundles " +
                "share a manifest, it shows digest agreement against the first, as a quick look. A manifest that ran " +
                "on only one device is labelled that way, because you can't measure divergence from one sample.",
        )

        doc.h2("Verify")
        doc.p(
            "Opens a bundle file from somewhere else and checks it using nothing but the file and public " +
                "certificates, with no state from the producing device involved. It reports the canonical hash, the " +
                "signature, the chain, whether the key was hardware-backed, and whether the attestation challenge is " +
                "bound to that bundle's manifest. You can import a verified bundle into the local store so it shows " +
                "up on the Fleet tab.",
        )
    }

    /* ---------------------------------------------------------------- 7 */

    private fun cli(doc: DocPdf) {
        doc.h1("Using the command line")

        doc.p("To get the verispec launcher, build it, then run it from the install directory or put it on your PATH:")
        doc.code(
            "./gradlew :tools:cli:installDist",
            "tools/cli/build/install/verispec/bin/verispec --help",
        )

        doc.h2("Command reference")
        doc.table(
            "Command" to "What it does",
            listOf(
                "verify <bundle>..." to "Checks the canonical hash, signature, attestation chain and challenge binding. Google's attestation roots are always trusted, and repeating --trusted-root adds more; --strict exits non-zero unless every bundle is fully trustworthy, which is what you want in CI.",
                "inspect <bundle>" to "Prints provenance, device, performance envelope and clause coverage as tables: the fastest way to see what a file actually contains.",
                "diverge <a> <b>..." to "Cross-device divergence over two or more bundles of the same manifest, refusing bundles from different manifests instead of producing a meaningless number. --reference picks the baseline; --json writes an analysis document.",
                "report <bundle>" to "Renders the submission PDF and the machine-readable analysis JSON. --compare adds other bundles for a divergence section; --no-verify skips verification.",
                "fleet devices" to "Lists attached devices with their SoC, Android release and ABI.",
                "fleet collect" to "Pulls bundles off every attached device and verifies each one; --out sets the directory, --app-id the application id, and --serial limits it to one device.",
                "fleet compare" to "Groups every bundle under a directory by manifest, and reports divergence for each group that spans more than one device.",
            ),
        )

        doc.h2("A typical fleet session")
        doc.code(
            "# 1. see what is attached",
            "verispec fleet devices",
            "",
            "# 2. pull and verify everything the fleet has produced",
            "verispec fleet collect --out bundles",
            "",
            "# 3. find manifests that ran on more than one device",
            "verispec fleet compare --dir bundles",
            "",
            "# 4. produce the binder document for one device, with divergence",
            "verispec report bundles/<serial>/<bundle>.json \\",
            "    --compare bundles/<other>/<bundle>.json \\",
            "    --pdf out/evidence.pdf --json out/analysis.json",
        )

        doc.callout(
            "fleet collect needs a debuggable build",
            "Bundles live in app-scoped storage, which the ADB sync protocol can't reach, so collection reads them " +
                "through run-as and streams them out base64-encoded over the shell, and that only works against a " +
                "debuggable build of the app. A bundle that fails verification is still kept and reported, because a " +
                "failed verification is a finding in its own right, not a reason to throw the file away.",
        )

        doc.h2("Sample bundles without hardware")
        doc.p(
            "To try the CLI with nothing attached, generate a pair of realistic, genuinely signed bundles, the second " +
                "of which drifts away from the first partway through:",
        )
        doc.code(
            "./gradlew :tools:cli:writeSampleBundles",
            "verispec diverge tools/cli/build/samples/pixel8pro.json \\",
            "                 tools/cli/build/samples/galaxys24.json",
        )
        doc.p(
            "These are signed with a throwaway JCA key, not a Keystore key, so their signatures verify and their " +
                "attestation chains correctly don't. That combination is useful, because it is exactly what a bundle " +
                "produced outside a real TEE should look like, and verification has to report it, not pass it.",
        )
    }

    /* ---------------------------------------------------------------- 8 */

    private fun extending(doc: DocPdf) {
        doc.h1("Extending the toolkit")

        doc.h2("Adding an inference backend")
        doc.p(
            "A backend is a module under data/ that implements InferenceBackend and adds itself to a Dagger " +
                "multibinding. The work is mechanical; the constraints are what matter.",
        )
        doc.numbered(
            listOf(
                "Create the module with the verispec.android.library and verispec.android.hilt convention plugins, and add it to settings.gradle.kts.",
                "Implement capabilities() to probe the runtime honestly: if the native library is missing or the probe throws, return BackendCapabilities.unavailable with a reason a person can act on, and never let an exception escape a probe.",
                "Implement load() with ModelFiles.resolveAndVerify. It re-hashes the file and refuses to load bytes that don't match the manifest; without that, provenance would be fiction.",
                "Implement infer() with AdapterSupport for timing and digesting, and don't time the call or hash the output yourself, because the shared helpers are what keep backends comparable.",
                "Record any fallback in effectiveDelegate: if the manifest asked for NNAPI and the graph wouldn't compile, run on CPU and say so, and the assembler turns the difference into a provenance note automatically. A delegate that is accepted but takes no operations is a fallback too: LiteRT's NNAPI delegate does exactly that on a device that supports none of the model, which is why LiteRtBackend reads the delegation count back (DelegationLog) and reports it in LoadedModel.loadNotes.",
                "Bind the adapter with @IntoMap and @RuntimeKey in a Hilt module, and add the RuntimeId if it is a new runtime.",
            ),
        )
        doc.callout(
            "The line adapters must not cross",
            "An adapter loads a model and returns a result; it never decides how that result is timed, hashed or " +
                "projected, and it never applies tuning the other backends don't get. If a change would make one " +
                "backend look better for reasons that have nothing to do with the hardware, it belongs somewhere else, " +
                "or nowhere.",
        )

        doc.h2("Adding a clause catalog version")
        doc.p(
            "To add a version, create a new directory under core/clauses/src/main/resources/clauses/, copy the four " +
                "framework files forward, edit them, and register the version in YamlClauseCatalog. Never edit a " +
                "published version in place except to fix a typo, because bundles record the catalog version they " +
                "were mapped against, and rewriting history under them makes old evidence impossible to reproduce.",
        )
        doc.p(
            "Each clause names the EvidenceRequirement values it needs, so adding a clause is a catalog change and " +
                "needs no code. Adding a new kind of evidence is a schema change: extend the EvidenceRequirement enum, " +
                "teach EvidenceFacts.derive how to detect it, and bump the bundle schema.",
        )

        doc.h2("Changing the wire format")
        doc.p(
            "The DTOs in core/evidence/wire are the only code that touches kotlinx.serialization; the domain stays " +
                "free of annotations. Their field names are the published schema's field names, so renaming one breaks " +
                "every downstream consumer. Update the JSON Schema in the same commit, bump the schema version, and " +
                "expect the canonical-serialization property tests to hold you to it.",
        )
        doc.callout(
            "Note",
            "New wire fields must be optional and carry @EncodeDefault(NEVER), so they are absent, never null, when " +
                "unused; otherwise older bundles re-encode to different bytes, and their ids and signatures stop " +
                "matching.",
        )
    }

    /* ---------------------------------------------------------------- 9 */

    private fun buildAndToolchain(doc: DocPdf) {
        doc.h1("Build and toolchain")

        doc.h2("Requirements")
        doc.table(
            "Component" to "Version and why",
            listOf(
                "JDK" to "17, since every module compiles to JVM 17 bytecode through the convention plugins.",
                "Gradle" to "8.14.3 via the wrapper; use the wrapper, not a system Gradle.",
                "Android Gradle Plugin" to "8.7.3, pinned: the installed Android Studio can't open newer AGPs, so raising it is a decision about the whole team's tooling.",
                "Kotlin" to "2.2.10 with KSP2, which is why Room must stay at 2.7 or newer: older Room processors fail on KSP2 with 'unexpected jvm signature V'.",
                "compileSdk / targetSdk" to "36, with android.suppressUnsupportedCompileSdk=36 in gradle.properties, because AGP 8.7 was only validated up to 35.",
                "minSdk" to "29, the floor set by the thermal status APIs and modern Keystore attestation.",
                "Android SDK" to "Platform 36 installed, with sdk.dir in local.properties.",
            ),
        )

        doc.callout(
            "AndroidX is deliberately not on the newest versions",
            "AndroidX ships lint checks inside its artifacts. The 2025-era checks, including Compose runtime's " +
                "RememberInComposition and lifecycle's NullSafeMutableLiveData, are compiled against a newer Kotlin " +
                "analysis API than the lint bundled with AGP 8.7, so they throw IncompatibleClassChangeError partway " +
                "through analysis and abort the build. The versions in libs.versions.toml date from the same time as " +
                "AGP 8.7, which keeps every lint check running instead of suppressing them one by one, so raise AGP " +
                "and the AndroidX block together, never separately.",
        )

        doc.h2("Common tasks")
        doc.code(
            "./gradlew build                  # everything: compile, test, lint, API check",
            "./gradlew test                   # JVM tests across all modules",
            "./gradlew verifyDependencyRules  # layering check on its own",
            "./gradlew apiDump                # after an intentional core/* API change",
            "./gradlew :app:ui:assembleDebug  # the operator console APK",
            "./gradlew :tools:cli:installDist # the verispec launcher",
            "./gradlew :tools:report:generateDocs  # this document and the plain-language manual",
        )
        doc.callout(
            "Note",
            "Never run clean in the same Gradle invocation as build tasks: because build-logic is an included build, " +
                "a plan containing both :build-logic:clean and :build-logic:testClasses deadlocks with \"Unable to " +
                "make progress running work\", even with --no-parallel. Run ./gradlew clean, then ./gradlew build, and " +
                "in Android Studio avoid Build > Rebuild Project, using Build > Clean Project and then Build > Make " +
                "Project instead.",
        )

        doc.h2("Things that will bite you on Windows")
        doc.bullets(
            listOf(
                "sdk.dir in local.properties needs forward slashes. With a backslash-escaped path, AGP throws IOException: The filename, directory name, or volume label syntax is incorrect, and the stack trace never mentions local.properties.",
                "Parallel builds sometimes hit a file lock on data/persistence intermediates, which clears when you run the task again.",
            ),
        )

        doc.h2("The public API surface is part of the contract")
        doc.p(
            "The binary-compatibility validator tracks the public API of the core modules, with the dumps in " +
                "core/*/api/, and apiCheck runs as part of build and fails on any change until you re-run apiDump. The " +
                "friction is intentional: downstream verifiers compile against these types, so changing them should be " +
                "a deliberate act, with a diff somebody reviewed.",
        )

        doc.h2("Reproducible builds")
        doc.p(
            "Archive tasks use a stable file order and no timestamps, because a toolkit that asserts reproducibility " +
                "should build reproducibly itself; anything else would look bad.",
        )

        doc.h2("The optional native build")
        doc.p(
            "The llama.cpp adapter's JNI bridge is opt-in, so the project builds on machines without an NDK. To turn " +
                "it on, install the NDK and CMake, check out llama.cpp, and set both properties:",
        )
        doc.code(
            "verispec.nativeLlama=true",
            "verispec.llamaCppDir=/path/to/llama.cpp",
        )
        doc.p(
            "Without them, the adapter still compiles and reports its backend as unavailable, and a run records that " +
                "as a capability gap. The CMake configuration deliberately sets no vendor-specific tuning flags, and " +
                "GPU backends such as Vulkan are opt-in and recorded in the bundle's system info string.",
        )
    }

    /* ---------------------------------------------------------------- 10 */

    private fun testing(doc: DocPdf) {
        doc.h1("Testing strategy")

        doc.p(
            "The tests sit where a silent failure would cost the most, rather than being spread evenly to hit a " +
                "coverage number.",
        )
        doc.table(
            "Area" to "How it is tested",
            listOf(
                "Canonical serialization" to "Property-tested with Kotest over generated JSON documents: determinism, independence from key insertion order, round-trip stability through a standard parser, no insignificant whitespace, code-point key ordering, and rejection of non-finite numbers.",
                "Bundle round-trip" to "Encode, decode, re-encode and compare bytes; check that the bundle id still equals the hash of the canonical content; validate the encoded document against the published JSON Schema.",
                "Clause catalog" to "Every framework loads, ids are unique, at least one clause references every EvidenceRequirement, and a bundle missing evidence produces named gaps, not silent passes.",
                "Divergence" to "Identical devices agree perfectly; a device that drifts from a known iteration is caught and that iteration is named; bundles from different manifests are refused; two bundles from the same device produce a warning instead of a silent result.",
                "End to end" to "Clause mapping, real ECDSA signing, encode to disk, decode, verify, tamper detection, divergence, then PDF and JSON rendering, where the tamper test changes a signed bundle and asserts that both the hash and the signature checks fail.",
            ),
        )

        doc.h2("Fixtures are shared, not copied")
        doc.p(
            "tools/analysis publishes its bundle fixtures through a Gradle test-fixtures source set, so the CLI's " +
                "end-to-end test builds on the same definition of a plausible bundle instead of a copy that drifts. " +
                "Fixtures compute real canonical hashes, so tests run through production code, not hand-written " +
                "constants that can fall out of step with the encoder.",
        )

        doc.h2("Determinism in the test runner")
        doc.p(
            "JVM test tasks run with a single fork, a fixed UTC timezone and UTF-8 encoding, because a " +
                "canonical-serialization suite that passes in only one timezone is worse than no suite: it teaches " +
                "the team to distrust a red build.",
        )
    }

    /* ---------------------------------------------------------------- 11 */

    private fun stateOfTheWork(doc: DocPdf) {
        doc.h1("Current state and what is next")

        doc.callout(
            "Read this before quoting any number",
            "Everything in this manual is written, compiles and passes its unit tests against fixtures, but only the " +
                "LiteRT adapter has run real models on real hardware: the float32 starter models on one Pixel 6, CPU " +
                "only, and EfficientNet-Lite0 on one Honor Magic V3 and one Samsung Galaxy Tab S7+, with bundles that " +
                "verify on the device and with the CLI. Tensor bundles signed before the 2026-10-27 throughput fix " +
                "carry a meaningless decode rate (output elements per microsecond); since the fix, tensorResult counts " +
                "one decode unit per forward pass, so the figure reads as inferences per second. The precondition gate " +
                "has run on the same Pixel 6 and blocked and timed out as designed, but at room temperature the phone " +
                "levelled off near headroom margin 0.45, so the default of 0.50 was unreachable and the passing run " +
                "used 0.40. That run was VALID; its steady state first came out 'not settled' although throughput was " +
                "flat, because isolated stalls broke the original settled-tail rule, and under the rule as revised on " +
                "2026-11-16 it settles at 21.9 inf/s from iteration 0, 0.9 % below peak. Schema 1.2.0 " +
                "telemetry and the ON_DIMMED screen policy have run on the same phone (2026-11-12, 300 iterations): all " +
                "8 core clocks and the Mali GPU clock were read on every iteration, battery temperature and screen " +
                "state on every power sample, the screen stayed on and visibly dimmed, and /sys/class/thermal was " +
                "recorded as denied. With the screen on, median throughput was 38.6 inf/s against about 22 with it " +
                "off, so you can't compare runs made under different screen policies. Cross-device divergence has run " +
                "once on real hardware (2026-11-14, manifest f945ad90…: EfficientNet-Lite0 FP32, LiteRT CPU with 4 " +
                "threads, 300 iterations, ON_DIMMED, margin 0.35, all unplugged) on that Pixel 6 (Tensor, Android 17), " +
                "an Honor Magic V3 (SM8650, Android 17) and a Samsung Galaxy Tab S7+ (SM8250, Android 13), and all " +
                "three bundles verified and were VALID. The Tab S7+ matched the Pixel's output digests on all 300 " +
                "iterations, as did the Pixel's own 2026-11-12 run and a second Tab S7+ run started after it had " +
                "cooled. The Honor's digests disagreed on every iteration, yet its 64-element logit projections " +
                "agreed with the Pixel's to max L∞ 2.8e-8 (min cosine 0.99999999999942). The bundles cannot show " +
                "whether the top-1 class matched, because they never carry raw outputs. On the Honor no GPU clock node " +
                "is readable, and the live view showed thermal status UNKNOWN while signing although the bundle " +
                "records NONE. The Honor and the Tab S7+ both return BatteryManager " +
                "current in mA, not the documented µA (about 1,000x too small); since 2026-11-16 the analysis infers the " +
                "unit per run (BatteryCurrent) and scales it, giving about 3.0 W for the Honor and 6.1-6.7 W for the " +
                "Tab S7+, where both had read as a few milliwatts. The Tab S7+ (Android 13) exposes 51 thermal zones " +
                "to apps that the Android 17 phones deny, and its steady state, 'not settled' under the original rule, " +
                "is 44.0 inf/s under the revised one. One model on three devices is a first observation, not a " +
                "characterisation. " +
                "Since 2026-11-16 the other three adapters have each run a real model on that Pixel 6, in short functional " +
                "runs (CPU, 4 threads, no gate, USB connected), with bundles that verify against the pinned Google roots: " +
                "ExecuTorch loaded EfficientNetV2-S (XNNPACK fp32 .pte; 30 iterations, median 4.4 inf/s on 0.7.0 and " +
                "4.5 inf/s after the 2026-11-17 upgrade to 1.4.0 with the export made for it; tensor inputs are drawn " +
                "from the seed and the iteration index, so each iteration has its own input and 30 distinct digests is " +
                "the expected result). The upgrade was for 16 KB memory pages: ExecuTorch 0.7.0 and its fbjni shipped " +
                "three native libraries with 4 KB segment alignment, which Android 15 and later flag with a compatibility " +
                "warning at launch. 1.4.0 is 16 KB-aligned, and app/ui now runs check<Variant>NativePageAlignment " +
                "before every assemble, failing the build if any packaged 64-bit library is aligned below 16 KB. " +
                "llama.cpp, compiled for the first time (release b12447, a pinned submodule), ran " +
                "Qwen2.5-0.5B-Instruct Q8_0 GGUF (30 iterations of 64 tokens, median 22.4 tok/s, all digests identical); " +
                "and MediaPipe now stops at maxNewTokens (it used to ignore it) and counts decode tokens with the " +
                "runtime's tokenizer once generation has settled, where it could silently fall back to a word count. " +
                "These are functional checks, not measured envelopes, except for one: a long MediaPipe run of the same " +
                "Qwen model (2026-11-16, manifest 5c8d8b52..., CPU 4 threads, maxNewTokens 128, ON_DIMMED, gate margin " +
                "0.40, unplugged, 500 iterations in 69.6 min after a 21.6 min gate wait). It verified and was VALID; all " +
                "500 output digests were identical and every iteration decoded exactly 128 tokens. Throughput peaked at " +
                "24.6 tok/s, the device reached LIGHT at iteration 13 (1.6 min) and stayed there for 97 % of the run, " +
                "never MODERATE, and throughput settled visibly near 17.6 tok/s (median of iterations 40-379), about " +
                "28 % below peak. Mean discharge power was 4.6 W, about 0.30 J per token, peak PSS 0.9 GB, battery " +
                "temperature 29.5 to 38.2 °C. SustainedLoad still reports no steady state: LLM decode is noisier per " +
                "iteration than tensor inference, and the last window's robust spread (5.8-6.7 %) and drift (up to " +
                "3.7 %) sit just over the 5 % and 3 % limits; whether those limits suit LLM runs is open. Two caveats " +
                "make the tail unrepresentative: the screen timed out at 50.2 min (iteration ~382, PowerManager reports " +
                "reason timeout) and stayed off, and throughput fell to 15.2 tok/s from then on although the fastest " +
                "core clock rose. KeepScreenOnDimmed only holds the display while the Live Run screen is composed and " +
                "in the foreground, and the envelope does not flag a screen-policy change mid-run, so the run still " +
                "reads VALID; both are open findings. " +
                "On 2026-11-17 all four adapters ran on the Honor Magic V3 as well (FCP-N49, SM8650, Android 17; " +
                "30 iterations each, CPU 4 threads, seed 42, no gate, battery not charging; all four bundles verify against " +
                "the pinned roots): ExecuTorch 1.4.0 EfficientNetV2-S median 6.0 inf/s (Pixel 6: 4.5); llama.cpp Qwen Q8_0, " +
                "64 tokens, median 44.5 tok/s, peak 56.7 (Pixel 6: 22.4), with 21 of 30 iterations at LIGHT or worse and " +
                "briefly MODERATE; MediaPipe Qwen int8, maxNewTokens 264, 36 tokens, median 35.9 tok/s, peak 43.9 " +
                "(Pixel 6: 21.1), 18 of 30 at LIGHT; LiteRT EfficientNet-Lite0 median 34.7 inf/s. Both LLM runs produced " +
                "the same output digest as the Pixel 6 on every iteration. The tensor runs repeat the cross-device " +
                "pattern: no digest matches the Pixel's, but the 64-value projections agree to 1.0e-6 (EfficientNetV2-S) " +
                "and 1.9e-8 (EfficientNet-Lite0), and the LiteRT run reproduced the Honor's own 2026-11-14 digests on all " +
                "30 iterations, so its divergence is deterministic, not run-to-run noise. These are functional runs too. " +
                "Every hardware run so far is on the CPU. The first GPU attempt (MediaPipe, Qwen int8 starter, Pixel 6, " +
                "2026-11-18) did reach the GPU: MediaPipe placed 1,303 of 1,360 operations on the Mali GPU through OpenCL " +
                "(ML_DRIFT_CL) and 57 on the CPU, with the GPU clock at 762 MHz. But every generation failed in the native " +
                "detokenizer (DetokenizerCalculator, token id -1), so that model has no GPU result on that phone. The run " +
                "also exposed a bug: MediaPipe 0.10.27 ships an ErrorListener it never calls and returns an empty string " +
                "from the failed generation, so the adapter signed 30 iterations of zero tokens whose digests were the " +
                "SHA-256 of an empty string. GenerationOutcome now fails any uncancelled generation whose future fails, " +
                "and any empty response, cancelled or not; ExecuteRunUseCase now also turns a warm-up failure into " +
                "RunFailure.InferenceFailed (it used to escape the loop). The GPU run now stops at warm-up 1 with no " +
                "bundle, and CPU runs are unchanged (36 tokens with the earlier digest; exactly 16 under a cap of 16). " +
                "ExecuteRunUseCaseTest and GenerationOutcomeTest cover both. No NPU path has run: NNAPI is offered by " +
                "LiteRT only up to Android 14, and QNN and VENDOR_NPU have no adapter. " +
                "On 2026-11-18 the Galaxy Tab S7+ (Android 13, so NNAPI is offered) ran both paths, 30 iterations each, " +
                "no gate, all bundles verifying. LiteRT EfficientNet-Lite0 on NNAPI ran at 84.9 inf/s against 46.1 on " +
                "the CPU with the same settings (the CPU run reproduced the tablet's 2026-11-14 digests 30/30). With " +
                "debug.nn.vlog set, NNAPI listed qti-default, qti-dsp, qti-gpu and qti-hta and placed all 62 operations " +
                "on qti-default. The GPU clock rose to 587-670 MHz from 305 MHz, and the outputs moved from the CPU's " +
                "by up to 1.6e-4 against about 1e-8 between devices on the CPU, which points to the Adreno GPU in reduced " +
                "precision; the DSP and HTA normally take only quantized models and every starter is float32. So that " +
                "is an accelerated run, not an NPU result, and that bundle cannot tell: it records NNAPI, not which NNAPI " +
                "device ran. MediaPipe on the tablet's Adreno GPU failed exactly as on the Pixel's Mali (1,303 of 1,360 " +
                "operations delegated, then the detokenizer error), now stopping at warm-up 1 with no bundle, so the " +
                "fault lies with this model on MediaPipe's GPU path rather than one GPU. MediaPipe on the tablet's CPU " +
                "ran at 24.2 tok/s with the Pixel's output digest. A real NPU run needs an int8 model and a way to name " +
                "the NNAPI device and record it, which schema 1.3.0 now does (see Naming the NNAPI device below). " +
                "Later on 2026-11-18 a OnePlus 8T (Android 14, the same SM8250) repeated the three tests, GPU first, while " +
                "charging and at LIGHT on every iteration: MediaPipe GPU failed in the same way with no bundle; LiteRT on " +
                "NNAPI ran at 70.4 inf/s median against 38.3 on the CPU, again with all 62 operations on qti-default; and " +
                "MediaPipe on the CPU ran at 21.5 tok/s with the Pixel's digest. Its NNAPI outputs equal the tablet's NNAPI " +
                "outputs on all 30 iterations and its CPU outputs equal the tablet's and the Pixel's, so the NNAPI " +
                "difference comes from the driver path, not the device; the OnePlus has no readable GPU clock. " +
                "The first accelerator runs section below charts all of it from the bundles. " +
                "Treat 'it builds' as exactly that and nothing more.",
            java.awt.Color(0.78f, 0.16f, 0.16f),
        )

        doc.h2("Build order and where it stands")
        doc.table(
            "Step" to "Status",
            listOf(
                "1. core/domain and core/evidence with canonical serialization and property tests" to "Done.",
                "2. One backend plus thermal telemetry" to "Done; four adapters written.",
                "3. ExecuteRunUseCase, WorkManager runner, Compose console" to "Done.",
                "4. Attestation and VerifyBundleUseCase" to "Done; bundles are real evidence once signed on a device.",
                "5. Divergence across two physical devices" to "Run once (2026-11-14) on a Pixel 6 (Tensor), an Honor Magic V3 (SM8650) and a Galaxy Tab S7+ (SM8250): Tab S7+ 100 % digest agreement with the Pixel; Honor 0 %, projections within max L∞ 2.8e-8. Not yet repeated, and not yet tried with other models, quantized weights or more SoCs.",
                "6. tools/report PDF output" to "Done.",
                "7. core/clauses and the coverage screen" to "Done: catalog 2026.1, 29 clauses across four frameworks.",
                "8. Additional backends, then fleet orchestration" to "All four adapters have run a real model on a Pixel 6: LiteRT extensively; ExecuTorch, llama.cpp and MediaPipe in short functional runs, plus one 500-iteration MediaPipe run of the Qwen model (VALID, peak 24.6 tok/s, settling near 17.6). On 2026-11-17 all four also ran 30-iteration functional checks on an Honor Magic V3, where both LLM runtimes matched the Pixel's output digests exactly. None of the three has a measured envelope on more than one device.",
            ),
        )

        CrossDeviceFigures.developerSection(doc)
        AcceleratorFigures.developerSection(doc)

        doc.h2("The immediate next step")
        doc.p(
            "The first cross-device run split the devices: the two Armv8.2 SoCs agree bit for bit, while the Armv9.2 " +
                "SoC differs at the 1e-8 level. Next, repeat the three runs to show the split holds, add a second Armv9 " +
                "device to test the ISA hypothesis, and then add a quantized (INT8) model, where you'd expect a bigger " +
                "gap.",
        )

        doc.h2("Two decisions worth revisiting on purpose")
        doc.bullets(
            listOf(
                "Bundle format alignment. Aligning with in-toto or SLSA attestation predicates, instead of a custom envelope, would cost little now and earn recognition from reviewers who already know those formats, and the cost grows with every bundle written.",
                "Revocation and boot state. Chains are pinned to Google's attestation roots, but the verifier does not yet consult Google's revocation list (android.googleapis.com/attestation/status) or read the RootOfTrust verified-boot state, so a revoked attestation key or an unlocked device still verifies. Both are host-side checks; revocation needs network access, which the offline CLI deliberately does not assume.",
            ),
        )
    }

    /* ---------------------------------------------------------------- appendices */

    private fun appendixSchema(doc: DocPdf) {
        doc.pageBreak()
        doc.appendix("A", "Key domain types")
        doc.p(
            "These are the types you meet first, and what each one is for; all of them live in core/domain and carry " +
                "no framework annotations of any kind.",
        )
        doc.table(
            "Type" to "Purpose",
            listOf(
                "RunManifest" to "The run specification, which never changes and whose SHA-256 is its identity.",
                "ModelRef / BackendRef" to "Which model, in what format and quantization; which runtime, version, delegate and thread count.",
                "Workload" to "TextGeneration, which forces greedy decoding, or TensorInference, whose inputs come from the manifest seed.",
                "DeviceProfile" to "Everything observed about the machine at run time; fields that couldn't be read stay null, and nothing is guessed.",
                "Observations" to "Iteration records, thermal and power timelines, memory envelope and output digests.",
                "OutputDigest" to "A hash and an optional fixed-size projection, with no way to carry raw output.",
                "EvidenceBundle" to "The signed artifact, whose unsigned() returns exactly the content that was hashed and signed.",
                "BackendCapabilities" to "What a backend can actually do on this device, including an explicit unavailable state with a reason.",
                "ClauseCoverage" to "Status for each clause, with satisfied and missing requirements and a written gap description.",
                "VerificationReport" to "The result of checking a bundle, where isTrustworthy requires hash, signature, chain and schema all to pass.",
            ),
        )

        doc.h2Plain("Ports the domain defines")
        doc.p(
            "A data module implements each of these and the implementation is injected, while the domain depends " +
                "only on the interfaces, which is why you can test the entire evidence pipeline on a JVM with no " +
                "device attached.",
        )
        doc.code(
            "InferenceBackend        capabilities / load / infer / unload",
            "ThermalTelemetrySource  stream(): Flow<ThermalSample>, halVersion()",
            "PowerTelemetrySource    stream(): Flow<PowerSample>",
            "MemoryTelemetrySource   snapshot(): MemorySnapshot",
            "DeviceProfiler          profile(): DeviceProfile",
            "EvidenceSigner          sign(bytes, challenge), attestationChain(challenge)",
            "SignatureVerifier       verify(bytes, signature, leaf)",
            "AttestationVerifier     verifyChain(chain): ChainResult",
            "EvidenceRepository      save / observeAll / load / export / delete",
            "ClauseCatalog           latestVersion(), catalog(version)",
            "Canonicalizer           manifestBytes(), bundleBytes()",
            "Clock                   epochMillis(), monotonicNanos()",
        )
    }

    private fun appendixGlossary(doc: DocPdf) {
        doc.appendix("B", "Glossary")
        doc.table(
            "Term" to "Meaning in this project",
            listOf(
                "Bundle" to "The signed evidence file from one run on one device.",
                "Manifest" to "The specification of a run: same manifest, same run, on any device.",
                "Delegate" to "The execution path a runtime uses: CPU, GPU, NNAPI, a vendor NPU. The manifest says which one you asked for; the bundle separately records which one actually ran.",
                "Divergence" to "The measured difference between outputs of the same model and input on different hardware.",
                "Decay" to "How much throughput falls over a sustained run, as a percentage from peak to steady state.",
                "Steady state" to "The median throughput of the settled tail of a run (a steady last window, and every earlier window at that level without drifting): the rate the device can sustain, not the headline one, and null when the run never settled.",
                "Precondition gate" to "The device state a run must hold for a stabilization window before the model loads: cool, unplugged, charged and in a fixed screen state.",
                "Headroom" to "The platform's own estimate of thermal margin, where 0 is cool and 1 is the severe-throttling threshold.",
                "Attestation challenge" to "A value baked into a Keystore key's certificate when the key is generated; here it is the manifest hash, which ties the key to one run.",
                "StrongBox" to "A dedicated secure element, stronger than the TEE, which is tried first; if the code falls back, the bundle says so.",
                "Capability gap" to "A backend, delegate or measurement that was unavailable, recorded in the bundle so you can see it is missing.",
                "Canonical bytes" to "The one byte sequence a given piece of content must always serialise to, which is what gets hashed and signed.",
                "Evidence requirement" to "A named kind of evidence, such as DRIVER_PROVENANCE, that clauses reference and bundles either provide or don't.",
            ),
        )

        doc.h2Plain("Where to look first in the code")
        doc.table(
            "Question" to "File",
            listOf(
                "What is a run, exactly?" to "core/domain/model/Manifest.kt",
                "How is a run executed?" to "core/domain/usecase/ExecuteRunUseCase.kt",
                "How are bytes made canonical?" to "core/evidence/canonical/CanonicalJson.kt",
                "How is a bundle signed?" to "data/attestation/KeystoreEvidenceSigner.kt",
                "How is one verified?" to "core/domain/usecase/VerifyBundleUseCase.kt and core/evidence/verify/",
                "How is divergence computed?" to "tools/analysis/Divergence.kt",
                "How is the layering enforced?" to "build-logic/.../DependencyRulesPlugin.kt",
                "What do the clauses say?" to "core/clauses/src/main/resources/clauses/2026.1/",
            ),
        )
    }
}
