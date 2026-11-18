package ai.verispec.tools.report.docs

import java.io.File
import java.time.LocalDate

/**
 * The plain-language manual, for non-engineers, in a direct, peer-to-peer
 * voice. It replaced the original edition (GeneralManual, retired 2026-11-16)
 * and keeps the _02 file name.
 *
 *     ./gradlew :tools:report:generateManual02
 */
object GeneralManual02 {

    @JvmStatic
    fun main(args: Array<String>) {
        val target = File(args.firstOrNull() ?: "DOCS/VeriSpec-AI-Manual_02.pdf")
        target.parentFile?.mkdirs()
        target.writeBytes(render())
        println("Wrote ${target.absolutePath} (${target.length()} bytes)")
    }

    fun render(): ByteArray {
        val doc = DocPdf("VeriSpec-AI Manual")

        doc.coverPage(
            subtitle = "What the toolkit does, what its results mean, and how to use it, written for readers who don't work on the code.",
            meta = listOf(
                "Toolkit version ${DeveloperManual02.TOOL_VERSION}",
                "Evidence format 1.2.0  ·  regulatory catalogue 2026.1",
                "Generated ${LocalDate.now()}",
                "",
                "For regulatory and quality staff, product managers, auditors,",
                "and engineers new to the project.",
                "",
                "The Developer Manual covers the same ground at the level of",
                "the code; this isn't a summary of it, just the same coverage",
                "in plainer language.",
            ),
        )
        doc.contentsPlaceholder()

        theProblem(doc)
        vocabulary(doc)
        theBundle(doc)
        howItIsBuilt(doc)
        runningATest(doc)
        screens(doc)
        readingResults(doc)
        checkingGenuine(doc)
        regulatory(doc)
        fleet(doc)
        limits(doc)
        questions(doc)
        glossary(doc)

        return doc.finish()
    }

    /* ---------------------------------------------------------------- 1 */

    private fun theProblem(doc: DocPdf) {
        doc.h1("The problem this solves")
        doc.lede("Anyone can run an AI model on a phone, but almost nobody can prove how it behaves once it's out in the world.")

        doc.p(
            "Putting a model on a phone is ordinary engineering now, but proving how that model behaved across ten " +
                "thousand different handsets, to someone who won't just take your word for it, is not. No widely " +
                "accepted document answers that question and little tooling produces one, so an outside party has no " +
                "easy way to check an answer they're handed.",
        )
        doc.p(
            "That gap is about to become a legal problem. Under the EU AI Act as amended by the Digital Omnibus, the " +
                "obligations for high-risk AI systems start to apply on 2 December 2027 for standalone systems, and on " +
                "2 August 2028 for AI built into regulated products such as medical devices and machinery. The European " +
                "Commission gave its reason for the delay plainly: the tools and procedures for assessing conformity, " +
                "which the law assumes already exist, hadn't matured yet.",
        )
        doc.p(
            "VeriSpec-AI tries to fill part of that gap by running a model across a set of devices and producing a " +
                "file that records what actually happened, in a form someone else can check.",
        )

        doc.h2("What you can say afterwards")
        doc.p(
            "This isn't a speed test. A speed benchmark answers one question, once, on one device, in a way nobody can " +
                "repeat, whereas VeriSpec-AI gives you a record that supports a narrower set of claims, and those claims " +
                "hold up when you hand them to a reviewer:",
        )
        doc.bullets(
            listOf(
                "This exact version of this model ran on this exact hardware, both identified precisely rather than by name.",
                "It ran on this version of the AI software and this driver, as found on the device, not as someone assumed.",
                "Under continuous use it slowed down by this much, starting at this point, at this temperature.",
                "Given the same input, the model gave the same answer on device A as on device B, or it didn't, and here's the measured difference.",
                "The evidence above supports these specific regulatory requirements, and not these others.",
            ),
        )

        doc.h2("Why the last point matters most")
        doc.callout(
            "A tool that only shows green is a liability",
            "It's easy to build something that reports success while quietly leaving out what it didn't check, so " +
                "that everyone involved believes a gap has been closed when it has only been hidden, and that's worse " +
                "than having no tool at all. So wherever this toolkit shows what's covered, it shows what isn't covered " +
                "just as prominently, names the missing item, and never leaves a blank where a gap belongs.",
        )

        doc.h2("Why the results can be trusted across vendors")
        doc.p(
            "Measurements are only worth something if a manufacturer whose hardware looks poor in them can't " +
                "reasonably dismiss the method, so fairness is built into the structure rather than promised in a policy:",
        )
        doc.bullets(
            listOf(
                "Every piece of AI software under test goes through one identical measurement procedure, with the same timer and the same sensors, and no vendor gets its own measurement code.",
                "The parts that connect to each vendor's software can't influence how results are timed or recorded: they load a model and return an answer, and everything else is shared.",
                "If a device can't run something, the record says so as a stated limitation, rather than the test quietly skipping it or crashing.",
                "Any vendor can contribute fixes to its own connection code, but changes that would give one vendor special treatment in the shared measurement path are refused.",
            ),
        )
    }

