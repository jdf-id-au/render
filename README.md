# jdf/render

**BGFX** GPU graphics + **GLFW** &mdash; **Clojure** REPL

Read eval graphics loop! In the grand tradition of (but much less sophisticated than):

- https://github.com/cbaggers/cepl
- https://github.com/byulparan/cl-visual
- https://github.com/digego/extempore
- https://www.shadertoy.com/
- https://github.com/oakes/play-cljc
- https://github.com/roman01la/minimax

## Getting started

Uses [`jdf/comfort`](https://github.com/jdf-id-au/comfort) as well.

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

## TODO

- sane aspect ratio preservation
- orbit
- picking
- save to video
- ...

## Shaders

Downloaded binaries at your own risk, or compile from bgfx source...

- get cli binaries (...) from https://www.lwjgl.org/browse/release/3.3.4/macosx/x64/bgfx-tool etc (must match deps version)
- put on path
- `chmod u+x`
- `xattr -dr com.apple.quarantine "shaderc"` etc