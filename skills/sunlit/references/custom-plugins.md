# Custom (per-project) Sunlit plugins

Sunlit supports project-specific plugins written in Clojure, sandboxed via SCI.
They live in the watched repo under `.sunlit/plugins/*.clj` and are loaded when
the Sunlit server starts (or restarts). They appear in the Plugins page under
"In this repository" and can be embedded on wiki/annotation pages.

## File shape

Create `<repo>/.sunlit/plugins/<name>.clj`. The simplest and preferred shape is
a single map returned as the last form:

```clojure
{:name "hello"
 :description "Demo project plugin: shows the current file path and repo root."
 :run (fn [{:keys [path params repo-root]}]
        {:html (str "<div class='plugin-panel'>"
                    "<h3>" (or (:title params) "Hello plugin") "</h3>"
                    "<p>File: <code>" path "</code></p>"
                    "<p>Repo: <code>" repo-root "</code></p>"
                    "</div>")})}
```

`<name>` is the filename without `.clj`; the `:name` key wins when present.

Alternatives:

- Return a bare function as the last form — the filename becomes the plugin
  name and the description is empty.
- Call `(codestory.plugin/define! {...})` or
  `(codestory.plugin/register! "name" fn)` to register imperatively.

## The plugin map

| Key | Required | Meaning |
| --- | --- | --- |
| `:name` | yes | Plugin name (used in the catalog and fences). |
| `:description` | no | Shown on the Plugins page. |
| `:run` | yes | `(fn [ctx] result)` — synchronous handler. |
| `:start!` | no | `(fn [ctx] result)` — long-running kick-off (see below). |
| `:long-running?` | no | `true` to mark it long-running in the catalog. |

The `:run` function receives a context map:

- `:path` — the file path the plugin was opened from (often nil).
- `:params` — a map from the fence body or API call.
- `:repo-root` — the absolute watched repository path.

The `:run` result is merged with `{:ok true}`. Useful keys:

- `:html` — an HTML string rendered as the plugin panel (the primary human output).
- `:markdown` — optional; rendered to HTML by the host (alternative to `:html`).
- `:text` — optional; plain text or markdown for machine/agent consumers.
- `:data` — optional; structured data (maps/vectors of JSON-safe values) for
  the `/agents/` endpoints and programmatic consumers.
- `:ok false` — mark the run as failed.

## Machine-readable output (advice)

Human HTML panels are the primary UI, but agents read `/agents/` JSON. When a
plugin produces facts — a parsed file, a list of items, counts — return them as
`:data` too (a map or vector of JSON-safe values: strings, numbers, booleans,
nil, and nested maps/vectors; no Clojure symbols or other non-serializable
forms). Return `:text` for a concise plain-text or markdown summary.

The human UI ignores `:data` and `:text`; the `/agents/plugin/<name>` endpoint
returns them so agents get structured output without parsing HTML.

```clojure
{:name "hello"
 :description "…"
 :run (fn [{:keys [path repo-root]}]
        {:html "<div class='plugin-panel'>…</div>"
         :text (str "File: " path "\nRepo: " repo-root)
         :data {:path path :repo-root repo-root}})}
```

## Host functions available in the sandbox

Call these through the `codestory.plugin` namespace:

- `(codestory.plugin/repo-root)` → absolute watched repo path string.
- `(codestory.plugin/read-file rel)` → file map
  `{:path :name :size :text? :language :content}`, or throws when not a file.
- `(codestory.plugin/slurp-repo rel)` → the file's text content string.
- `(codestory.plugin/git-available?)` → true when git is on PATH and the repo
  is a work tree.
- `(codestory.plugin/tracked? rel)` → true when the file or directory is tracked
  in git (a directory counts as tracked if it contains a tracked file).
- `(codestory.plugin/git-status rel)` → `:tracked`, `:untracked`, `:ignored`,
  or nil.
- `(codestory.plugin/define! m)` — register a plugin map imperatively.
- `(codestory.plugin/register! name-or-map [fn])` — register a bare fn (or map)
  under a name.

The sandbox is intentionally small: only `codestory.plugin` and
`java.lang.String` are exposed. It does **not** include `clojure.java.shell`,
hiccup, or the job cache. Use the host functions to read files and ask about
git; do the rest in plain Clojure.

## Loading and running

- Restart Sunlit after adding or editing a plugin (plugins are read at startup).
- `GET /api/plugins` lists it under `:repo` ("In this repository" on the
  Plugins page at `/#/plugins`).
- Run it from the Plugins page, or embed it on any wiki/annotation page with a
  plugin fence:

  ````text
  ```plugin hello
  {:title "Custom title"}
  ```
  ````

  The line after ` ```plugin <name> ` is optional EDN; it becomes the `:params`
  map passed to `:run`.

## Long-running plugins (advanced)

Built-in long-running plugins use the job cache (`codestory.jobs`), which is
not available in the SCI sandbox. A custom plugin that needs to run a slow
command should do it synchronously in `:run` (the browser waits), or ship its
own polling logic in returned HTML. In practice most project plugins are
synchronous `:run` handlers.

## Example: report the git status of the current file

```clojure
{:name "file-status"
 :description "Shows whether the current file is tracked in git."
 :run (fn [{:keys [path]}]
        {:html (str "<p>" path " → " (codestory.plugin/git-status path) "</p>")})}
```

## Reference

- Built-in plugins live in `src/clj/codestory/plugins/*.clj` in the Sunlit
  repo; they show how `:run`/`:start!` and the job cache work.
- A complete example project plugin ships at `examples/plugins/hello.clj` in
  the Sunlit repo.