    /* ---------------------------------------------------------------- 2 */

    private fun vocabulary(doc: DocPdf) {
        doc.h1("The vocabulary you need")
        doc.lede("Nine terms carry most of the meaning here, and from this point on each one means exactly what this table says.")

        doc.table(
            "Term" to "What it means here",
            listOf(
                "Model" to "The trained AI system itself, as a specific file rather than a product name, so if two builds of \"the same\" model differ by a single byte, they're different models to this toolkit.",
                "Quantization" to "Shrinking a model by storing its internal numbers less precisely, so it fits and runs on a phone. You give up a little accuracy for a big gain in speed and memory, and how much accuracy is the interesting question, which the toolkit measures.",
                "Runtime" to "The software that actually runs the model on the device. Several exist, from different vendors, and the same model can behave differently on each.",
                "Delegate" to "Which part of the chip does the work: the main processor, the graphics processor, or a dedicated AI accelerator. The same model on the same phone can give different answers, and very different speeds, depending on this.",
                "SoC" to "System on a chip: the main processor package in a phone. Different manufacturers' chips do arithmetic in subtly different ways, and that's the root of much of what this toolkit measures.",
                "Throttling" to "A hot phone deliberately slowing itself down, to avoid damage and to stay comfortable to hold, which any test longer than a minute or two will run into.",
                "Hash" to "A short fingerprint calculated from a file's contents, which changes completely if you change one character anywhere in the file. You can't work the fingerprint backwards into the file, and it's how the toolkit identifies things exactly.",
                "Signature" to "An electronic seal applied by a key kept inside the phone's security hardware, which anyone can check but only that phone could have applied.",
                "Evidence bundle" to "The output of one test on one device: a single file holding everything recorded, sealed so that any later change shows.",
            ),
        )

        doc.p(
            "Two of these trip people up more than the rest. First, \"the same model\" is a claim about file contents, " +
                "not about a product name or a version number. Second, quantized models aren't guaranteed to give " +
                "identical answers on different manufacturers' chips, which follows from how the arithmetic is " +
                "implemented; it's barely documented in public, and measuring it is one of the main reasons this " +
                "toolkit exists.",
        )
    }

    /* ---------------------------------------------------------------- 3 */

    private fun theBundle(doc: DocPdf) {
        doc.h1("What the toolkit produces")
        doc.lede("One test on one device gives you one evidence bundle: a sealed file that describes itself.")

        doc.p(
            "The bundle is a text file in a standard, published format, so both other software and a person can read " +
                "it, and a companion PDF lays the same content out for a submission binder. The text file is the " +
                "record; the PDF is just a view of it.",
        )

        doc.h2("What's inside")
        doc.table(
            "Part" to "What it records, and why",
            listOf(
                "The test specification" to "Exactly what was asked for: which model, which software, which part of the chip, how many repetitions, and the starting conditions. It carries its own fingerprint, so two tests specified identically are provably the same test.",
                "The device record" to "What the machine actually was (manufacturer, model, chip, driver versions, operating system release and security update level, memory), read from the device at the time and never copied from a spreadsheet.",
                "Origin details" to "The model's fingerprint, the AI software's version as it reported itself, which part of the chip was requested, and which part actually did the work, two things that differ more often than you'd expect.",
                "The measurements" to "For every repetition, how long it took, how fast it produced results and how hot the device was, alongside temperature and battery readings over time, peak memory use, and a fingerprint of each answer.",
                "Regulatory coverage" to "Which requirements the above supports, which it doesn't, and a written explanation of every shortfall.",
                "The seal" to "The electronic signature, plus certificates from the phone's manufacturer proving that the sealing key really lived in that phone's security hardware and was created for this test alone.",
            ),
        )

        doc.h2("What's deliberately left out")
        doc.callout(
            "The bundle never contains the model's actual answers",
            "It stores only fingerprints of them, plus a small set of summary numbers, and the reason is privacy: what " +
                "a model produces can reveal what went into it, and evidence files are exactly the kind of document " +
                "that gets forwarded around an organisation and sent to outside reviewers. The file format has nowhere " +
                "to put the text of an answer, so no later change of mind or configuration mistake can undo this.",
        )
        doc.p(
            "The fingerprints answer the question that matters, which is whether two devices gave the same answer, and " +
                "the summary numbers measure how far apart they were when they didn't. Neither can be turned back into " +
                "the original content.",
        )

        doc.h2("Why the file can't be quietly edited")
        doc.p(
            "The bundle is named by a fingerprint of its own contents, and the electronic seal covers those same " +
                "contents, so if you change one measurement afterwards, however small, the fingerprint stops matching " +
                "and the seal stops verifying. The checking software reports this as a failure and tells you which " +
                "check failed, rather than just refusing to open the file.",
        )
        doc.p(
            "The seal proves more than \"unaltered\": the key that applied it was created inside the phone's security " +
                "hardware for this one test, and the manufacturer's certificates say so, which means a key from one " +
                "test can't be reused to bless another.",
        )
    }

    /* ---------------------------------------------------------------- 4 */

