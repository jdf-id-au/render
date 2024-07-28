# jdf/render

**BGFX** GPU graphics + **GLFW** &mdash; **Clojure** REPL

Read eval graphics loop! In the grand tradition of (but much less sophisticated than):

- https://github.com/cbaggers/cepl
- https://github.com/byulparan/cl-visual
- https://github.com/digego/extempore
- https://www.shadertoy.com/ https://iquilezles.org/

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

## TODO

- sane aspect ratio preservation
- rebindable GLFW callbacks
- orbit
- picking
- shader wrangling (BGFX shell tools...)
- videos
- ...