(ns render.util
  (:require [clojure.string :as str]
            [comfort.core :as cc])
  (:import (java.io File)
           (org.joml Matrix4f Matrix4x3f Vector3f Vector4f)))

;; TODO gen/import docs for repl use...

(defn rename ; ───────────────────────────────────────────── ergonomic API calls
  [prefix converter sym]
  (symbol (str prefix (converter sym))))

(defmacro bgfx
  "Briefer function calls"
  {:clj-kondo/ignore [:unresolved-symbol]}
  [function-name & args]
  (let [f (rename 'org.lwjgl.bgfx.BGFX/bgfx_ cc/kebab->snake function-name)]
    `(~f ~@args)))

(defmacro BGFX ; needs to be macro so can use unquoted symbols
  "Briefer enums"
  {:clj-kondo/ignore [:unresolved-symbol]}
  [& enum-names]
  (let [f (partial rename 'org.lwjgl.bgfx.BGFX/BGFX_ cc/kebab->screaming-snake)
        [e & r] (map f enum-names)]
    (if r `(bit-or ~e ~@r) e)))

(defmacro glfw
  "Briefer function calls"
  {:clj-kondo/ignore [:unresolved-symbol]}
  [function-name & args]
  (let [f (rename 'org.lwjgl.glfw.GLFW/glfw cc/kebab->pascal function-name)]
    `(~f ~@args)))

(defmacro GLFW
  "Briefer enums" ; via `public static final long` at least...
  {:clj-kondo/ignore [:unresolved-symbol]}
  [& enum-names]
  (let [f (partial rename 'org.lwjgl.glfw.GLFW/GLFW_ cc/kebab->screaming-snake)
        [e & r] (map f enum-names)]
    (if r `(bit-or ~e ~@r) e)))

(comment
  ;; try C-c RET for cider-macroexpand-1
  (bgfx create-frame-buffer-from-handles sb true)
  (BGFX texture-blit-dst texture-read-back)
  (glfw get-cursor-pos window cur-x cur-y)
  (GLFW cocoa-retina-framebuffer)
  )

(defprotocol MathFormat
  (notate [this]))

(extend-protocol MathFormat
  Matrix4f
  (notate [x] (cc/bracket 4 
              (.m00 x) (.m10 x) (.m20 x) (.m30 x)
              (.m01 x) (.m11 x) (.m21 x) (.m31 x)
              (.m02 x) (.m12 x) (.m22 x) (.m32 x)
              (.m03 x) (.m13 x) (.m23 x) (.m33 x)))
  Matrix4x3f
  (notate [x] (cc/bracket 4 
              (.m00 x) (.m10 x) (.m20 x) (.m30 x)
              (.m01 x) (.m11 x) (.m21 x) (.m31 x)
              (.m02 x) (.m12 x) (.m22 x) (.m32 x)))
  Vector4f
  (notate [x] (cc/bracket 4 (.x x) (.y x) (.z x) (.w x)))
  Vector3f
  (notate [x] (cc/bracket 3 (.x x) (.y x) (.z x))))

(defn display-scale
  "Return window x-scale, y-scale, and unscaled width and height."
  [window]
  (let [x (float-array 1)
        y (float-array 1)
        w (int-array 1)
        h (int-array 1)]
    (glfw get-window-content-scale window x y) ; ratio between current dpi and platform's default dpi
    (glfw get-window-size window w h) ; in screen coordinates
    (mapv first [x y w h])))

(defn cursor-pos
  "Return window cursor-x, cursor-y, and unscaled width and height."
  [window]
  (let [x (double-array 1)
        y (double-array 1)
        w (int-array 1)
        h (int-array 1)]
    (glfw get-cursor-pos window x y)
    (glfw get-window-size window w h) ; in screen coordinates
    (mapv first [x y w h])))