    private fun howItIsBuilt(doc: DocPdf) {
        doc.pageBreak()
        doc.h1("How the system is put together")
        doc.lede("An app on the device, tools on a computer, and a deliberate line between them.")

        doc.h2("The four parts")
        doc.table(
            "Part" to "What it's responsible for",
            listOf(
                "The operator console" to "An Android app you use to describe a test, start it, watch it run, and look at the evidence it produces. It's deliberately plain, an instrument rather than a consumer product.",
                "The test engine" to "The part of the app that runs the test. It keeps going with the screen off, survives the system closing the app, and saves each repetition as it finishes, so an interruption near the end doesn't lose the earlier work.",
                "The evidence core" to "The rules for what a test is, what gets recorded, how the file is written, and how the seal is applied and checked. A technical reviewer at a regulator could read this part, so it's kept free of anything specific to Android or to a vendor.",
                "The host tools" to "Programs that run on an ordinary computer to collect bundles from a set of devices, compare them, check seals, and produce the PDF report.",
            ),
        )

        doc.h2("Why comparison happens on a computer, not on a phone")
        doc.callout(
            "This split is deliberate",
            "You can't compare results between devices on any one of those devices, and doing it on a separate " +
                "computer means no device ever grades its own work. The app shows a quick summary when it happens to " +
                "hold results from several devices, but the comparison in a report is always produced separately.",
            java.awt.Color(0.07f, 0.20f, 0.23f),
        )

        doc.h2("Supporting several AI runtimes")
        doc.p(
            "The toolkit supports four AI runtimes, from Google and others, including the widely used open format " +
                "that Google's own tools don't handle. Each one connects through the same narrow interface, so adding " +
                "a fifth neither disturbs the other four nor changes how anything is measured.",
        )
        doc.p(
            "Before a test starts, the app asks each runtime what it can actually do on this particular device rather " +
                "than assuming, and a runtime that isn't installed, or a chip feature that isn't there, becomes a " +
                "recorded limitation in the evidence. That matters more than it sounds, because a missing capability " +
                "that's written down is a finding, while one that just produces no data is invisible.",
        )
    }

    /* ---------------------------------------------------------------- 5 */

    private fun runningATest(doc: DocPdf) {
        doc.h1("Running a test")
        doc.lede("What you do, and what the system does in response.")

        doc.numbered(
            listOf(
                "Describe the test by choosing the model file, the runtime, which part of the chip to use, how many repetitions, and how long to run. Every choice sits on one screen, because every choice is part of the test's identity.",
                "Bring in the model. Tap Import model file and the app copies the file into its own storage, fingerprinting it on the way in, so the test names the precise file rather than a location that might change later. You don't need a model file of your own to try the app: pick one from Or use a starter model. These are a few default models covering each of the toolkit's AI engines, including one small language model for trying out text generation, in two versions so the same model can be tried on two engines (about 520 and 645 MB). The app downloads the one you choose and refuses it unless its fingerprint matches the one written into the app. On a Pixel 6 the language model has had a short trial on each of its two engines and one long test of an hour and ten minutes, 500 repetitions, in which it gave exactly the same answer every time. It wrote about 24 tokens a second at its best and settled near 18 once the phone warmed up. That is one phone and one model, so it shows how this setup behaved, not how the model performs in general.",
                "Let the app check the test before it starts, confirming that the test can be repeated and that this device can do what you've asked. Problems that would make the evidence worthless stop the test, while lesser concerns show up as warnings and don't.",
                "Wait for the right starting state. By default, Wait for start conditions holds the test until the phone is cool, unplugged, at least half charged and has its screen off, and has stayed that way for thirty seconds, because a warm or charging phone measures something different from a cool one. If those conditions aren't reached within fifteen minutes the test doesn't run, unless you turned on If not met in time, run anyway and mark it, in which case it runs and the results are marked as not comparable.",
                "If you need the screen on, set Screen: ON_DIMMED, and the app keeps the screen on, as dim as it goes, for the whole test. The option exists because some phones slow their graphics chip when the screen goes off.",
                "The app examines the device, reading the chip, driver versions, operating system, security update level and memory from the device itself, at that moment.",
                "The test runs, with warm-up repetitions first that don't count, then the measured repetitions. Temperature, battery and screen state are sampled about once a second, and the speed of the processor and graphics chip is noted after every repetition where the phone allows it, while the results list which of these readings the phone wouldn't provide.",
                "Results are saved as you go, each repetition written to storage the moment it finishes, so if something interrupts the test at repetition forty-seven, you still have the first forty-six.",
                "Coverage is worked out: the system looks at what was actually recorded, compares it against the regulatory catalogue, and lists what's supported and what's missing.",
                "The bundle is assembled and sealed, with everything written in the standard format, fingerprinted, and sealed with a key created in the phone's security hardware for this specific test.",
            ),
        )
        doc.callout(
            "Note: unplug the phone, and expect the wait",
            "A phone on a charging cable will never pass the start conditions, because charging adds heat, and in a " +
                "warm room a phone may also never cool to the default level within the fifteen-minute limit. That " +
                "happened on our own Pixel 6; see Limits and current status.",
        )
        doc.callout(
            "Note: keep VeriSpec open for a dimmed-screen test",
            "With Screen: ON_DIMMED, the app can only hold the screen on while it's in the foreground, so if you switch " +
                "to another app the screen may turn off partway through. The results record whether the screen really " +
                "stayed on, so check that before you trust the run.",
        )

        doc.h2("Why tests are repeatable")
        doc.p(
            "A result nobody can reproduce is an anecdote, and three design choices make repetition possible:",
        )
        doc.bullets(
            listOf(
                "The test specification is fixed and fingerprinted, so there's no doubt about what was run.",
                "When the model generates text, the system makes it pick its most likely choice at every step, never a random one, because random answers can't be compared between devices.",
                "When the model takes numerical input, that input is generated from a starting number written into the test specification, by a method simple enough that you could reproduce the same inputs in another programming language without this software at all.",
            ),
        )

        doc.h2("Heat, and being honest about our own effect")
        doc.p(
            "Sustained tests run for tens of minutes and the device will get hot, so the app keeps the processor awake " +
                "throughout and the test carries on with the screen off. By default it doesn't keep the screen on, so " +
                "the power readings reflect the AI work rather than the display. When a test requires the screen on, " +
                "the app holds it at minimum brightness instead, and the results record whether it really stayed on. " +
                "All of this is written down because it changes what the power figures mean.",
        )
        doc.p(
            "Throttling is reported at two levels. The report counts every repetition at which the device had started " +
                "slowing itself for heat, including the mildest level the platform reports, and separately counts " +
                "those at which the device was meaningfully cutting performance. The first tells you when heat started " +
                "to matter, and the second how much of the test was seriously affected.",
        )
    }

