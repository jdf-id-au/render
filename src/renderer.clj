(ns renderer
  (:require [logo])
  (:import (java.util Objects)
           (java.util.function Consumer)
           (org.lwjgl.glfw GLFW Callbacks GLFWErrorCallback GLFWKeyCallbackI)
           (org.lwjgl.bgfx BGFX BGFXInit)
           (org.lwjgl.system Platform MemoryStack MemoryUtil)
           (java.util.concurrent CountDownLatch TimeUnit)
           (java.util.concurrent.atomic AtomicBoolean)))

(defn display-scale
  "Return window x-scale, y-scale, unscaled width and unscaled height."
  [window]
  (let [x (make-array Float/TYPE 1) ; could use FloatBuffer... any advantage?
        y (make-array Float/TYPE 1)
        w (make-array Integer/TYPE 1)
        h (make-array Integer/TYPE 1)]
    (GLFW/glfwGetWindowContentScale window x y) ; ratio between current dpi and platform's default dpi
    (GLFW/glfwGetWindowSize window w h) ; in screen coordinates
    (mapv first [x y w h])))

(def renderer* (atom nil))

(reset! renderer* ; C-M-x this (or could add-watch to normal defn)
  (fn renderer [window width height]
    ;; Oddly blurry on mac at fullscreen (when not hovering over menu bar)
    (BGFX/bgfx_set_view_rect 0 0 0 width height)
    (BGFX/bgfx_touch 0)
    (BGFX/bgfx_dbg_text_clear 0 false)
    (let [x (int (- (max (/ width 2 8) 20) 20))
          y (int (- (max (/ height 2 16) 16) 6))]
      (BGFX/bgfx_dbg_text_image x y 40 12
        (logo/logo) 160) ; TODO think about resource management, currently just memoized...
      (BGFX/bgfx_dbg_text_printf 0 0 0x1f "25-c99 -> java -> clojure"))))
