# Local post and comment translation

The Android app runs inference in-process with llama.cpp. It does not send post or
comment text to a translation server. The original Reddit data remains unchanged.

## Using the feature

1. Download the official [HY-MT2-1.8B Q4_K_M GGUF](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/tree/main)
   to the device (approximately 1.13 GB). Use standard Q4_K_M, Q6_K or Q8_0 files;
   the specialized STQ 1.25-bit/2-bit variants are not supported by this integration.
2. Open **Settings → Local translation → Import model** and choose the file.
   The app copies it into private, non-backed-up storage. Keep enough free space
   for the downloaded file and its imported copy. Replacing a model temporarily
   requires room for both imported versions.
3. Choose a target language. The initial default is Simplified Chinese.
4. Open a post's action menu and select **Translate**. Its translated title appears
   below the original title; its translated self-text appears below the original
   body in the comment screen. A comment's **Translate** action appends its translation
   below that comment. If you customized action menus, enable Translate under
   Settings → Menus. Original Markdown, links and Reddit actions remain intact.
5. In a post's comment screen, open the toolbar menu and select **Translate entire
   comment thread**. Comments are processed using the configured concurrency limit,
   including collapsed comments.
   The app follows Reddit's available “more replies” links with its existing request
   mechanism, merges new replies into the thread, and continues translating them.
   Deleted/removed/empty comments and comments filtered out by Reddit or the app are
   not translated. This cannot recover content Reddit does not return.
6. The inline progress row shows processed/failed counts. Tap it or use **Cancel
   comment translation** to stop. A download failure stops the batch and reports an
   error; individual inference failures remain visible below their original comments.
   Retry the thread action to resume, reusing completed translations with matching inputs.
   Tap an individual comment's translating indicator to cancel that item.

### Concurrent translations

**Settings → Local translation → Concurrent translations** offers 1–4 simultaneous
translations. The default is **1 (serial)**. The limit is shared across all individual
post/comment requests and thread batches in the process. It changes without restarting
the app; lowering it lets existing calls finish before the pool settles at the new
limit. A batch picks up a higher limit at its next dispatch. Additional Reddit reply
requests remain serial.

For a stable setting, each inference uses at most `max(1, 4 / concurrency)` CPU
threads, further capped by the native runtime's detected processor count. Existing
calls keep their assigned thread count until they finish. Each request owns its model,
context and sampler; mapped file pages may be shared by the OS, but inference caches
and work buffers still consume additional memory. No automatic concurrency increase,
cloud fallback or silent retry is performed.

Start with 1; try 2 on a capable device and compare total time for the same uncached
comments. More concurrency may increase memory use, heat and UI contention without
improving throughput. Desktop/emulator results do not establish phone performance.

Each comment includes bounded excerpts of the post title/body and up to three nearest
ancestors, with author names and nearest-parent-first ordering. These are source-text
excerpts, not previous model translations, to avoid propagating translation mistakes.
Long source text is split at whitespace when possible, at most 1,000 Unicode code
points per chunk; subsequent chunks receive a short preceding source excerpt. All
chunks must succeed before a result is displayed. Context excerpts are explicitly
marked when shortened. Prompts combine the official HY-MT2 **Structured Data 2**
background section with its default translation-only instruction. An explicit rule
limits background to understanding, forbidding its translation or restatement. Source
text follows the translation instruction's colon directly; the separate source heading
is omitted because the model echoed it during short-comment validation. Chinese
targets use Chinese wording; other targets use English wording. Requests without
background use the official default translation template. Context can help with
ambiguity, but accuracy and Markdown
fidelity are not guaranteed. Linked articles, image OCR and video transcription are
not included.

Translations are kept in an activity ViewModel, so recycled rows and rotation retain
completed results. Leaving/replacing the comment listing stops its batch and suppresses
late download callbacks. After rotation, restart the batch from the menu; completed
matching results are reused. Closing the activity cancels its outstanding translations.
Successful results are also persisted in a private SQLite cache across app restarts
and activities. Cache keys concatenate the original UTF-8 text's MD5 and its first
and last 8 bytes (hexadecimal, colon-separated; shorter text uses all its bytes).
Target language and discussion context are matched separately, and the original text
is checked on reads. Only complete, non-empty results are cached. Entries expire
30 days after writing; hits do not extend their lifetime. The existing hourly cache
pruner and database opening delete expired entries; reads also reject expired entries.
Android may reclaim this cache to free storage.
Replacing/removing the model invalidates result reuse. Inline views hide cached results
when the original source text no longer matches.