    /* ---------------------------------------------------------------- 6 */

    private fun screens(doc: DocPdf) {
        doc.h1("Using the app, screen by screen")

        doc.table(
            "Screen" to "What it's for",
            listOf(
                "Run Composer" to "Describe a test and check it before you commit to it. It shows which runtimes this device actually offers, imports and fingerprints the model file, and reports errors and warnings without starting anything. Every field is visible, with nothing hidden behind an advanced setting, because every field affects the test's identity.",
                "Live Run" to "Watch a test in progress. A chart plots speed against repetition number and marks throttled repetitions, so you see heat slowing the device as it happens rather than afterwards, and leaving this screen doesn't interrupt or lose the test.",
                "Bundles" to "The evidence held on this device, both made here and imported from elsewhere, with each entry showing whether its seal has been checked.",
                "Bundle Detail" to "Everything recorded in one bundle (origins, device, the full speed and heat picture, and the certificates behind the seal), with one action that re-checks the seal and records the result. Labelled charts show speed for each repetition and, where the phone let them be read, processor speed, battery temperature and heat headroom over the test.",
                "Clause Coverage" to "Which regulatory requirements this evidence supports, grouped by framework, with shortfalls first and a count of unmet requirements at the top.",
                "Fleet" to "Results grouped by test, so the same test on different devices lines up side by side, with a quick sign of whether the devices agreed.",
                "Verify" to "Open an evidence file from anywhere, including someone else's device, and check it using nothing but the file.",
            ),
        )

        doc.h2("Two lines on Bundle Detail worth understanding")
        doc.p(
            "These are the two that surprise people most. The first shows which part of the chip was requested and " +
                "which one actually did the work, because when an AI accelerator declines a model, the work quietly " +
                "moves to the main processor and the performance figures then describe something other than what you " +
                "asked for. The second is the driver version: if the device doesn't report one, the record says so " +
                "rather than leaving the field blank, because \"not reported\" and \"not present\" are different findings.",
        )

        AppScreenFigures.plainSection(doc)

        doc.h2("Getting evidence off the device")
        doc.p(
            "To export an evidence file, use the normal Android file picker; from there it can go to cloud storage, " +
                "email or a cable. The formatted PDF report is made on a computer, not on the phone, because you " +
                "normally make it once at the end of a testing campaign, not on each device.",
        )
    }

    /* ---------------------------------------------------------------- 7 */

