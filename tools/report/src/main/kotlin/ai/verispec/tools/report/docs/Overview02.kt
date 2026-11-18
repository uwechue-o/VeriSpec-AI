package ai.verispec.tools.report.docs

import java.io.File
import java.time.LocalDate

/**
 * The short plain-language overview, for readers deciding whether the toolkit
 * matters to them rather than learning to use it. It covers the same fifteen
 * topics, in the same order, as the hand-made overview of September 2026
 * (deleted 2026-11-16; it is in git history), rewritten in a fuller,
 * more conversational voice, with the project status brought up to date.
 *
 *     ./gradlew :tools:report:generateOverview02
 */
object Overview02 {

    @JvmStatic
    fun main(args: Array<String>) {
        val target = File(args.firstOrNull() ?: "DOCS/VeriSpec-AI-Overview-for-Non-Engineers_02.pdf")
        target.parentFile?.mkdirs()
        target.writeBytes(render())
        println("Wrote ${target.absolutePath} (${target.length()} bytes)")
    }

    fun render(): ByteArray {
        val doc = DocPdf("VeriSpec-AI Overview")

        doc.coverPage(
            subtitle = "A plain-language overview of what the toolkit is for, what it produces, and how far it has got.",
            meta = listOf(
                "Toolkit version ${DeveloperManual02.TOOL_VERSION}",
                "Generated ${LocalDate.now()}",
                "",
                "For product leaders, compliance and risk teams, researchers,",
                "people in government and regulated industries, technical",
                "managers, and developers who would like the idea before the code.",
                "",
                "The Manual and the Developer Manual go into the detail;",
                "this is the version you can read in a quarter of an hour.",
            ),
        )

        summary(doc)
        whyItExists(doc)
        whatItRecords(doc)
        theBundle(doc)
        reproducibility(doc)
        sustainedPerformance(doc)
        fleetComparison(doc)
        coverage(doc)
        screens(doc)
        desktopTools(doc)
        runtimes(doc)
        trust(doc)
        workflow(doc)
        whatIsDifferent(doc)
        status(doc)
        longerTerm(doc)
        glossary(doc)

        return doc.finish()
    }

    private fun summary(doc: DocPdf) {
        doc.callout(
            "What it is",
            "VeriSpec-AI is a toolkit, built around an Android app, for checking and writing down how an AI model " +
                "really behaves when it runs on a phone or another small device, rather than on a server somewhere " +
                "in a data centre.",
            java.awt.Color(0.16f, 0.36f, 0.62f),
        )
        doc.h2Plain("The short version")
        doc.p(
            "Running an AI model on a phone has become ordinary, but proving afterwards what actually happened when " +
                "it ran is still surprisingly hard. Someone reviewing the result will want to know which exact model " +
                "file was used, which phone and which processor did the work, which piece of software carried it out, " +
                "whether the phone slowed down as it warmed up, and whether the same model gave noticeably different " +
                "answers on a different make of phone.",
        )
        doc.p(
            "VeriSpec-AI exists to produce that proof. Every test it runs ends in a single, structured record, which " +
                "we call an evidence bundle, holding the model, the device, the software around it, the measurements " +
                "taken, and how all of that lines up against the requirements a reviewer may care about. The bundle " +
                "is digitally sealed, so that anyone can tell later if it has been changed, and the same information " +
                "can be turned into a readable PDF report for people who would rather not open a data file.",
        )
        doc.p(
            "It is worth being clear about what the toolkit does not do: it never declares an AI system " +
                "\"compliant\". Its job is to record what a test genuinely showed, and to make any missing evidence " +
                "plainly visible instead of quietly papering over it, so that the people who do make that judgement " +
                "have something solid to make it from.",
        )
    }