Local inference requires **64-bit Android 6.0+** (arm64-v8a or x86_64). The rest of
RedReader keeps its original minimum Android version and 32-bit support. Actual RAM
requirements exceed the model file size and depend on the model and device.

## Architecture and model changes

```
Post/comment action or thread queue → TranslationViewModel → TranslationService
                                         → TranslationProvider
                                           → GgufTranslationProvider
                                             → LlamaNative (JNI) → llama.cpp
```

- `TranslationRequest` carries original text, a BCP 47 target language tag and
  supporting discussion context.
- `TranslationProvider` owns model-specific prompting, supported languages and
  inference. Its translation calls may run concurrently on background workers; mutable
  per-request state must be isolated. It must honor cancellation and report failures rather than fabricate a translation.
- `TranslationService` bounds concurrent requests with a configurable worker pool and
  delivers results on the supplied callback executor. Its provider closes only after
  all running calls have returned. Cancelling an item suppresses late callbacks.
- `TranslationViewModel` retains per-item state; `InlineTranslationView` observes
  only while attached and rebinds to the correct item when rows are recycled.
- `CommentListingFragment` keeps up to the selected number of comment tasks in flight
  and serializes additional reply requests,
  deduplicating comment IDs and restoring the existing parent chain.
- `GgufTranslationProvider` supplies the HY-MT2 translation prompt. The runtime uses
  the GGUF chat template and the model's tokenizer. A different model may need a
  different provider/prompt; importing an arbitrary GGUF does not guarantee suitability.
- `LocalTranslation` is the single composition point for choosing a provider. A
  future engine can implement the same interface without changing Reddit or UI code.
- `TranslationModelStore` imports files through Android's document picker. Import
  checks GGUF magic/version; full model compatibility is checked during inference.
  Copy failure preserves the previous model. Inferences hold shared read locks;
  replacement/removal takes an exclusive write lock and waits for all readers. Lock
  acquisition is interruptible, so cancelled waiting work does not block indefinitely. **Remove model** deletes the private copy, not the source file.

The initial backend uses CPU inference, an 8,192-token context, at most 4,096 output
tokens, and the CPU thread budget described above. It reserves room for the output and rejects an
oversized chunk/context explicitly. It does not silently truncate or switch to a cloud model.
Native model, context and sampler memory are released after each chunk, including
failure and cancellation. This favors bounded memory use over repeated-load latency.
Model replacement/removal clears the persistent result cache under the model write
lock, before changing the model file, so old inferences cannot repopulate it afterward.

## Building and validation

`src/main/cpp/CMakeLists.txt` downloads llama.cpp at commit
`3057bb66c86c46d5781e50e85462a760ba7d1feb`, with a pinned SHA-256 archive checksum.
The first native build requires network access. Gradle uses the project's configured
NDK and CMake 3.22.1. The model weights are not bundled in the APK or committed to Git.
The llama.cpp MIT license is included in `assets/licenses/llama.cpp.txt`.

Build with `./gradlew assembleDebug`. No model is required to compile the app.
For a provider/runtime upgrade, validate actual translations and cancellation with
the intended model, and verify native libraries for each supported ABI.

Device acceptance scenarios:

- With no model, Translate explains how to import one; browsing remains usable.
- Import a valid model, translate a post with self-text and a nested comment,
  and verify original text, links and Reddit actions remain available.
- Translate emoji/non-ASCII content and switch the target language.
- Translate the same input in another activity and after restarting the app; matching
  language/context should reuse the result without inference. Change source, language
  or context and verify a fresh translation. Include inputs shorter than 8 UTF-8 bytes.
- Verify entries aged 30 days or more are not reused and are removed on database open
  or the regular pruning broadcast, while newer entries remain. Cache hits must not
  refresh the creation time. Failed or incomplete translations must not be cached.
- Cancel while loading and while generating; recycle/collapse rows and rotate the screen.
- Verify the default serial setting, two concurrent model readers, lowering the limit,
  cancelling active/queued tasks, and replacing/removing a model during inference.
- Translate an entire thread, load nested replies, stop/restart, and handle a download
  failure without reporting the thread as complete.
- Import a non-GGUF file, cancel the document picker, and interrupt an import;
  the previous complete model should remain usable.
- Replace/remove the model and translate again. Try a long input and verify
  all chunks are translated; failures must not expose partial output as success.