    private fun readingResults(doc: DocPdf) {
        doc.h1("Reading the results")
        doc.lede("What the numbers mean, and what a reasonable person should conclude from them.")

        doc.h2("Speed and slowdown")
        doc.table(
            "Figure" to "How to read it",
            listOf(
                "Peak speed" to "The best the device sustained over five repetitions in a row, usually early on while it's still cool, so one unusually fast repetition doesn't count. On its own it's the least useful number in the report, and the one marketing quotes most.",
                "Steady-state speed" to "The typical speed once the device has settled at its sustainable temperature and speed has stopped changing, which for anything that runs continuously is the honest figure. If speed was still falling when the test ended, no steady state is reported, because the toolkit doesn't guess one.",
                "Validity" to "Whether you can compare this result with others. VALID means the test started from the required conditions and ran long enough, while results marked otherwise are still recorded and say why they're not comparable.",
                "Decay" to "How far speed fell from peak to steady state, as a percentage. A large figure doesn't mean a faulty device; it means the peak was never sustainable.",
                "Time to first slowdown" to "How long the device ran before it began deliberately cutting performance. \"Never\" is a genuine, good result for a short test, but tells you little about a long one.",
                "Proportion throttled" to "The share of the test that ran while the device was cutting performance.",
                "Variability" to "How much speed fluctuated. High variability makes averages misleading, so look into it before you quote any single number.",
            ),
        )
        doc.callout(
            "Note: never compare results taken with different screen settings",
            "On our Pixel 6 the same model ran almost twice as fast with the screen on as with it off, so a screen-on " +
                "result and a screen-off result are measuring different conditions.",
        )

        doc.h2("Agreement between devices")
        doc.p(
            "When the same test has run on two or more devices, the report compares them, with one device as the " +
                "reference point and the others measured against it. The software only compares tests that were " +
                "specified identically, and if you ask it to compare two that weren't, it refuses rather than producing " +
                "a number that looks meaningful and isn't.",
        )
        doc.table(
            "Figure" to "How to read it",
            listOf(
                "Agreement" to "The share of repetitions where the two devices gave exactly the same answer. It's strict: identical or not, with no partial credit.",
                "First difference" to "The repetition where the devices first disagreed. An early number suggests a systematic difference, while a late one often points to heat or drift that built up over time.",
                "Closeness measures" to "When the answers differed, how far apart they were. Values very close to perfect similarity mean the devices disagreed on fine detail, and larger gaps mean they disagreed on substance.",
            ),
        )
        doc.callout(
            "Disagreement is a finding, not a fault",
            "If two chips give different answers from the same model and the same input, neither is necessarily " +
                "broken, because quantized arithmetic isn't guaranteed to be identical across manufacturers. For a " +
                "regulated product, what matters is knowing how big the difference is, being able to show it, and " +
                "having decided whether it's acceptable; assuming it's zero is the mistake.",
        )

        doc.h2("Power and memory")
        doc.p(
            "Where the device reports usable battery readings, the report includes average power draw and an estimate " +
                "of energy used per unit of work, and peak memory use is recorded throughout. Read both with the heat " +
                "section in mind: the screen is off, and the processor is deliberately kept awake.",
        )
    }

    /* ---------------------------------------------------------------- 8 */

    private fun checkingGenuine(doc: DocPdf) {
        doc.h1("Checking that evidence is genuine")
        doc.lede("If nobody but the producer can check a file, it isn't evidence.")

        doc.p(
            "Anyone can check a bundle using only the file and public certificates, without the device that made it " +
                "and without help from whoever produced it. To check a file, open the Verify screen in the app, or run " +
                "a single command on a computer.",
        )

        doc.h2("Checking a file on a computer")
        doc.p(
            "The command is verispec verify, part of the toolkit's command-line program. Someone with the toolkit's " +
                "source builds the program once, from the project folder; it then sits in " +
                "tools/cli/build/install/verispec/bin/. After that, checking a file is one line, and several files can " +
                "be named at once:",
        )
        doc.code(
            "./gradlew :tools:cli:installDist                       (once, to build the program)",
            "verispec verify verispec-bundle-<id>.json              (check one file)",
            "verispec verify --strict bundles/*.json                (fail unless every file passes)",
        )
        doc.p(
            "On Windows, start the program through the shell launcher, sh tools/cli/build/install/verispec/bin/verispec, " +
                "because the .bat launcher does not work. To fetch the files from connected phones and check each as " +
                "it arrives, use verispec fleet collect --out bundles instead (this needs a development build of the " +
                "app). For every file the program prints each of the four checks below, whether the key was held in " +
                "security hardware, the device, and a final verdict of TRUSTWORTHY or NOT TRUSTWORTHY, with any " +
                "problem named. Running it on your own computer, with your own copy of the program, is what makes " +
                "the check independent of whoever produced the file.",
        )

        doc.h2("The four checks")
        doc.table(
            "Check" to "What it establishes",
            listOf(
                "Contents unchanged" to "The file's fingerprint still matches its contents, so nothing has changed since it was sealed.",
                "Seal valid" to "The electronic signature really does match the contents and the certificate that came with it.",
                "Certificates valid" to "The chain of certificates holds together and ends at Google's published root certificate for genuine Android security hardware, showing the sealing key really was held in such hardware.",
                "Sealed for this test" to "The key was created for this specific test, so the seal can't have been moved over from a different one.",
            ),
        )
        doc.p(
            "A file is reported as trustworthy only when all four hold, and when one fails, the report names it and " +
                "says why instead of giving you a single unexplained verdict. The detail matters, because a file that " +
                "fails the first check has been edited, while a file that fails only the third may simply have come " +
                "from hardware without the necessary security features.",
        )

        doc.h2("One honest limitation")
        doc.callout(
            "Read the wording on the verification result",
            "The certificate check only means something if it ends at a starting point you trust. The toolkit " +
                "uses Google's published root certificates for Android security hardware, and the real results " +
                "from all three test phones check out against them. Those roots are public, so anyone can confirm " +
                "the toolkit uses the right ones. Someone could alter their own copy of the app to accept a fake " +
                "root, but that fools only the person using the altered copy, so evidence that matters should be " +
                "checked by the reviewer, on their own computer. Two further checks are not made yet: whether Google " +
                "has since withdrawn a phone's certificates, and whether the phone's software had been unlocked or " +
                "modified. A file that passes is therefore strong evidence of genuine hardware, not proof that the " +
                "phone was untampered.",
        )
    }

