(ns renderer
  (:require [logo]
            [util :refer [glfw GLFW bgfx BGFX]])
  (:import (java.util Objects)
           (java.util.function Consumer)
           (org.lwjgl.glfw Callbacks GLFWErrorCallback GLFWKeyCallbackI)
           (org.lwjgl.bgfx BGFXInit
             BGFXVertexLayout)
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

;; Environment ─────────────────────────────────────────────────────────────────
(defn make-vertex-layout [normals? colour? nUVs]
  (let [layout (BGFXVertexLayout/calloc)]
    (bgfx vertex-layout-begin layout (bgfx get-renderer-type))
    (bgfx vertex-layout-add layout
      (BGFX attrib-position) 3 (BGFX attrib-type-float) false false)
    (when normals?
      (bgfx vertex-layout-add layout
        (BGFX attrib-normal) 3 (BGFX attrib-type-float) false false))
    (when colour?
      (bgfx vertex-layout-add layout
        (BGFX attrib-color0) 4 (BGFX attrib-type-uint8) true false))
    (when (pos? nUVs)
      (bgfx vertex-layout-add layout
        (BGFX attrib-texcoord0) 2 (BGFX attrib-type-float) false false))
    (bgfx vertex-layout-end layout)
    layout))

(defn make-vertex-buffer
  ([buffer layout]
   (bgfx create-vertex-buffer (bgfx make-ref buffer) layout (BGFX buffer-none)))
  ([buffer layout vertices]
   (for [vertex vertices
         attr vertex]
     (cond
       (float? attr) (.putFloat buffer attr)
       (integer? attr) (.putInteger buffer attr)))
   (assert (zero? (.remaining buffer)))
   (.flip buffer)
   (make-vertex-buffer buffer layout))
  )

(defn make-environment []
  (let [layout (make-vertex-layout false true 0)
        vertices (MemoryUtil/memAlloc (* 8 (+ (* 3 4) 4)))
        vbh (make-vertex-buffer vertices layout cube-vertices)]
    {:layout layout
     :vertices vertices
     :vbh ; vertex buffer handle?
     :indices
     :ibh
     :vs
     :fs
     :program
     :view-buf
     :proj-buf
     :model-buf
     }))

(defn close-environment [env])

;; Renderer ────────────────────────────────────────────────────────────────────

(defonce renderer* (atom nil))
(reset! renderer* ; C-M-x this (or could add-watch to normal defn)
  (fn renderer [width height]
    (Thread/sleep 500)
    
    (bgfx set-view-rect 0 0 0 width height)
    
    (bgfx touch 0)
    
    
    ))

(defn make [window width height]
  (println "Making renderer")
  (cc/with-resource [stack (MemoryStack/stackPush) nil
                     init (BGFXInit/malloc stack) nil]
    (bgfx init-ctor init)
    (.resolution init (reify Consumer ; does this mean it should detect change?
                        (accept [this o]
                          (.width o width)
                          (.height o height)
                          (.reset o (BGFX reset-vsync)))))
    (println "Checking platform")
    (condp = (Platform/get)
      Platform/LINUX
      (doto (.platformData init)
        (.ndt (org.lwjgl.glfw.GLFWNativeX11/glfwGetX11Display))
        (.nwh (org.lwjgl.glfw.GLFWNativeX11/glfwGetX11Window window)))
      Platform/MACOSX
      (doto (.platformData init)
        (.nwh (org.lwjgl.glfw.GLFWNativeCocoa/glfwGetCocoaWindow window)))
      Platform/WINDOWS
      (doto (.platformData init)
        (.nwh (org.lwjgl.glfw.GLFWNativeWin32/glfwGetWin32Window window))))
    (println "Initialising bgfx")
    (assert (bgfx init init))
    (println "bgfx renderer:" (bgfx get-renderer-name (bgfx get-renderer-type)))
    (bgfx set-debug (BGFX debug-text))
    (bgfx set-view-clear 0 (bit-or (BGFX clear-color) (BGFX clear-depth))
      0x303030ff 1.0 0)
    renderer*))

(defn close [_]
  (println "Closing renderer")
  (bgfx shutdown))
