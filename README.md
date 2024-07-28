# jdf/render

**BGFX** GPU graphics + **GLFW** &mdash; **Clojure** REPL

Read eval graphics loop! In the grand tradition of (but much less sophisticated than):

- https://github.com/cbaggers/cepl
- https://github.com/byulparan/cl-visual
- https://github.com/digego/extempore
- https://www.shadertoy.com/
- https://github.com/oakes/play-cljc

## Getting started

### macOS

- shell `% cd example`
- shell `% clj -M:macos-x64 -m example.main`
- emacs `cider-connect-clj` to localhost port 12345
- `(-main)` will already be running

### Windows

- emacs `(setq cider-clojure-cli-global-options "-A:windows-x64")`
- emacs `cider-jack-in-clj` (`example` subproject)
- `(-main)`

### Linux

(untested, need vulkan shaders)

## API

- `render.core/main` function
- `render.core/retry-on-window-close?` atom
- `render.renderer/make-vertex-layout` function
- `render.renderer/make-vertex-buffer` function
- `render.renderer/make-index-buffer` function
- `render.renderer/load-shader` function
- `render.renderer/load-texture` function
- `render.util/bgfx` and `BGFX` macros
- `render.util/glfw` and `GLFW` macros
- `render.util/with-resource` macro (copypaste from https://github.com/jdf-id-au/comfort )

## TODO

- sane aspect ratio preservation
- orbit
- picking
- shader wrangling (BGFX shell tools...)
- save to video
- ...