    /* ---------------------------------------------------------------- 9 */

    private fun regulatory(doc: DocPdf) {
        doc.pageBreak()
        doc.h1("The regulatory mapping")
        doc.lede("Four frameworks, twenty-nine requirements, and an explicit position on each.")

        doc.table(
            "Framework" to "What it covers, and why it's included",
            listOf(
                "EU AI Act" to "Article 15 on accuracy, robustness and cybersecurity, and Annex IV on the technical documentation a high-risk system must carry: ten requirements.",
                "ISO/IEC 42001" to "The international standard for managing AI within an organisation, covering risk assessment, monitoring, verification and record keeping: eight requirements.",
                "ISO/PAS 8800" to "Safety of AI in road vehicles, including how the hardware platform and numerical precision affect behaviour: six requirements.",
                "FDA PCCP" to "United States guidance on pre-authorising planned changes to AI-enabled medical device software, covering how a change is described, verified and assessed: five requirements.",
            ),
        )

        doc.h2("How coverage is decided")
        doc.p(
            "Each requirement in the catalogue names the kinds of evidence it needs, and after a test the system looks " +
                "at what was actually recorded and marks each requirement as fully supported, partly supported, or not " +
                "supported. You don't assert anything: coverage is calculated from the file's contents, so claiming it " +
                "can't improve it.",
        )
        doc.p(
            "Take a requirement that needs comparison between devices. Evidence from a single device can't satisfy " +
                "it, and no amount of care on that one device will change the answer, which is why single-device " +
                "reports usually show several unmet requirements; it's the correct result, not a defect.",
        )

        doc.h2("Why the mapping is kept separate from the software")
        doc.p(
            "Regulation moves, so the requirements live in dated catalogue files rather than in the program, and a " +
                "change in the law means a new catalogue, not a new release of the software. A published catalogue is " +
                "never edited afterwards, because each bundle records which catalogue version it was assessed against, " +
                "and rewriting that catalogue would silently change the meaning of evidence already issued.",
        )
        doc.callout(
            "What this mapping is and isn't",
            "It's a structured, documented view of which technical evidence supports which requirement, produced the " +
                "same way every time and showing its gaps. It isn't legal advice, a conformity assessment, or a " +
                "substitute for a notified body or a regulator, and the catalogue paraphrases obligations rather than " +
                "quoting legal text, so the legislation itself is always the authoritative source.",
        )
    }

    /* ---------------------------------------------------------------- 10 */

    private fun fleet(doc: DocPdf) {
        doc.h1("Working with several devices")
        doc.lede("The interesting results only appear once the same test has run on different hardware.")

        doc.p(
            "One device tells you how fast and how hot, while several devices tell you whether the model behaves the " +
                "same way across the hardware your product will actually ship on, which is the question a regulator, " +
                "a safety engineer or a customer is more likely to ask.",
        )

        doc.h2("The usual sequence")
        doc.numbered(
            listOf(
                "Run the same test on each device. A test is defined by its contents, so running the identical specification everywhere is what makes the results comparable.",
                "Collect the evidence onto a computer, where one command gathers the files from every connected device and checks each seal as it arrives.",
                "Compare, with another command that groups the files by test and reports agreement between devices for each group.",
                "Produce the report, where a final command renders the PDF for the binder, comparison included, with a machine-readable summary alongside.",
            ),
        )

        doc.h2("The commands, for reference")
        doc.table(
            "Command" to "What it does",
            listOf(
                "verispec fleet devices" to "Lists the connected devices with their chip and operating system version.",
                "verispec fleet collect" to "Copies evidence from every connected device into a folder and checks each file.",
                "verispec fleet compare" to "Groups the collected files by test and reports agreement between devices.",
                "verispec verify" to "Checks one or more evidence files, and can be set to fail automatically if any file isn't fully trustworthy, which suits automated checking.",
                "verispec inspect" to "Prints the contents of one file in readable form.",
                "verispec diverge" to "Compares two or more files from the same test in detail.",
                "verispec report" to "Produces the PDF report and the machine-readable summary.",
            ),
        )
        doc.callout(
            "Note: collection needs a development build",
            "verispec fleet collect reads from the app's private storage, so the device must have a development build " +
                "of the app installed. A file that fails its check during collection is kept and reported rather than " +
                "thrown away, because a failed check is itself a result somebody needs to see.",
        )
    }

    /* ---------------------------------------------------------------- 11 */

