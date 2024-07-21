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
    (Thread/sleep 500)
    (let [dbg-cols (int (Math/floor (/ width 8)))
          dbg-lines (int (Math/floor (/ height 16)))
          [_ _ width height] (display-scale window)]
      (BGFX/bgfx_set_view_rect 0 0 0 width height)
      (BGFX/bgfx_touch 0)
      (BGFX/bgfx_dbg_text_clear 0 false)
      (let [n (count logo/raw) ; 4000
            pitch 160 ; image pitch in bytes (4x number of cols?)
            lines (/ n 160) ; 25? meaning?
            x (int (/ (- dbg-cols 40) 2))
            y (int (/ (- dbg-lines 12) 2))]
        (println width height x y)
        ;; Coords in characters not pixels
        (BGFX/bgfx_dbg_text_printf 0 30 0x1f "25-c99 -> java -> clojure")
        (BGFX/bgfx_dbg_text_image x y 40 12 (logo/logo) pitch)))))
