# API translation

Open **Settings → Translation → Translation method** to select a local model or an
API format. Each API format retains its own settings. Local GGUF remains the default.
API translation does not require importing a local model or loading the native runtime.

All requests are non-streaming. Complete translations appear in the existing inline
post/comment views and use the same 30-day SQLite cache. The whole-comment-thread
action uses the same shared concurrency setting (1–4, default 1).

## Configuration

All API formats provide an HTTPS address, a credential and a whole-request timeout
in seconds (default 120). Addresses must not contain credentials, query parameters or
fragments. The app appends the operation path to the base URL, except for Google
Basic, which uses the configured URL directly. Custom compatible gateways must expose
the selected request and response format; changing the address does not convert formats.

| Format | Default address | Additional settings |
| --- | --- | --- |
| OpenAI Chat Completions | `https://api.openai.com/v1` | Model ID (required), output token limit, token-limit parameter (`max_tokens` or `max_completion_tokens`), optional organization and project |
| OpenAI Responses | `https://api.openai.com/v1` | Model ID (required), output token limit, optional organization and project |
| Anthropic Messages | `https://api.anthropic.com/v1` | Model ID (required), output token limit, API version (default `2023-06-01`) |
| Gemini generateContent | `https://generativelanguage.googleapis.com/v1beta` | Model ID without `models/` (required), output token limit |
| DeepL | `https://api-free.deepl.com/v2` | Formality (`default`, `more`, `less`, `prefer_more`, `prefer_less`); use `https://api.deepl.com/v2` for the Pro endpoint |
| Google Cloud Translation Basic v2 | `https://translation.googleapis.com/language/translate/v2` | API key; this is the complete endpoint URL |
| Google Cloud Translation Advanced v3 | `https://translation.googleapis.com/v3` | OAuth access token, project (required), location (default `global`), optional full model resource name |
| Azure Translator v3 | `https://api.cognitive.microsofttranslator.com` | Subscription key, resource region when required, optional custom translation category |

Use a model ID supported by the configured service. No model is selected implicitly.
The default LLM output limit is 4,096 tokens. For Chat Completions, select the token
parameter supported by the model/service; the app does not retry using another
parameter after rejection. Leave the key empty only for Chat Completions/Responses
endpoints that intentionally do not require authentication.

Google Advanced accepts an OAuth **access token**, not an API key or service-account
JSON. Token acquisition and automatic refresh are not implemented. Replace an expired
token in settings. The optional model is a full resource name, for example
`projects/PROJECT/locations/global/models/general/nmt`; when omitted, Google's default
model is used. Regional deployments can use the appropriate regional base URL.

Azure's base URL must include any path prefix required by the resource, such as
`/translator/text/v3.0` for an applicable custom endpoint; the app appends `/translate`.
Global single-service Azure resources may omit the region. Other resource types
require the region matching the subscription.

## Behavior and data

- LLM APIs receive the original text and existing bounded discussion context. The
  translation instruction limits output to the original text and requests preservation
  of Markdown, links, code and paragraph breaks. DeepL receives context separately.
  Google Translation and Azure receive only the original text because these adapters
  do not have a discussion-context field. Translation accuracy and Markdown fidelity
  depend on the service and model.
- Target languages remain Simplified/Traditional Chinese, English, Japanese, Korean,
  French, German and Spanish. Dedicated adapters map the Chinese tags to their
  service's language codes.
- API settings and credentials live in separate app-private preferences. They are
  excluded from the app's preferences export, Android cloud backup and device transfer.
  Keys are masked in settings. Requests do not include Reddit cookies or account tokens.
- Text goes to the configured service; thread translation may incur API charges.
  API requests follow the app's Tor setting through its existing Orbot HTTP proxy
  address. Redirects and automatic repeat requests are disabled.
- Requests use the complete source text. Service input limits produce an explicit
  failure; the API adapter does not silently truncate or fall back to another model.
  Responses are bounded to 2 MiB and read completely before display or caching.
