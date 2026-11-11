# Writing plugins for Sunlit Clearing

Everything that shows up in a panel (tests, linters, architecture extracts,
the matrix, image previews) is a plugin. This guide explains how plugins
work and how to write your own.

There are two kinds:

| | Repo plugins | Built-in plugins |
|---|---|---|
| Where | `<repo>/.sunlit/plugins/*.clj` in the watched repo | `src/clj/sunlit/plugins/` in Sunlit Clearing |
| Language | Clojure, interpreted by [SCI](https://github.com/babashka/sci) | Clojure, compiled into the jar |
| Access | a small, mostly read-only API | everything (shell commands, the job cache, any library) |
| Background jobs | no | yes |
| Good for | quick project-specific panels | tools you want in every repo, slow tools |

Start with a repo plugin. Make it a built-in when it needs to run
commands, take a long time, or be available everywhere.

## How a plugin is used

A plugin appears on a page in one of two ways:

1. **A fence** on a wiki page or file annotation. The EDN map in the fence
   becomes the plugin's `params`:

   ````markdown
   ```plugin todo-count
   {:pattern "TODO|FIXME"}
   ```
   ````

2. **Automatically**, for certain files or folders. For example `leiningen`
   attaches to every `project.clj`, `image` to image files, and `gallery` to
   folders containing images. (Auto-attachment is set up in
   `sunlit.render`; see [Built-in plugins](#built-in-plugins).)

Either way, Sunlit Clearing calls the plugin's `:run` function and puts
the HTML it returns into the page.

## The plugin map

A plugin is a map:

```clojure
{:name "todo-count"                     ; what fences call it
 :description "Counts TODO comments."   ; shown on the Plugins page
 :run (fn [{:keys [path params repo-root]}] ; called to render
        {:html "<div class='plugin-panel'>…</div>"})}
```

### What `:run` receives

| Key | Value |
|-----|-------|
| `:params` | the fence's EDN map (`{}` for an empty fence or an auto-attached plugin) |
| `:path` | the file or folder being viewed, relative to the repo root; `nil` on a wiki page |
| `:repo-root` | the repo's absolute path, as a string |

If a fence's body isn't valid EDN, `params` is `{:raw "<the text>"}`.

### What `:run` returns

| Key | Meaning |
|-----|---------|
| `:html` | the panel shown to people (main output) |
| `:markdown` | alternative to `:html`: Markdown that Sunlit Clearing renders for you |
| `:text` | optional plain text or Markdown for AI agents |
| `:data` | optional structured result for agents: maps, vectors, strings, numbers, booleans, nil |
| `:ok false` | marks the run as failed |

If `:run` throws, the panel shows the error message; the rest of the page
still works.

### Make it useful to agents too

Every page and plugin is also served as JSON under `/agents/`, so AI agents
working on the repo can read the same facts you see:

- `GET /agents/page/<name>`: a wiki page with its plugin results
- `GET /agents/file/<path>`: a file's annotation and plugin results
- `GET /agents/plugin/<name>`: one plugin's result

Agents get `:text` and `:data`, not your HTML. So return the actual facts
in `:data` (`{:count 12 :files [...]}`), not just a rendering of them. A
plugin that does this is both a panel for you and a tool for your agents.

## Repo plugins

Put one `.clj` file per plugin in the watched repo's `.sunlit/plugins/`
folder. The file's last form should be the plugin map. Restart Sunlit
Clearing to load new or changed plugins; the Plugins page lists them (under
"Repo") along with any that failed to load.

An example: count TODO comments across the repo.

```clojure
;; .sunlit/plugins/todo-count.clj
(require '[clojure.string :as str]
         '[sunlit.plugin :as sp])

(defn- matches [re path]
  (->> (str/split-lines (or (sp/slurp-repo path) ""))
       (keep-indexed (fn [i line] (when (re-find re line) {:path path :line (inc i)})))))

{:name "todo-count"
 :description "Lists TODO/FIXME comments in the files given by :files."
 :run (fn [{:keys [params]}]
        (let [re (re-pattern (or (:pattern params) "TODO|FIXME"))
              hits (mapcat #(matches re %) (:files params))]
          {:html (str "<div class='plugin-panel'><h3>TODOs: " (count hits) "</h3><ul>"
                      (str/join (map #(str "<li><a href='#/file/" (:path %) "?line=" (:line %) "'>"
                                           (:path %) ":" (:line %) "</a></li>")
                                     hits))
                      "</ul></div>")
           :data {:count (count hits) :hits (vec hits)}}))}
```

Used like this:

````markdown
```plugin todo-count
{:files ["src/app/core.clj" "src/app/server.clj"]}
```
````

There's another example in `examples/plugins/hello.clj`.

### What repo plugins can use

Repo plugins run in a sandbox. They have Clojure's core functions and
`clojure.string`, plus these functions in the `sunlit.plugin` namespace:

| Function | Returns |
|----------|---------|
| `(repo-root)` | the repo's absolute path |
| `(slurp-repo path)` | a repo file's text |
| `(read-file path)` | a map with the file's `:content` and details |
| `(git-available?)` | whether the repo is a git repository |
| `(tracked? path)` | whether git tracks the file |
| `(git-status path)` | the file's git status |

They can't use Java classes, run shell commands, write files, or start
background jobs. Build HTML as strings (Hiccup isn't available).

Instead of ending with a map, a file can call
`(sunlit.plugin/define! {...})`, or end with a bare `(fn [ctx] ...)`. A bare
fn takes its name from the file name and has no description.

## Built-in plugins

A built-in plugin is a namespace under `src/clj/sunlit/plugins/` that defines
a `plugin` map. `src/clj/sunlit/plugins/leiningen.clj` is a small, complete
example.

To add one:

1. Create `src/clj/sunlit/plugins/my_plugin.clj` with
   `(ns sunlit.plugins.my-plugin ...)` and `(def plugin {...})`. Use Hiccup
   (`hiccup.core/html`) to build HTML.
2. In `src/clj/sunlit/plugins.clj`, require it and add `my-plugin/plugin` to
   `builtin-defs`.
3. Optional: to attach it to certain files or folders automatically, add a
   case to `auto-plugin-fences` (files) or `auto-plugin-fences-for-dir`
   (folders) in `src/clj/sunlit/render.clj`.
4. Optional: to have it on a starter page in every repo, add a fence to
   the page's seed text in `src/clj/sunlit/pages.clj`. Seeds are only written
   when a page doesn't exist yet, so existing repos aren't changed.
5. Add tests under `test/sunlit/` and run `lein test`.

Built-ins can read repo files with `sunlit.repo` (`read-file`,
`safe-resolve`, `sidecar-file`), and anything else on the classpath.

### Slow plugins: running in the background

Running tests, linters or a deep scan is too slow to do on every page load.
Such plugins have two functions:

- `:run` only **shows the cached result** of the last run. It never does the
  work.
- `:start!` **starts a run** in the background. It's called when someone
  clicks Rerun (or when an agent asks for a fresh result).

Results are stored in `.sunlit/cache/plugins/<plugin>/<id>/`, so they survive
restarts. Each finished run is also added to the run log,
`.sunlit/cache/runs.jsonl`, which the `overview` plugin reads, so a new
background plugin appears in the overview without any extra work. If your
`:compute!` result has a small `:summary` map (like `{:passed 12 :failed 0}`),
it is logged too and shown in the overview. `sunlit.jobs` provides the machinery.

**Running a shell command.** `jobs/start!` runs a command in the repo root,
saving its exit code and output; `jobs/render-panel` draws the standard panel
(title, status, Rerun button, command, output). The generic `run` plugin
(`src/clj/sunlit/plugins/run.clj`) is the simplest example:

```clojure
(ns sunlit.plugins.shellcheck
  (:require [sunlit.jobs :as jobs]))

(defn- id [params] (or (:id params) "shellcheck"))

(def plugin
  {:name "shellcheck"
   :description "Runs shellcheck over the repo's scripts."
   :long-running? true
   :run (fn [{:keys [params]}]
          (jobs/render-panel {:plugin "shellcheck" :id (id params)
                              :title (or (:title params) "shellcheck")
                              :params (assoc params :id (id params))}))
   :start! (fn [{:keys [params]}]
             (jobs/start! {:plugin "shellcheck" :id (id params)
                           :cmd (or (:cmd params) ["sh" "-c" "shellcheck scripts/*.sh"])}))})
```

**Running Clojure code.** `jobs/start-compute!` runs a function in the
background instead of a command. The function returns a map; any `:html`
in it is shown in the panel, and `:summary` / `:stdout` are kept too. The
`structure-*` plugins (`src/clj/sunlit/plugins/structure.clj`) work this way:

```clojure
:start! (fn [{:keys [params]}]
          (jobs/start-compute! {:plugin "my-scan" :id (id params)
                                :label "my-scan"
                                :compute! (fn [] {:html (scan-html) :summary {:units 42}})}))
```

**Panel options.** `jobs/render-panel` also takes:

- `:summary-html`: a headline shown above the output, e.g. "12 passed,
  1 failed". `unittests` parses the test output to make one.
- `:iframe`: a repo-relative HTML file to embed after a run, such as a
  coverage report.
- `:body-html`: your own HTML in place of the cached `:html`.
- `:collapsible? true`: fold the output away under a toggle, keeping the
  title and summary visible (used by the linters).

The `:id` keeps separate results for separate fences, e.g. one per language.
Two runs with the same plugin and id never overlap: Rerun while one is
running just shows the run in progress.

**Linters.** To add a linter like the mystyle ones, use
`sunlit.mystyle/linter-plugin`; see `src/clj/sunlit/plugins/mystyle_clj.clj`
and the commands in `src/clj/sunlit/mystyle.clj`. A linter that prints
`path:line:col [HARD|WARN] message` lines gets the findings panel, counts and
`:exclude` support for free.

### Plugins that edit their own fence

A plugin can let people change its settings from the panel, as the matrix's
cell editor does. Give the plugin an `:update` function:

```clojure
:update (fn [params action] new-params)
```

The browser sends an action with `POST /api/fence`, naming the page or
annotation, the fence's position on it, the plugin and the action (any
JSON). Sunlit Clearing passes the fence's current params and the action to
`:update`, and writes the returned params back into the fence. Only the
entries that changed are rewritten, so the user's comments and layout
survive. See `update-params` in `src/clj/sunlit/plugins/matrix.clj` and
`sunlit.fences`.

A plugin only ever changes its own fence, which lives in `.sunlit/`.
Plugins must never modify the watched repo's own files.

## Showing a fence without running it

To show a plugin fence as an example in a page or annotation, as these
guides do, wrap it in a fence of four backticks. Plugin fences inside it are
shown as code and never run:

`````markdown
````markdown
```plugin my-plugin
{}
```
````
`````

## Checklist

- [ ] A clear `:name` and a one-sentence `:description` (it's the Plugins
      page's only documentation).
- [ ] Sensible defaults, so `{}` works.
- [ ] `:data` with the actual facts, for agents.
- [ ] Slow work goes in `:start!`, not `:run`.
- [ ] Reads the repo; never writes to it.
- [ ] Built-ins: registered in `builtin-defs`, with tests.
