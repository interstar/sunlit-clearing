# Sunlit Clearing


Sunlit Clearing is meant to be a ray of light and clarity on my code repos.

It gives me a web-UI overview with a plugin based architecture. By default it shows a tree of the files in the repo. And adds a wiki-like "side-car" which allows them to be annotated.

There are plugins for architectural overviews, running unit tests and showing the results, showing test coverage, and running extra linters for style. These are *my* linters and so focused on the languages I use (Clojure, Haxe, Python, C++, JS). And are pretty opinionated. But you can add your own plugins and your own linters for your languages and style. 

The goal of Sunlit Clearing is to provide a great overview of what's going on in a project. Especially a project which might be worked on by AI agents. (We can also look at the transcripts of all the agents that have worked on the codebase).

The codebase is written in Clojure. And can be distributed as a runnable Uberjar.

**AI Disclosure : This codebase was written largely with the assistance of AI**

## V1 features

- Collapsible repository tree on the home page (zoom in / back out)
- File view with highlight.js colouring (Clojure, Python, C++, Haxe, JS/TS, Java, …)
- Annotations stored under `.sunlit/annotations/<path>.md` inside the watched repo
- Wiki-style links between files: `[[src/foo.clj]]` or `[[label|src/foo.clj]]`
- Links to locations inside files: `[[src/foo.clj#draw-circle]]` (symbol),
  `[[src/foo.clj#42]]` (line), `[[src/foo.clj#40-60]]` (range). The file view has
  a line-number gutter: click for a line permalink, shift-click for a range
- Plugin fences in annotations:

````markdown
```plugin leiningen
{}
```
````

- Built-in **leiningen** plugin (auto panel on `project.clj`, also via fence)
- Built-in **image** plugin (auto panel for PNG/JPEG/GIF/WebP/SVG/BMP/ICO/AVIF files, also via fence)
- Built-in **gallery** plugin (auto thumbnail grid when a directory contains images, also via fence)
- Built-in **malli** / **instaparse** plugins (scan `src/` for schemas & grammars)
- Optional user plugins as SCI scripts in `.sunlit/plugins/*.clj` (see [Writing plugins](docs/writing_plugins.md))
- Built-in **matrix** plugin: a grid for matrix-shaped code (types × operations,
  apps × targets × concerns), see the [guide](docs/guide.md#3-the-matrix)
- **Plugins** page listing built-in and repo plugins (with descriptions)
- **Conventions** page: check the repo against a declared layout template
  (directories, files, .gitignore rules, structured-document formats) + a lightweight issues list
  (see the [guide](docs/guide.md#4-conventions-and-issues))
- Agent **sessions** from Codex, Pi, Claude Code and Cursor: list, read, search, export (see the [guide](docs/guide.md#5-agent-transcripts))

## Quick start (dev)

```bash
# compile CLJS once, then serve this repo (or pass another path)
./go.sh
# or:
lein cljs
lein run -m sunlit.core -- --port 4567 /path/to/repo
```

Open http://localhost:4567/

### Figwheel (interactive CLJS)

```bash
lein fig
# in another terminal:
lein run -m sunlit.core -- --port 4567 .
```

## Install (uberjar)

Rebuild from scratch and install locally:

```bash
./scripts/install-local
```

That produces:

```text
~/bin/clearing.jar
~/bin/sunlit
~/.local/lib/mtc/vendor/clearing.jar   # for Mind Traffic Control / Basket
```

Then (with `~/bin` on your `PATH`):

```bash
sunlit --port 4567 /path/to/repo
```

Use `./scripts/install-local --skip-clean` for a faster incremental rebuild.

## Documentation

- **[User guide](docs/guide.md)**: architecture overviews; unit tests,
  coverage and linters; the matrix; conventions and issues; agent
  transcripts.
- **[Writing plugins](docs/writing_plugins.md)**: how to write repo plugins
  and built-in plugins, including slow background ones.

## Developing the bundled linters

The `mystyle-*` linters are vendored into `scripts/` (dev) and
`resources/scripts/` (bundled into the uberjar) from their canonical source,
`self-written-tools/mystyle/`. `mystyle-js` additionally needs `tree-sitter`,
`tree-sitter-javascript`, and `tree-sitter-typescript` installed for the
interpreter in use, and `mystyle-cpp` needs `tree-sitter-cpp`. `mystyle-haxe` is a Linux x86-64 binary built from
`mystyle/haxe/` (`./build.sh`); copy `bin/mystyle-haxe` into both directories
after rebuilding. Without a vendored copy, sunlit falls back to `mystyle-haxe`
on PATH.

Python analysis tools (pytest, coverage, and the tree-sitter grammars for the
mystyle linters) live in `.sunlit/venv`, created on the first Python
test/coverage/lint run. A venv missing any of them is topped up on its next
use. The Python mystyle linters run in it, falling back to `SUNLIT_PYTHON` or
`~/.main_python_venv` if it can't be set up. Dev requirements: `pip install -r requirements-dev.txt`.

## Locations

A location is `path#anchor`, where the anchor is a line (`42`, `L42`), a range
(`40-60`) or a symbol. Symbols resolve heuristically per language
(`sunlit.locate`, no parsing): the least-indented matching definition wins,
then the first whole-word mention, then the first literal mention. Qualified
names (`Shape.area`, `Shape::area`) look inside the container's block; free
text such as `defmethod area :circle` works through the literal fallback.
Client routes: `#/file/<path>?line=40-60` and `#/file/<path>?sym=name`.

## Annotation & pages

```
.sunlit/
  annotations/     # sidecar notes for repo files
  pages/           # free-titled wiki pages (_index = tree notes)
  cache/plugins/   # long-running plugin outputs
  plugins/         # optional SCI project plugins
  template.edn     # optional per-repo conventions override
  venv/            # private Python for pytest / coverage
```

### Version control

`.sunlit/` mixes authored content with generated data. Commit the authored
parts (`annotations/`, `pages/`, `plugins/`, and a per-repo `template.edn`),
and ignore the generated parts. Add these lines to the watched repo's
`.gitignore`:

```gitignore
.sunlit/cache/
.sunlit/venv/
```

The **Conventions** page checks for these two ignore rules.

The server listens on 127.0.0.1 only, since these pages show source code and
agent transcripts.

## Non-goals (v1)

- Editing source files
- Git integration
- GraphQL
- Live file watching (eyeball watch) — later
- LLM analysis — later