- HTTP failures, empty/malformed responses, refusals, tool output and reported output
  truncation are errors. The application does not save partial output as a translation.
  A failed item can be retried using the existing translation action. Cancelling a job
  interrupts its HTTP request and suppresses late callbacks. Cancellation cannot undo
  work already performed or charged by the remote service.
- Cache namespaces include format, normalized address, model, relevant generation
  settings and prompt version. Credentials and credential hashes are not cached.
  Source text, target language and discussion context must also match. Schema v1's
  disposable cache is recreated once during upgrade to v2; imported models are retained.
- Changing API settings, target language or Tor settings cancels old jobs and clears
  in-memory results. A thread batch stops when its configuration revision changes;
  restart the thread action to use the new configuration. Completed disk-cache entries
  remain reusable when their inputs and configuration match. Updating/removing the
  local model still clears the translation cache under the existing model lock.

## Architecture

`TranslationViewModel → TranslationService → TranslationProvider`

`LocalTranslation` selects `GgufTranslationProvider` or `ApiTranslationProvider`.
`ApiTranslationConfig` provides independent profiles and validates configuration;
`ApiTranslationProtocol` builds/parses the eight wire formats;
`ApiTranslationProvider` owns cancellable OkHttp requests and namespaced caching.
The API provider uses existing OkHttp and Android JSON APIs; no dependencies or
minimum Android version were changed.

## Acceptance

Validation performed on 2026-09-14:

- `./gradlew :compileDebugJavaWithJavac :Checkstyle :lintDebug :pmd --console=plain`
  passed. Lint reported no errors or warnings. PMD's existing dependency also built
  the debug APK. Existing Java deprecation/resource warnings remain.
- `git diff --check` and `zipalign -c -P 16 4 build/outputs/apk/debug/RedReader-debug.apk`
  passed. XML checks confirmed exclusion of API profiles from all three Android
  backup/transfer sections.
- A temporary Android instrumentation scenario on the existing Pixel 9 Pro emulator
  completed 60 business-scenario checks against a local HTTPS fixture. The fixture
  verified each format's path, authentication headers and request fields. Scenarios
  covered all eight response formats, empty/malformed responses, authentication and
  quota failures, LLM truncation/refusal, a 503 without repeated HTTP requests,
  redirect rejection, the response-size limit, cancellation, timeout, cache isolation,
  settings fields, masked credentials, inline rebinding and configuration changes.
- The same scenario invoked the actual whole-thread menu on a two-level comment
  fixture. Both comments completed, the child retained its parent context, and
  progress reported two successes and no failures.
- A separate instrumentation process reused the persisted translation while an HTTP
  interceptor rejected any network attempt. An Android SQLite scenario also upgraded
  a v1 cache and verified that identical inputs can coexist in separate engine
  namespaces after migration.
- The fixture used temporary credentials and a temporary trusted certificate injected
  only into the acceptance harness. Production TLS validation was not changed. The
  harness and fixture are outside the repository. No unit tests were written or run.

Live-service translation quality, account permissions and billing require credentials
for the intended service and were not verified. The fixture cannot establish those
properties. Real Orbot routing and physical-device behavior also remain unverified.

Official interface references:

- [OpenAI Chat Completions](https://developers.openai.com/api/reference/cli/resources/chat/subresources/completions)
- [OpenAI Responses](https://developers.openai.com/api/reference/cli/resources/responses/methods/create)
- [Anthropic Messages](https://platform.claude.com/docs/en/api/messages/create)
- [Gemini generateContent](https://ai.google.dev/api/generate-content)
- [DeepL text translation](https://developers.deepl.com/api-reference/translate/request-translation)
- [Google Basic v2](https://docs.cloud.google.com/translate/docs/reference/rest/v2/translate)
- [Google Advanced v3](https://docs.cloud.google.com/translate/docs/reference/rest/v3/projects.locations/translateText)
- [Azure Translator v3](https://learn.microsoft.com/en-us/azure/ai-services/translator/text-translation/reference/v3/translate)