    private fun whyItExists(doc: DocPdf) {
        doc.h1("Why VeriSpec-AI exists")
        doc.p(
            "More and more organisations are putting AI directly onto the devices they ship, whether those are " +
                "phones, vehicles, medical equipment or machinery on a factory floor. The trouble is that the same " +
                "model can behave differently from one device to the next, depending on the processor and any AI " +
                "chip it has, the drivers and runtime software installed, how hot the device is, how much memory is " +
                "free, and how the model was compressed to fit.",
        )
        doc.p(
            "An ordinary benchmark answers a narrow question, usually \"how fast did this model run?\", and stops " +
                "there. VeriSpec-AI is aimed at a broader one that matters far more to anyone accountable for the " +
                "result: could another person check what was tested, under what conditions, and what the results " +
                "really showed, without having to take our word for it?",
        )
        doc.p(
            "That question comes up wherever AI behaviour has to be written down for someone else to rely on: in " +
                "internal governance, in assurances given to customers, in audits and safety reviews, in regulatory " +
                "submissions, and in organisations that look after large fleets of devices.",
        )
    }

    private fun whatItRecords(doc: DocPdf) {
        doc.h1("What the app records")
        doc.p(
            "While a test runs, the app writes down the exact model file, the Android device and its processor, the " +
                "operating-system and security details, the AI runtime and the execution path it actually used, the " +
                "drivers where the phone is willing to reveal them, how much memory was used, how the device's power " +
                "and temperature behaved, and the outcome of every single repetition of the test.",
        )
        doc.p(
            "It also records what was not available. If you asked for a particular AI chip, for instance, and the " +
                "work had to fall back to the ordinary processor instead, that fact is kept in the record rather than " +
                "silently dropped, because a reviewer needs to know which path really produced the numbers.",
        )
        doc.p(
            "One thing the app deliberately leaves out is the AI's raw output. Instead of the text or values the " +
                "model produced, it keeps a compact mathematical fingerprint of them, which is enough to tell whether " +
                "two devices gave the same answer but not enough to reconstruct the answer itself. That way, " +
                "anything sensitive that might have crept into the model's input does not end up copied into files " +
                "that are later handed to reviewers.",
        )
    }

    private fun theBundle(doc: DocPdf) {
        doc.h1("The evidence bundle")
        doc.p(
            "Each completed test on one device produces one evidence bundle, which you can think of as a " +
                "tamper-evident laboratory record of an AI test: everything needed to understand the test is in it, " +
                "and any later change to it shows.",
        )
        doc.p(
            "Inside are the specification of the test, a profile of the device, where the model and the runtime " +
                "came from, the performance and temperature readings from every repetition, how the evidence covers " +
                "the relevant requirements, and a digital signature over the whole thing. The machine-readable file " +
                "is the authoritative record, and the readable PDF is simply produced from the same information.",
        )
        doc.p(
            "Every bundle is named by a fingerprint of its own contents, so if a single measurement is changed " +
                "afterwards the name no longer matches and checking the bundle reveals the alteration. On top of " +
                "that, VeriSpec-AI uses the secure hardware built into Android phones to tie the evidence to the " +
                "particular device, and the particular test, that produced it.",
        )
    }

    private fun reproducibility(doc: DocPdf) {
        doc.h1("Reproducibility: making comparisons mean something")
        doc.p(
            "One of the central design goals is that the very same test can be repeated on many devices. To make " +
                "that possible, VeriSpec-AI fixes the inputs and the important model settings in advance, so that " +
                "two devices are given precisely the same work to do rather than something merely similar.",
        )
        doc.p(
            "Because the work is identical, the toolkit can then compare what different processors and execution " +
                "paths made of it: whether their outputs agree, by how much they differ when they do not, and at " +
                "which point in the test the differences first appear.",
        )
        doc.p(
            "This matters because AI arithmetic, and especially the compressed arithmetic used to fit models onto " +
                "phones, is not guaranteed to give identical results on chips from different manufacturers. Rather " +
                "than pretending those differences do not exist, VeriSpec-AI turns them into measured evidence that " +
                "somebody can look at and decide about.",
        )
    }