Reference: [Tencent model instructions and prompt templates](https://github.com/Tencent-Hunyuan/Hy-MT2/blob/main/README_CN.md),
[llama.cpp Android documentation](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md).

### Validation performed

- `./gradlew :pmd :Checkstyle :lintDebug :assembleDebug --console=plain` passed;
  Android Lint reported no errors or warnings. No unit tests were run.
- `zipalign -c -P 16 4 build/outputs/apk/debug/RedReader-debug.apk` passed.
- The packaged ARM64 JNI library ran the official Q4_K_M model on an Android
  emulator through a temporary `app_process` integration harness. English text
  translated into Chinese with its emoji preserved; cancellation during model
  loading raised `InterruptedIOException`. The example took approximately 53
  seconds on that emulator; this is not a physical-device performance estimate.
- A temporary Android instrumentation scenario imported the real GGUF file and ran
  the actual thread action on a local two-level comment fixture. Both comments were
  translated inline through the production provider. The child received its parent
  context and translated “He shot it yesterday. It came out sharp.” as
  “他昨天拍了照片。照片很清晰。” in a photography discussion.
- The device scenarios also checked inline rebinding, clearing a recycled untranslated
  row, hiding a result after source edits, inline cancellation, model revision changes,
  merging replies with canonical parents, and deduplicating repeated comment IDs.
- Live Reddit pagination/network failures, the document picker, long-thread stress,
  rotation, and physical-device memory/performance still need acceptance checks.
  The local fixture does not establish completeness of Reddit's server responses.

### Concurrency validation

- `./gradlew :pmd :Checkstyle :lintDebug :assembleDebug --console=plain` passed;
  Android Lint reported no errors or warnings. `git diff --check` and the APK's
  16 KB zip alignment check also passed. No unit tests were written or run.
- An Android instrumentation scenario used the actual Q4_K_M model and thread menu.
  The unset preference selected 1; changing it to 2 produced two simultaneous model
  read-lock holders and two completed native calls. Switching to 1 queued the second
  request. Active/queued cancellation, interrupting an exclusive model-writer wait,
  and draining native calls before service termination all passed.
- A host JNI scenario also completed two real model calls concurrently. These checks
  establish concurrency behavior, not phone throughput or translation quality. One
  short contextual Android response echoed a prompt delimiter/source text; model
  output quality remains a limitation and is not improved merely by concurrency.
- Physical-device throughput, sustained thermals, and 3/4-way memory pressure have
  not been benchmarked. Keep 1 as the default and evaluate 2 on the intended device.

### Official context template validation

- `./gradlew :compileDebugJavaWithJavac :pmd :Checkstyle --console=plain` passed
  (the existing task dependencies also assembled the debug APK). No unit tests ran.
- A temporary host JNI scenario called the production prompt builder and native
  runtime with the actual Q4_K_M model: six inputs, three generations each, covering
  short replies, photography context, negation/URLs, no background, Traditional
  Chinese, and an English target. All 15 Chinese-target outputs were in Chinese;
  the three English-target outputs were in English. URLs and negation were retained.
- Output-quality acceptance did **not** fully pass: two of the 18 outputs echoed
  `〖待翻译文本〗`. Manual inspection also found two translations of a train ticket
  as an airline ticket. The official template alone does not guarantee clean or
  accurate output. No output stripping or silent retry was added. This prompt change
  has not been exercised on an Android device.

### Short-comment scope correction

- Reproduced background-only output with the previous official context template:
  `Exactly.`, `Why?`, and `Nice shot!` translated a photography post and its ancestors
  instead of the requested comment. This is a P1 translation correctness issue.
- The adjusted prompt preserves the same background excerpts, explicitly excludes
  background from the output, and places source text directly after the translation-only
  instruction. It does not shorten context based on comment length, strip generated
  text, change sampling, or silently retry.
- With the actual Q4_K_M model and production prompt builder/JNI on the host, eight
  short comments were each generated three times. All 24 outputs translated the
  requested comment without background or template headings. For example, `Exactly.`
  became `没错。`, and `Why?` became `为什么？`. This is a bounded scenario result,
  not a guarantee for every comment or model.
- Another 18 real-model outputs covered longer comments, no context, URLs/negation,
  Traditional Chinese, and an English target. Language, URL retention, and absence
  of template echoes passed. Manual inspection still found loss of precision:
  photography's `sharp` became a generic positive description rather than image
  sharpness. Correct output scope does not guarantee full semantic accuracy.
- `./gradlew :compileDebugJavaWithJavac :pmd :Checkstyle --console=plain` passed,
  including the debug APK assembly required by the existing task dependencies.
  No unit tests were written or run. Android-device validation remains outstanding.
