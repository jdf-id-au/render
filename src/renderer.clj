(ns renderer
  (:require [logo]
            [util :refer [glfw bgfx]])
  (:import (java.util Objects)
           (java.util.function Consumer)
           (org.lwjgl.glfw GLFW Callbacks GLFWErrorCallback GLFWKeyCallbackI)
           (org.lwjgl.bgfx BGFX BGFXInit)
           (org.lwjgl.system Platform MemoryStack MemoryUtil)
           (org.joml Vector3f)
           (java.util.concurrent CountDownLatch TimeUnit)
           (java.util.concurrent.atomic AtomicBoolean)))



(defn display-scale
  "Return window x-scale, y-scale, unscaled width and unscaled height."
  [window]
  (let [x (make-array Float/TYPE 1) ; could use FloatBuffer... any advantage?
        y (make-array Float/TYPE 1)
        w (make-array Integer/TYPE 1)
        h (make-array Integer/TYPE 1)]
    (glfw get-window-content-scale window x y) ; ratio between current dpi and platform's default dpi
    (glfw get-window-size window w h) ; in screen coordinates
    (mapv first [x y w h])))

(defn debug-text
  [cols lines]
  ;; cols and lines seem to be ORIGINAL dimensions ("resolution") / 8x16
  (bgfx dbg-text-clear 0 false)
  (let [n (count logo/raw) ; 4000
        pitch 160 ; image pitch in bytes (4x number of cols?)
        lines (/ n 160) ; 25? meaning?
        x (int (/ (- cols 40) 2))
        y (int (/ (- lines 12) 2))]
    ;; Coords in characters not pixels
    (bgfx dbg-text-printf 0 0 0x1f (str cols "x" lines))
    #_(bgfx dbg-text-image  x y 40 12 (logo/logo) pitch)))







(defonce renderer* (atom nil))
(reset! renderer* ; C-M-x this (or could add-watch to normal defn)
  (fn renderer [width height]
    (Thread/sleep 500)
    
    (bgfx set-view-rect 0 0 0 width height)
    
    (bgfx touch 0)
    
    
    ))

