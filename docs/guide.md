# Sunlit Clearing user guide

This guide covers the main tools Sunlit Clearing gives you for understanding a
repository:

1. [Architecture overviews](#1-architecture-overviews)
2. [Unit tests, coverage and linters](#2-unit-tests-coverage-and-linters)
3. [The matrix](#3-the-matrix)
4. [Conventions and issues](#4-conventions-and-issues)
5. [Agent transcripts](#5-agent-transcripts)

For installing and starting Sunlit Clearing, see the [README](../README.md).
To add your own tools, see [Writing plugins](writing_plugins.md).

## Before you start: pages and plugin fences

Most features live on **wiki pages**. Pages are Markdown files kept in the
repo's `.sunlit/pages/` folder. Click **Edit** on any page (or on a file's
annotation) to change it. Link to other pages with `[[Page name]]` and to
files with `[[src/foo.clj]]`.

A page shows a tool by including a **plugin fence**. This is a fenced code
block whose language is `plugin <name>`, with options written in EDN (Clojure
map syntax) inside it:

````markdown
```plugin unittests
{:lang :python}
```
````

`{}` means "use the defaults". You can put the same plugin on several pages,
or several times on one page with different options. The **Plugins** page
lists every plugin available with a short description.

### Slow tools run in the background

Running tests, coverage, linters and architecture scans can take a while. So
these plugins never run when a page loads. Instead each one shows a panel with
its **last result** (and when it was produced), plus a **Rerun** button. Click
Rerun to start a fresh run in the background; the panel updates when it
finishes. Results are stored under `.sunlit/cache/`, so they survive a
restart.

If two fences of the same plugin should keep separate results, give each an
`:id`:

````markdown
```plugin unittests
{:lang :clojure :id "fast" :cmd ["lein" "test" ":fast"]}
```
````

Sunlit Clearing creates a few starter pages the first time it opens a repo:
**Architecture**, **Testing**, **Lint** and **Python tools**, and puts an
[overview](#is-everything-up-to-date) on the front page. They're ordinary
pages, so edit them freely.

Every repo also has two built-in pages: this **Guide** (the Guide link in the
top bar) and **Writing plugins**. If you edit and save one, your copy is kept
in the repo and shown from then on; delete it from `.sunlit/pages/` to get
the built-in version back.

---

## 1. Architecture overviews

The **Architecture** tab opens a wiki page for your own description of the
project, with automatic extracts of the code's structure underneath.

The extracts are **deterministic**: they're read directly from the source
code, with no AI involved, so they are accurate and the same every time.

For each language, a scan shows:

- the modules, namespaces or packages, and how they depend on each other
- types and classes, and how they relate
- diagrams of both (drawn with Mermaid; large projects get a package-level
  overview instead of one huge diagram)

Click **Rerun** after the code changes to refresh the scan.

### Choosing what to show

The starter Architecture page uses the combined `structure` plugin, which
shows one box for each supported language present in the repo:

````markdown
```plugin structure
{}
```
````

In a large or multi-language repo, you may prefer one language per box, or
per page. Use the per-language plugins:

| Plugin | Language |
|--------|----------|
| `structure-clojure` | Clojure / ClojureScript |
| `structure-python` | Python |
| `structure-javascript` | JavaScript / TypeScript |
| `structure-haxe` | Haxe |
| `structure-cpp` | C / C++ |

For example, a page called "Backend" could hold just
`structure-python`, with your notes about the backend around it.

### Schemas and grammars (Clojure)

Two more plugins find particular kinds of definitions in Clojure code under
`src/`:

- `malli` lists the [Malli](https://github.com/metosin/malli) schemas.
- `instaparse` lists the [Instaparse](https://github.com/Engelberg/instaparse)
  grammars.

Both are on the starter Architecture page; delete them if your project doesn't
use those libraries.

### Writing about the architecture

The page itself is yours. A good overview explains *why* the code is shaped
the way it is, which the extracts can't tell you. Link straight to the
code you are talking about:

- `[[src/app/server.clj]]` links to a file
- `[[src/app/server.clj#start!]]` links to a function or other definition
- `[[src/app/server.clj#40-60]]` links to a range of lines

In the file view, click a line number to get a link to that line, or
shift-click to select a range.

---

## 2. Unit tests, coverage and linters

### Unit tests

The **Testing** tab runs your test suite and shows a headline summary
(passed / failed counts) above the full output.

The `unittests` plugin works out the project's main language from the
source files it finds, and runs:

| Language | Default command |
|----------|-----------------|
| Clojure | `lein test` |
| Python | `pytest`, looking for tests in `tests/python`, `tests/` or `test/` |
| Haxe | `haxe test.hxml` (or `tests.hxml`, `test.interp.hxml`) |

Options for the fence:

- `:lang` chooses the language instead of guessing: `:clojure`, `:python` or
  `:haxe`.
- `:cmd` runs your own command instead, as a vector (`["make" "test"]`) or a
  string.
- `:path` / `:tests` points pytest at a test folder.
- `:hxml` picks the Haxe build file.
- `:id` keeps this fence's results separate (see above).

The starter **Testing** page has one section per language. Delete the ones
you don't need.

### Coverage

Coverage plugins run the tests with coverage measurement, show a summary, and
embed the HTML coverage report in the page so you can browse it.

| Plugin | Language | What it runs |
|--------|----------|--------------|
| `cloverage` | Clojure | `lein cloverage` (the project needs the cloverage plugin) |
| `pycov` | Python | `pytest` with coverage.py |
| `hxcov` | Haxe | `haxe testCoverage.hxml` (or `coverage.hxml`), reading `lcov.info` |

Each accepts `:cmd` to override the command and `:report` to name the HTML
report file if Sunlit Clearing can't find it in the output.

### Python tools

Sunlit Clearing keeps its own Python environment in `.sunlit/venv`, separate
from any environment your project uses. It's created the first time you run
Python tests, coverage or a Python-based linter, and pytest, coverage and the
linters' parsing libraries are installed into it. The **Python tools** page
lets you create or repair it by hand.

Your project's own dependencies are not installed there. If the tests need
them, use `:cmd` to run pytest from your project's environment.

### Linters

The **Lint** tab is a wiki page with one panel per linter. The bundled
linters check structural style: overlong functions and files, hidden mutable
state, code that silently ignores bad input, near-duplicate code, and so on.
Each finding is marked **HARD** (should fix) or **WARN** (worth a look).

| Plugin | Language | Needs |
|--------|----------|-------|
| `mystyle-clj` | Clojure / ClojureScript | [babashka](https://babashka.org) (`bb`) |
| `mystyle-py` | Python | Python |
| `mystyle-js` | JavaScript / TypeScript | Python (parsing libraries are installed automatically) |
| `mystyle-cpp` | C / C++ | Python (as above) |
| `mystyle-hx` | Haxe | nothing (bundled program, Linux x86-64) |

These linters encode one person's opinions about style. Treat them as
a starting point, or write your own (see [Writing plugins](writing_plugins.md)).

Each panel folds open and closed; the counts stay visible when it's closed,
and the browser remembers which panels you left open.

**Skipping folders.** Each linter already skips the usual suspects (version
control, build output, installed dependencies). To skip more, list them under
`:exclude` in the fence, save the page, then Rerun:

````markdown
```plugin mystyle-clj
{:exclude [".cache" "panel-editor/generated"]}
```
````

A bare name like `".cache"` is skipped wherever it appears. A path like
`"panel-editor/generated"` is relative to the repo root.

Delete the fences for languages the repo doesn't use.

### Project linters

If the repo has its own lint script (`projectlint.sh`, running
`project_lint/*.clj` or `*.bb` checks), the Testing tab also runs it via the
`projectlint` plugin and shows an OK / ERROR summary.

### Running any command

The `run` plugin runs any shell command in the repo and shows its output,
with the same cached / Rerun behaviour:

````markdown
```plugin run
{:id "build" :title "Release build" :cmd ["lein" "uberjar"]}
```
````

Add `:report "path/to/report.html"` to embed an HTML file the command
produces.

### Is everything up to date?

The `overview` plugin answers "when did we last run the tests, coverage and
linters, did they pass, and has the code changed since?" It's on the front
page of new repos; put it on any page:

````markdown
```plugin overview
{}
```
````

With `{}` it lists everything that has been run, with its result and how long
ago. To check that results are current, list the checks you care about and
the files each one covers:

````markdown
```plugin overview
{:title "Health"
 :checks [{:label "Python tests"    :plugin "unittests" :id "python" :compare "src/**/*.py"}
          {:label "Python coverage" :plugin "pycov"                  :compare "src/**/*.py"}
          {:label "Clojure lint"    :plugin "mystyle-clj"            :compare ["src/**/*.clj" "test/**"]}
          {:label "Deployed"        :match "deployed to prod"        :compare "src/**"}]}
```
````

Each row shows one of:

| State | Meaning |
|-------|---------|
| ✓ up to date | the last run passed, and no matching file has changed since |
| ⚠ code changed since | a file under `:compare` was modified after the last run (the row names it) |
| ✗ last run failed | the last run failed (the row says when it last passed) |
| – never run | nothing in the log matches |

A check picks out runs by `:plugin`, by `:id` (the fence's `:id`, or the
plugin's default such as `"python"` for Python tests), and by `:match`, a
regular expression tested against the run's message. Use any combination.
`:compare` takes one file pattern or a list; leave it out to skip the
comparison.

**How it works.** Whenever a background run finishes, Sunlit Clearing adds a
line to `.sunlit/cache/runs.jsonl`:

```json
{"at":"2026-11-11T12:59:17Z","plugin":"mystyle-clj","id":"clojure","status":"ok","msg":"mystyle-clj/clojure ok","summary":{"hard":0,"warn":225}}
```

The overview only reads this log; it doesn't know about the plugins that
wrote it. So anything else can add to it too: a deploy script, a git hook,
or an agent. Only `at` (a UTC timestamp) and `msg` are needed:

```bash
echo "{\"at\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"msg\":\"deployed to prod\"}" >> .sunlit/cache/runs.jsonl
```

A line without a `status` counts as a success. File changes are judged by
modification time, so switching git branches can mark checks as changed
even when the content is the same; rerun them and they're current again.

---

## 3. The matrix

A file tree can only group code one way. But a lot of code has a grid shape:
every *type* needs every *operation*, every *app* needs to work on every
*target*. The matrix shows that grid, which cells are filled in, and which are
missing.

Put a `matrix` fence on any page or annotation:

````markdown
```plugin matrix
{:title "Shapes"
 :dims {:type ["circle" "square" "triangle"]
        :operation ["area" "draw" "serialize"]}
 :pattern ["handlers/{type}/{operation}.clj"
           "handlers/default/{operation}.clj"]
 :manual [["circle" "draw" "src/render.clj#draw-circle"]
          ["triangle" "serialize" :n/a]]}
```
````

This draws a 3 × 3 grid. For the cell (circle, area), Sunlit Clearing looks
for `handlers/circle/area.clj`, then for `handlers/default/area.clj`. Each
cell links to whatever it found.

### The options

- **`:dims`** names the dimensions and lists their values, in order. Write
  `:auto` instead of a list to collect the values from the files that match
  the pattern.
- **`:pattern`** says where to find each cell's code. Give one pattern or
  several, tried in order.
  - `{type}` stands for the dimension's value.
  - `*` and `**` are wildcards (within one folder / across folders).
  - `#name` points at a definition inside the file, and can use dimensions
    too, e.g. `"src/shapes.clj#{operation}-{type}"`.
  - A pattern that leaves out some dimensions acts as a shared default:
    cells it fills are shown as *inherited*.
- **`:manual`** fills in cells by hand: `[value value … what]`, with values
  in `:dims` order. `what` is a link (`"src/x.clj#fn"`), a status (`:done`,
  `:todo`, `:n/a`), or a map `{:link … :status … :note …}`. Manual entries
  win over patterns.
- **`:check`** is a small function for cells that one path can't describe
  (see below).
- **`:rows`** / **`:cols`** choose which dimensions run down and across.
  With three or more dimensions, the others become tabs, plus a Summary tab.

### Cell statuses

| Status | Meaning |
|--------|---------|
| done | found, or marked done |
| inherited | covered by a shared default |
| todo | still to do (checklists) |
| missing | expected but not found |
| broken | a manual link that points nowhere |
| error | a check function failed or took too long |
| n/a | deliberately not needed |

Below the grid, an issues list shows each missing cell with the path it
expected, and any *orphans*: files that match a pattern but whose values
aren't in `:dims` (a typo, or a dimension value you forgot to list).

### Editing in the grid

You don't have to edit the EDN by hand. Click ✎ (or double-click a cell) to
set its link, status or note, and use the controls to swap rows and columns.
Sunlit Clearing rewrites only the changed entries in the fence, keeping your
comments and layout. It only ever changes the page or annotation the fence
lives in, never your source code.

### Checklists

Leave out both `:pattern` and `:check` and the matrix becomes a checklist.
Every cell has a tick box, and empty cells show as *todo* rather than
*missing*:

````markdown
```plugin matrix
{:title "Release checklist"
 :dims {:step ["changelog" "tag" "build" "announce"]
        :platform ["linux" "mac" "windows"]}
 :manual []}
```
````

### Check functions

Sometimes "is this cell done?" isn't one file. For example, an app supports a
target when *any* of its source files has an `#if <target>` block. A `:check`
function answers that question for each cell:

````markdown
```plugin matrix
{:title "Apps × targets"
 :dims {:app ["editor" "viewer" "player"]
        :target ["js" "cpp" "hl"]}
 :rows :app
 :cols :target
 :check (fn [{:keys [app target]}]
          (let [hits (grep (re-pattern (str "#if\\s+" target))
                           (str "src/" app "/**/*.hx"))]
            (if (seq hits)
              {:status :ok
               :links (map #(link (:path %) (:line %)) hits)}
              {:status :missing
               :warn (str "no #if " target " block under src/" app "/")})))
 :manual [["player" "hl" :n/a]]}
```
````

The function is called once per cell with that cell's values, e.g.
`{:app "editor" :target "js"}`. It returns:

- `{:status :ok}` or `{:status :missing :warn "why"}`,
- optionally with `:links ["path#anchor" …]` and a `:note`.

Returning plain `true`, `false` or `nil` also works.

Some things to know:

- Cells already decided in `:manual` (by a link or status) aren't checked.
- Checks run every time the page is shown, so keep them quick. All the
  checks in one matrix share a 10-second budget; cells still unchecked when
  it runs out show as *error*.
- With three or more dimensions, every cell in every tab is checked.
- For a long check, put it in a file under `.sunlit/` (e.g.
  `.sunlit/checks/targets.clj`, ending with the function) and write
  `:check "checks/targets.clj"`.

Check code runs in a sandbox. It can read the repo but not change anything,
and it can't see `.sunlit/` or ignored folders. It has these helpers:

| Helper | Gives you |
|--------|-----------|
| `(files)` / `(files "src/**/*.hx")` | repo paths, optionally matching a pattern |
| `(exists? path)` | whether the file exists |
| `(slurp-file path)` / `(lines path)` | the text, or a vector of lines (`nil` for missing or binary files) |
| `(grep pattern)` / `(grep pattern glob)` | `[{:path :line :text}]` matches; the pattern is a regex or plain string |
| `(find-symbol path name)` | `{:line :end :exact}` for a definition, or `nil` |
| `(link path anchor)` | `"path#anchor"` |

`clojure.string` and the usual Clojure core functions are available too.

---

## 4. Conventions and issues

The **Conventions** tab checks the repo against a standard project layout
and shows a simple issue tracker.

### The layout check

The standard says which folders and files a project should have, which lines
its `.gitignore` should contain, and how structured documents (such as issue
files) should be written. The Conventions tab lists what matches and what
doesn't.

The standard is an EDN file. Sunlit Clearing uses the first one it finds:

1. `<repo>/.sunlit/template.edn`: a one-off override for this repo (use
   sparingly, because the main virtue of this is if you want to help impose consistency across multiple projects)
2. `~/.sunlit/template.edn`: your shared standard 
3. the default bundled with Sunlit Clearing

A template looks like this:

```clojure
{:title "Standard project layout"
 :directories [{:path "src"    :required true :description "Primary source"}
               {:path "issues" :required true :description "Issue tracker"}]
 :files       [{:path "README.md" :required true :description "Readme"}]
 :gitignores  [{:pattern ".sunlit/cache/" :required true :description "Ignore plugin/session cache"}
               {:pattern ".sunlit/venv/"  :required true :description "Ignore analysis venv"}]
 :formats     [{:name "issue"
                :match "issues/*.md"
                :filename "^\\d{4}-[A-Za-z0-9._-]+\\.md$"
                :tracker? true
                :fields [{:key "status"  :required true :enum ["open" "closed"]}
                         {:key "labels"  :required true :multiple true}
                         {:key "created" :required true :pattern "^\\d{4}-\\d{2}-\\d{2}$"}]}]}
```

A **format** applies to the files matching `:match` (`*` matches within a
folder, `**` across folders). `:filename` is a pattern every matching file
name must fit. Each field can be:

- `:required`: must be present
- `:enum`: must be one of these values
- `:multiple`: a list, each item checked against `:enum` if there is one
- `:pattern`: must match this regular expression

### Issues

A format marked `:tracker? true` is also shown as an issue list. With the
default template, issues are Markdown files in `issues/`, named
`NNNN-short-slug.md`, with a header block:

```markdown
---
id: 8
title: "if branches that are different variants of one sum type do not join"
status: open
labels: [front-end]
created: 2026-10-17
---

What's wrong, how to reproduce it…

## Comments

### 2026-10-18 14:02

Later findings, a fix, or why it was closed.
```

`id`, `title`, `status` (`open` or `closed`), `labels` and `created` are
required; `closed` (a date) is optional. The `id` must match the number in
the file name.

The tracker lists the issues with their status, title and labels, links each
one to its file, and counts the open and closed ones. Labels are free-form:
the tracker shows whichever labels your issues use, with a count for each. To
restrict a project to a fixed set, add `:enum ["bug" "docs" …]` to the
`labels` field in that repo's `.sunlit/template.edn`.

Because issues are plain files in the repo, they're versioned with the code,
and AI agents can read and write them as easily as you can.

---

## 5. Agent transcripts

If AI coding agents work on the repo, the **Sessions** tab shows their
conversations: what was asked, what the agent replied, and which tools it
used.

### Which agents

Sunlit Clearing reads the transcripts each agent already saves on your
machine. It needs no setup and sends nothing anywhere.

| Agent | Where it looks |
|-------|----------------|
| Claude Code | `~/.claude/projects/` (or `$CLAUDE_CONFIG_DIR/projects`) |
| Codex | `~/.codex/sessions/` (or `$CODEX_HOME/sessions`) |
| Cursor (editor chats) | `~/.config/Cursor/User/` |
| Cursor (agent CLI) | `~/.cursor/` |
| Pi | `~/.pi/agent/sessions/` |

A session belongs to the repo when the agent was started in the repo's folder
or any folder inside it.

### Using the Sessions tab

1. Click **Refresh cache** the first time, and whenever you want to pick up
   new sessions. It scans in the background (a few seconds; longer if an
   agent has a lot of history) and the list fills in when it's done.
2. The list shows each session, newest first: when it started, which agent,
   its title (when the agent gave it one) and how many messages it has.
3. Click a session to read it. Messages are labelled by role: user,
   assistant, and tool (tool calls and their results). Very long tool
   output is shortened, marked `…(truncated)`.
4. Click **Export Markdown** on a session to download it as a Markdown file,
   for example to keep it with the repo or share it.

### Searching

The search box above the list searches the text of every message in every
session (ignoring case). Results show the best matches first, with a snippet
of each; click one to open its session.

### Privacy

Transcripts can contain anything you or the agents typed, including code,
file contents and command output. Sunlit Clearing copies the repo's sessions
into `.sunlit/cache/sessions/`. Keep that folder out of version control (the
Conventions tab checks that `.sunlit/cache/` is in `.gitignore`).

Sunlit Clearing only accepts connections from your own machine
(127.0.0.1), so other people on your network can't open these pages.

### For scripts and agents

The same data is available as JSON:

| Request | Returns |
|---------|---------|
| `GET /api/sessions` | the session list |
| `GET /api/sessions/<id>` | one transcript |
| `POST /api/sessions/refresh` | starts a rescan |
| `GET /api/sessions/search?q=…` | matching messages; also `role=`, `from=` / `to=` (YYYY-MM-DD) and `limit=` |
| `GET /api/sessions/stats` | counts by agent and role, and the date range |
| `GET /api/sessions/<id>/export.md` | the transcript as Markdown |