    private fun sustainedPerformance(doc: DocPdf) {
        doc.h1("Sustained performance and heat")
        doc.p(
            "VeriSpec-AI does not stop at a short burst of speed. It can run a model over and over for a long " +
                "stretch while recording how fast it goes, how warm the device is, how much power it draws, and how " +
                "much memory it uses, so that the whole story of the test is on record rather than just its best " +
                "moment.",
        )
        doc.p(
            "That is what lets it separate an impressive peak from the speed a device can actually keep up. Many " +
                "devices start quickly and then slow down noticeably as they heat, and VeriSpec-AI records when that " +
                "slowdown begins, how far it goes, and how hot the device was at the time.",
        )
        doc.p(
            "While a test is running, a live view in the app shows the speed changing and any heat-related " +
                "slowdowns as they happen, so the person running it can see what the device is going through.",
        )
    }

    private fun fleetComparison(doc: DocPdf) {
        doc.h1("Comparing a fleet of devices")
        doc.p(
            "The same test can be run on several physical devices, and VeriSpec-AI groups together the results " +
                "that share an identical test specification so that they can be compared side by side as a fleet.",
        )
        doc.p(
            "An organisation might, for example, run one model with one fixed workload on phones built around " +
                "Snapdragon, Tensor, Exynos and MediaTek processors, then gather the bundles onto an ordinary " +
                "computer and compare them there, looking both for differences in performance and for differences in " +
                "what the model produced.",
        )
        doc.p(
            "The point is to have a footing for repeatable comparisons across devices, instead of relying on " +
                "scattered screenshots or benchmark numbers someone wrote down by hand.",
        )
    }

    private fun coverage(doc: DocPdf) {
        doc.h1("Evidence coverage and regulatory support")
        doc.p(
            "VeriSpec-AI comes with a versioned catalogue that maps the evidence it collects onto selected " +
                "requirements from standards and regulations. Its purpose is to show which requirements the available " +
                "evidence supports and, just as importantly, which ones it does not fully support.",
        )
        doc.p(
            "The app goes out of its way to point out gaps. A measurement that could not be taken, or a capability " +
                "the device did not have, is reported as exactly that, rather than being quietly read as a pass.",
        )
        doc.p(
            "All of this makes VeriSpec-AI a tool for producing evidence and supporting assurance, not a machine " +
                "that hands out legal or regulatory certificates. Deciding whether a whole product, or a whole " +
                "organisation, meets a particular standard remains the job of people with the standing to make that " +
                "call.",
        )
    }

    private fun screens(doc: DocPdf) {
        doc.h1("What you see in the app")
        doc.table(
            "Screen" to "What it is for",
            listOf(
                "Run Composer" to "Where you set up a repeatable test: the model, the work it will do, the runtime, and the conditions it should run under. If you have no model of your own, you can pick one of the built-in starter models and try the app straight away.",
                "Live Run" to "Shows a test while it runs: how far it has got, how the speed is changing, how warm the device is, and when it starts slowing itself down because of heat.",
                "Bundles" to "Keeps the evidence produced on this device, or brought in from elsewhere, together with its signature and the details of where everything came from.",
                "Clause Coverage" to "Shows which requirements the evidence supports and makes plain which ones it does not.",
                "Fleet" to "Lines up results from different devices that ran the same test, for a quick comparison.",
                "Verify" to "Checks a bundle on its own terms: whether its contents are unchanged, whether its signature holds, and whether the device's security certificates back it up.",
            ),
            leftFraction = 0.26f,
        )
    }

