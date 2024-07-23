(ns renderer
  (:require [logo]
            [util :refer [glfw GLFW bgfx BGFX]]
            [clj-commons.primitive-math :as m]
            [comfort.core :as cc]
            [clojure.java.io :as io])
  (:import (java.util Objects)
           (java.util.function Consumer)
           (org.lwjgl.glfw Callbacks GLFWErrorCallback GLFWKeyCallbackI)
           (org.lwjgl.bgfx BGFXInit
             BGFXVertexLayout
             BGFXReleaseFunctionCallback BGFXReleaseFunctionCallbackI)
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

(defn make-vertex-layout [normals? colour? nUVs] ; ─────────────────────── setup
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
   (doseq [vertex vertices
           attr vertex]
     (println "buffering" attr)
     (cond
       (float? attr) (.putFloat buffer attr)
       (integer? attr) (.putInt buffer attr)))
   (assert (zero? (.remaining buffer)))
   (.flip buffer)
   (println "Calling bgfx_create_vertex_buffer")
   (make-vertex-buffer buffer layout)))

(defn make-index-buffer [buffer indices]
  (doseq [index indices] (.putShort buffer index))
  (assert (zero? (.remaining buffer)))
  (.flip buffer)
  (bgfx create-index-buffer (bgfx make-ref buffer) (BGFX buffer-none)))

(defn load-resource [path]
  (let [r (io/resource path)
        ;; memAlloc (C malloc, "off heap") vs BufferUtils/createByteBuffer ?
        res (MemoryUtil/memAlloc (-> r .openConnection .getContentLength))]
    (with-open [is (io/input-stream r)]
      (loop [b (.read is)]
        (when (not= b -1)
          (.put res (m/ubyte->byte b))
          (recur (.read is)))))
    (.flip res)))

(def release-memory-cb
  (BGFXReleaseFunctionCallback/create
    (reify BGFXReleaseFunctionCallbackI
      (invoke [this ptr user-data] (MemoryUtil/nmemFree ptr)))))

(defn load-shader [s]
  (let [code (load-resource
               (str "shaders/"
                 (condp = (bgfx get-renderer-type)
                   (BGFX renderer-type-direct3d12) "dx11/"
                   (BGFX renderer-type-opengl) "glsl/"
                   (BGFX renderer-type-metal) "metal/") s))]
    (bgfx create-shader (bgfx make-ref-release code release-memory-cb nil))))

(defn load-texture [s]
  (let [data (load-resource (str "textures/" s))]
    (bgfx create-texture
      (bgfx make-ref-release data release-memory-cb nil)
      (BGFX texture-none) 0 nil)))

(def cube-vertices
  [[-1. 1. 1. 0xff000000]
   [1. 1. 1. 0xff0000ff]
   [-1. -1. 1. 0xff00ff00]
   [1. -1. 1. 0xff00ffff]
   [-1. 1. -1. 0xffff0000]
   [1. 1. -1. 0xffff00ff]
   [-1. -1. -1. 0xffffff00]
   [1. -1. -1. 0xffffffff]])

(def cube-indices
  [0 1 2
   1 3 2
   4 6 5
   5 6 7
   0 2 4
   4 2 6
   1 5 3
   5 7 3
   0 4 1
   4 5 1
   2 3 6
   6 3 7])

(defn setup []
  (println "Setting up renderer") ; then segfault (run from cli to see)
  (let [layout (make-vertex-layout false true 0)
        vertices (MemoryUtil/memAlloc (* 8 (+ (* 3 4) 4)))
        vbh (make-vertex-buffer vertices layout cube-vertices)
        _ (println "indices")
        indices (MemoryUtil/memAlloc (* 2 (count cube-indices)))
        _ (println "ibh")
        ibh (make-index-buffer indices cube-indices)
        _ (println "vs")
        vs (load-shader "vs_cubes")
        fs (load-shader "fs_cubes")
        program (bgfx create-program vs fs true)
        view-buf (MemoryUtil/memAllocFloat 16)
        proj-buf (MemoryUtil/memAllocFloat 16)
        model-buf (MemoryUtil/memAllocFloat 16)]
    {:layout layout
     :vertices vertices
     :vbh vbh ; vertex buffer handle presumably
     :indices indices
     :ibh ibh
     :vs vs
     :fs fs
     :program program
     :view-buf view-buf
     :proj-buf proj-buf
     :model-buf model-buf}))

(defn teardown [{:keys [view-buf proj-buf model-buf
                        program ibh indices vbh vertices layout]}]
  (println "Tearing down renderer")
  (MemoryUtil/memFree view-buf)
  (MemoryUtil/memFree proj-buf)
  (MemoryUtil/memFree model-buf)
  (bgfx destroy-program program)
  (bgfx destroy-index-buffer ibh)
  (MemoryUtil/memFree indices)
  (bgfx destroy-vertex-buffer vbh)
  (MemoryUtil/memFree vertices)
  (.free layout))

(defonce renderer* (atom nil)) ; ────────────────────────────────────── renderer
(reset! renderer* ; C-M-x this (or could add-watch to normal defn)
  (fn renderer [width height]
    (Thread/sleep 500)
    
    (bgfx set-view-rect 0 0 0 width height)
    
    (bgfx touch 0)
    (bgfx dbg-text-printf 0 0 0x1f "sadness")
    
    
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