    private fun limits(doc: DocPdf) {
        doc.h1("Limits and current status")

        doc.callout(
            "Read this before you quote any figure from this toolkit",
            "Everything in this document is built and passes its automated tests. LiteRT, the first of the four AI " +
                "runtime connections, has run a few small models on a single Pixel 6, which produced sealed results " +
                "that checked out, and the other three have since had short trials on the same phone, plus one long test of " +
                "the built-in language model. In the earliest of those results the speed figure for " +
                "image and audio models was wrong, and although it has been fixed, results sealed before the fix still " +
                "carry the wrong figure and can't be changed. The wait for a cool, unplugged phone has also been tried " +
                "on that Pixel 6, and it worked, but in a warm room the phone couldn't cool to the default level, so " +
                "the test that passed used a slightly looser level, and the result says so. At first that test couldn't " +
                "name a settled speed, because a few momentary slowdowns confused the check for one even though the " +
                "speed was steady; the check was corrected on 16 November 2026, and the same result now shows a settled " +
                "speed less than 1 % below its best. The extra readings (processor speeds, battery temperature, whether the screen was on) and " +
                "the dimmed-screen option have also worked on that phone. There the same model ran almost twice as " +
                "fast with the screen on as with it off, which is why you must not compare results taken with " +
                "different screen settings. The comparison between a Pixel 6, an Honor Magic V3 and a Samsung Galaxy " +
                "Tab S7+ below is real, but it's one small model run once on each device, so treat it as an " +
                "observation rather than a general finding about any of them. " +
                "No other figure the toolkit produces today should be quoted as a finding about any device.",
            java.awt.Color(0.78f, 0.16f, 0.16f),
        )

        doc.h2("What's finished and what isn't")
        doc.table(
            "Area" to "Status",
            listOf(
                "The evidence format, sealing and checking" to "Complete and tested, including detection of altered files.",
                "The app and the test engine" to "Complete, including surviving interruption and the screen being off.",
                "The regulatory catalogue and coverage" to "Complete for the four frameworks, twenty-nine requirements.",
                "Comparison between devices" to "Run once on three real devices with three different chips (2026-11-14), a Pixel 6, an Honor Magic V3 and a Samsung Galaxy Tab S7+, each running the same image-recognition test 300 times. The tablet's answers matched the Pixel's bit for bit on every repetition, and both the Pixel and the tablet matched themselves exactly when run a second time. The Honor's were never identical, though the differences were tiny: about three parts in a hundred million at most. Before this, the comparison software had only been tested on made-up data, with differences planted in advance so the right answer was known, and one model on three devices is still a first observation, not a general finding.",
                "Reports" to "Complete; this document was produced by the same software.",
                "The four AI runtime connections" to "All four have now run a real model on a real phone. LiteRT has run small models on a Pixel 6 and one of them on an Honor Magic V3 and a Samsung Galaxy Tab S7+. Since 16 November 2026 the other three have each run one model on the Pixel 6, in short trials that show they work, not how well: an image model on ExecuTorch, and the same small language model on both MediaPipe and llama.cpp, the engine for the open model format, which was built for the first time for this. On 17 November 2026 the ExecuTorch engine was updated to a newer version, which removed a warning about memory page sizes that newer Android phones showed when the app started, and the image model was run again on it. On 17 November 2026 the same short trials were repeated on an Honor Magic V3, with all four engines. The Honor ran the language model about twice as fast as the Pixel 6, though it also started slowing itself down to manage heat within the first few repetitions, and it wrote exactly the same answers as the Pixel. The image models gave results that differ from the Pixel's only by tiny rounding amounts, the same pattern seen before, and repeating an earlier Honor test gave exactly the same results as the first time. Every test so far has used the phone's main processor. A first attempt on the Pixel 6's graphics processor, on 18 November 2026, did use it, but the language model failed there every time and produced no text. The app at first sealed that empty output as if it were a real result. That has been fixed: a run that produces nothing now stops with a clear error and no sealed file. Nothing has run on a dedicated AI chip yet. The language model on MediaPipe has also had one long test: 500 repetitions over an hour and ten minutes on an unplugged Pixel 6. It gave the same answer every time, wrote about 24 tokens a second at its best and settled near 18 once the phone warmed up. Its last stretch ran with the screen off, because the screen timed out, so that part is not comparable with the rest.",
            ),
        )

        CrossDeviceFigures.plainSection(doc)

        doc.h2("What would change with real hardware")
        doc.p(
            "Two things. First, the figures would become real, which is the whole point. Second, and less obviously, " +
                "the runtime connections would meet situations only real hardware produces, such as an accelerator " +
                "declining a model, a driver reporting its version in an odd format, or a device that heats up faster " +
                "than expected. The toolkit is designed to record these as findings rather than fail on them, and that " +
                "has now started to happen. The Honor Magic V3 and the Samsung tablet both report their battery current " +
                "in different units from the ones Android specifies, a thousand times too small; since 16 November " +
                "2026 the analysis recognises this and corrects for it, and the report says when it has. The Honor won't let apps read its graphics chip's speed, while the tablet, on an older Android " +
                "version, lets apps read some fifty temperature sensors the two phones keep private. All of it shows " +
                "up in the result instead of being hidden.",
        )

        doc.h2("Two decisions still open")
        doc.bullets(
            listOf(
                "The evidence file uses a format designed for this project. Switching to one of the existing open standards for this kind of sealed record would cost little now, and reviewers who already know that standard would recognise it, but the cost of switching grows with every file issued.",
                "Certificate checking starts from Google's published roots, but it does not yet check whether Google has withdrawn a phone's certificates, or whether the phone's software had been unlocked. Adding both would close the remaining gap between \"genuine hardware\" and \"untampered phone\".",
            ),
        )
    }