    private fun desktopTools(doc: DocPdf) {
        doc.h1("Companion tools for an ordinary computer")
        doc.p(
            "The Android app does the testing on the device, but VeriSpec-AI also includes tools that run on a " +
                "normal computer, for gathering results from several test devices, checking bundles, comparing what " +
                "the devices produced, and writing reports.",
        )
        doc.p(
            "The more detailed comparison between devices is done on the computer on purpose, so that no device " +
                "ever ends up marking its own homework, and so that anyone holding the collected evidence can analyse " +
                "it independently of the phones that produced it.",
        )
        doc.p(
            "The same tools produce the readable PDF report that is used to review or share a finished set of " +
                "evidence.",
        )
    }

    private fun runtimes(doc: DocPdf) {
        doc.h1("The AI engines it works with")
        doc.p(
            "VeriSpec-AI has connections, which we call adapters, for four widely used ways of running AI on a " +
                "device: LiteRT, llama.cpp, ExecuTorch and MediaPipe. The design keeps these separate from the rules " +
                "about evidence, so that further engines can be added later without touching how evidence is " +
                "recorded, sealed or checked.",
        )
        doc.p(
            "Each engine is expected to say honestly what it can do on the device in front of it. When an engine, an " +
                "AI chip or a particular measurement is not available, VeriSpec-AI records that limitation as part of " +
                "the evidence rather than carrying on as if nothing were missing.",
        )
    }

    private fun trust(doc: DocPdf) {
        doc.h1("Trust, privacy and spotting tampering")
        doc.p(
            "VeriSpec-AI treats trustworthiness as a core requirement rather than a feature to add later. Every " +
                "test definition gets a stable identity, evidence is always written out in the same consistent form, " +
                "and every finished bundle is cryptographically signed.",
        )
        doc.p(
            "Where the phone supports it, the signature is made by a key that lives inside the phone's own security " +
                "hardware and is created for that one test, and the certificates that come with it are checked " +
                "against Google's published root certificates for genuine Android security hardware. If the " +
                "strongest form of that hardware is not available and a weaker one has to be used, the downgrade is " +
                "written into the record instead of being hidden. One honest caveat remains: the toolkit does not yet " +
                "check whether Google has since withdrawn a phone's certificates, or whether the phone's software had " +
                "been unlocked or modified.",
        )
        doc.p(
            "Privacy is built into the evidence format as well. Because the model's raw output is never stored in " +
                "the bundle, there is much less chance of sensitive content travelling along inside files that are " +
                "later shared with reviewers.",
        )
    }

    private fun workflow(doc: DocPdf) {
        doc.h1("An example from the real world")
        doc.p(
            "Picture a medical-device company, an automotive supplier, a defence contractor or an industrial " +
                "manufacturer that is putting the same on-device AI model onto several approved hardware platforms " +
                "and needs to be able to stand behind how it behaves on each of them.",
        )
        doc.p(
            "The organisation starts by defining one controlled VeriSpec-AI test, which is then run on each of the " +
                "devices in turn. Each device produces its own signed evidence bundle, and the bundles are collected " +
                "onto a computer and checked. VeriSpec-AI then compares how well each device sustained its " +
                "performance and how the model's outputs compared across the fleet, and it sets out which evidence " +
                "requirements are supported and which gaps remain.",
        )
        doc.p(
            "What comes out at the end is a record that can be reproduced, and that can support engineering " +
                "reviews, quality processes, assurance work, audit preparation and technical documentation.",
        )
    }

    private fun whatIsDifferent(doc: DocPdf) {
        doc.h1("What makes VeriSpec-AI different")
        doc.p(
            "VeriSpec-AI brings together several activities that are usually handled separately, often by " +
                "different teams with different tools: recording where the model and the device came from, " +
                "running repeatable AI tests, measuring performance under sustained load and heat, comparing outputs " +
                "across devices, producing evidence that shows any tampering, and mapping that evidence onto " +
                "requirements.",
        )
        doc.p(
            "Its central idea is that trustworthy AI on devices takes more than showing that a model can run. " +
                "Organisations need a way to demonstrate what actually ran, where it ran, how it behaved, and what " +
                "the available evidence does, and does not, prove.",
        )
    }

