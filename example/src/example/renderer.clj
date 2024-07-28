(ns example.renderer
  "Example project using jdf/render."
  (:require [render.renderer :as rr]
            [render.util :as ru :refer [with-resource glfw GLFW bgfx BGFX]])
  (:import (org.lwjgl.system MemoryUtil)
           (org.joml Matrix4f Matrix4x3f Vector3f)))

#_(defn debug-text
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

(def cube-vertices
  [[-1.  1.  1. 0xff000000] ;; Double Double Double Long
   [ 1.  1.  1. 0xff0000ff]
   [-1. -1.  1. 0xff00ff00]
   [ 1. -1.  1. 0xff00ffff]
   [-1.  1. -1. 0xffff0000]
   [ 1.  1. -1. 0xffff00ff]
   [-1. -1. -1. 0xffffff00]
   [ 1. -1. -1. 0xffffffff]])

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
  (println "Setting up renderer on thread" (ru/current-thread))
  (let [layout (rr/make-vertex-layout false true 0)
        vertices (MemoryUtil/memAlloc (* 8 (+ (* 3 4) 4)))
        vbh (rr/make-vertex-buffer vertices layout cube-vertices)
        indices (MemoryUtil/memAlloc (* 2 (count cube-indices)))
        ibh (rr/make-index-buffer indices cube-indices)
        vs (rr/load-shader "vs_cubes")
        fs (rr/load-shader "fs_cubes")
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

(defn renderer
  [{:keys [view-buf proj-buf model-buf vbh ibh program] :as context}
   status width height time frame-time]
  (bgfx set-view-rect 0 0 0 width height)
  (bgfx touch 0)
  (bgfx dbg-text-printf 0 0 0x1f (str frame-time))

  (let [at (Vector3f. 0. 0. 0.)
        eye (Vector3f. (* 10 #_(Math/tan time)) (* 30 (Math/sin time)) -35. )
        view (doto (Matrix4x3f.)
               (.setLookAtLH
                 (.x eye) (.y eye) (.z eye)
                 (.x at) (.y at) (.z at)
                 0. 1. 0.))
        
        fov (* 60. #_(Math/sin time)) near 0.1 far 100.
        fov-radians (-> fov (* Math/PI) (/ 180))
        aspect (/ width (float height))
        proj (doto (Matrix4f.)
               (.setPerspectiveLH fov-radians aspect near far
                 (not (.homogeneousDepth (bgfx get-caps)))))

        _ (bgfx set-view-transform 0
                (.get4x4 view view-buf)
                (.get proj proj-buf))

        encoder (bgfx encoder-begin false)
        ]
    (doseq [yy (range 12) xx (range 12)]
      (bgfx encoder-set-transform encoder
            (-> (Matrix4x3f.)
              (.translation
                (-> xx (* 3.) (- 15))
                (-> yy (* 3.) (- 15))
                0.)
              (.rotateXYZ
                (-> xx (* 0.21) (+ time))
                (-> yy (* 0.37) (+ time))
                0.)
              (.get4x4 model-buf)))
      (bgfx encoder-set-vertex-buffer encoder 0 vbh 0 8)
      (bgfx encoder-set-index-buffer encoder ibh 0 36)
      (bgfx encoder-set-state encoder (BGFX state-default) 0)
      (bgfx encoder-submit encoder 0 program 0 0))
    
    (bgfx encoder-end encoder)))

(comment
  (@main/refresh-thread!)
  )