    /* ---------------------------------------------------------------- 12 */

    private fun questions(doc: DocPdf) {
        doc.h1("Questions people ask")

        doc.h2Plain("Is this a benchmark?")
        doc.p(
            "No. A benchmark ranks devices by speed, whereas this records what a specific model did on specific " +
                "hardware, in a form someone else can check, and speed is only one part of that. People mix the two " +
                "up because both produce numbers about performance.",
        )

        doc.h2Plain("Does a bundle prove our product is compliant?")
        doc.p(
            "No. It gives you technical evidence that supports particular requirements, and says plainly which " +
                "requirements it doesn't support, but compliance is a judgement made by people and organisations with " +
                "the standing to make it, on a much broader basis than any one test.",
        )

        doc.h2Plain("Can somebody edit a bundle to make the results look better?")
        doc.p(
            "They can edit the file, but anyone who checks it will see the change, and the check needs nothing from " +
                "us. They can't make a fresh seal for the altered contents without the key, which stays inside the " +
                "security hardware of the phone that ran the test and can't be extracted.",
        )

        doc.h2Plain("Why does our report show unmet requirements when the test passed?")
        doc.p(
            "Because passing a test and evidencing a requirement are different things, and one device can't meet a " +
                "requirement that asks for comparison between devices, however well it performed. The tool is working " +
                "correctly, and the list tells you exactly what to run next.",
        )

        doc.h2Plain("Two identical phones disagreed. Is one of them faulty?")
        doc.p(
            "Probably not. First check whether they really are identical in the sense that matters, with the same chip " +
                "revision, the same driver version and the same operating system update; the bundle records all of " +
                "these, and that's usually enough to explain the difference. If two truly identical devices still " +
                "disagree, that points at randomness in the test setup, which the system is designed to prevent, so " +
                "it's worth investigating.",
        )

        doc.h2Plain("Does any of our data leave the device?")
        doc.p(
            "The toolkit doesn't transmit anything anywhere, and evidence stays in the app's private storage until " +
                "someone exports or collects it. The bundle holds no model output, only fingerprints of it, so even a " +
                "file that's shared widely doesn't carry the content that went through the model.",
        )

        doc.h2Plain("How long does a test take?")
        doc.p(
            "As long as you set it to take. Meaningful sustained-load results need time for the device to get hot and " +
                "settle, tens of minutes rather than seconds, and while short tests are good for checking that your " +
                "setup works, they say little about behaviour in real use.",
        )
    }

    /* ---------------------------------------------------------------- 13 */

    private fun glossary(doc: DocPdf) {
        doc.pageBreak()
        doc.appendix("A", "Glossary")
        doc.table(
            "Term" to "Meaning",
            listOf(
                "Agreement" to "The share of repetitions in which two devices gave exactly the same answer.",
                "Attestation" to "Certificates from the device manufacturer showing that a sealing key really lived in the phone's security hardware and was created for one specific test.",
                "Decay" to "The fall in speed from the best figure to the sustainable one, as a percentage.",
                "Delegate" to "Which part of the chip did the work: main processor, graphics processor, or AI accelerator.",
                "Divergence" to "The measured difference between answers from the same model and input on different hardware.",
                "Evidence bundle" to "The sealed file produced by one test on one device.",
                "Fingerprint (hash)" to "A short value calculated from contents, which changes completely if the contents change at all.",
                "Limitation (capability gap)" to "Something the device or its software couldn't do, written into the evidence so its absence shows.",
                "Quantization" to "Storing a model's numbers less precisely so it runs on a phone, trading some accuracy for speed and size.",
                "Runtime" to "The software that runs an AI model on a device.",
                "Security hardware" to "A protected part of the phone that holds keys and won't release them, used here to seal evidence.",
                "SoC" to "System on a chip: the main processor package in a phone.",
                "Steady state" to "The typical speed once the device has settled at a sustainable temperature, and the honest figure for continuous use.",
                "Test specification" to "The full description of what was run, fingerprinted so two identical descriptions are provably the same test.",
                "Throttling" to "A device deliberately slowing itself down because it's too hot.",
            ),
        )

        doc.h2Plain("Where to go next")
        doc.table(
            "If you want to" to "Read",
            listOf(
                "Understand the code, extend it, or review it technically" to "The Developer Manual, in the same folder.",
                "Know exactly what a bundle may contain" to "The published evidence format description that ships with the software.",
                "See the wording behind each requirement" to "The dated catalogue files, which paraphrase each obligation and explain how the toolkit's evidence relates to it.",
                "Check a file somebody sent you" to "The Verify screen in the app, or the verify command on a computer, neither of which needs anything from whoever produced the file.",
            ),
        )
    }
}