    private fun status(doc: DocPdf) {
        doc.h1("Where the project stands")
        doc.p(
            "This is still an early version, but it has moved well beyond code tested only on made-up data. The " +
                "evidence system, the Android app, the four engine adapters, the temperature and power readings, " +
                "signing and checking, the reports and the requirement catalogue (twenty-nine requirements across " +
                "four frameworks) are all written and pass their automated tests, and several of them have now " +
                "worked on real phones.",
        )
        doc.p(
            "The LiteRT engine has run small real models on a Google Pixel 6, producing sealed results that check " +
                "out. In November 2026 the same image-recognition test was run 300 times on three phones with three " +
                "different chips, a Pixel 6, an Honor Magic V3 and a Samsung Galaxy Tab S7+: the tablet's answers " +
                "matched the Pixel's exactly on every repetition, while the Honor's differed on every repetition, " +
                "though only by about three parts in a hundred million. The other three engines have since each run " +
                "one real model on the Pixel 6, in short trials that show they work rather than how well they " +
                "perform, and the built-in language model has had one long test, running for over an hour on an " +
                "unplugged Pixel 6, in which it gave the same answer all 500 times and slowed from about 24 to " +
                "about 18 pieces of words a second as the phone warmed up. All four have since been tried on an " +
                "Honor Magic V3 as well, where the language model ran about twice as fast and gave exactly the " +
                "same answers as on the Pixel. In the first tries away from the main processor, on a Samsung tablet " +
                "and a OnePlus 8T phone, the language model failed on the graphics processor, and the app now stops " +
                "cleanly when that happens; Android's route to AI accelerators ran the image model almost twice as " +
                "fast, but went to a general-purpose path rather than the dedicated AI chip, so nothing has run on " +
                "an AI chip yet.",
        )
        doc.p(
            "That distinction matters, and it is the reason this section exists. A first comparison of one model " +
                "on three devices is an observation, not a general finding about any of them, and none of the " +
                "figures above should be quoted as a verdict on a device or an engine until the tests have been " +
                "repeated and widened to more models and more hardware.",
        )
    }

    private fun longerTerm(doc: DocPdf) {
        doc.h1("Where it could go")
        doc.p(
            "If it continues to hold up on real hardware, VeriSpec-AI could grow into a reusable, open foundation " +
                "for AI assurance on Android and other small devices. It could be used to qualify a fleet of " +
                "devices, to catch regressions after a model or runtime update, to compare hardware, to generate " +
                "evidence automatically as part of a software release process, to document regulated products, and " +
                "to support independent research into how AI behaves on real devices.",
        )
        doc.p(
            "Its real long-term value, then, is not as yet another Android AI app, but as infrastructure for " +
                "producing evidence about AI behaviour on real-world devices that other people can check and repeat " +
                "for themselves.",
        )
    }

    private fun glossary(doc: DocPdf) {
        doc.h1("A plain-language glossary")
        doc.table(
            "Term" to "What it means here",
            listOf(
                "Evidence bundle" to "The signed record produced by one test on one device.",
                "Manifest" to "The fixed recipe for a test: the model, its settings, the work it will do, and the conditions it runs under.",
                "Runtime, or engine" to "The software that actually runs the AI model.",
                "Delegate, or accelerator" to "The piece of hardware doing the AI work, such as the main processor (CPU), the graphics chip (GPU) or a dedicated AI chip (NPU).",
                "Provenance" to "Where everything came from: exactly which model, software, device and drivers were involved.",
                "Divergence" to "A measurable difference between the results that the same test produced on different hardware.",
                "Thermal throttling" to "A device deliberately slowing itself down because it is getting too hot.",
                "Attestation" to "Security evidence, issued by the phone's hardware, that helps establish which device and which protected key produced a signature.",
                "Capability gap" to "A feature, engine, AI chip or measurement that was not available during a test, and is recorded as such.",
            ),
            leftFraction = 0.28f,
        )
    }
}
