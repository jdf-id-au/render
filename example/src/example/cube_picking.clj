(ns example.cube-picking
  "Example project using jdf/render."
  (:require [render.renderer :as rr]
            [render.util :as ru :refer [with-resource glfw GLFW bgfx BGFX]]
            [render.shaders :as rs])
  (:import (org.lwjgl.system MemoryUtil)
           (org.joml Matrix4f Matrix4x3f Vector3f)
           (java.nio ShortBuffer)))

(def callbacks
  {:mouse/button-1 (fn [window action] (println "button-1" window action))
   [:mouse/button-1 :mod/shift] (fn [window action] (println "shift-button-1"))
   #_#_:scroll (fn [window x y] (println "scroll " x " " y))})

(def common
  {:varying "
vec4 v_color0 : COLOR0 = vec4(1.0, 0.0, 0.0, 1.0);
vec3 a_position : POSITION;
vec4 a_color0 : COLOR0;"
   :vertex "
$input a_position, a_color0
$output v_color0
void main() {
  gl_Position = mul(u_modelViewProj, vec4(a_position, 1.0) );
  v_color0 = a_color0;
}"})

(def shaders
  ;; Copyright 2011-2024 Branimir Karadzic. Modified.
  ;; License: https://github.com/bkaradzic/bgfx/blob/master/LICENSE
  {:cubes (assoc common :fragment "
$input v_color0
void main() {
  gl_FragColor = v_color0;
}")
   :pick (assoc common :fragment "
$input v_color0
uniform vec4 u_id;
void main() {
  gl_FragColor.xyz = u_id.xyz;
  gl_FragColor.w = 1.0;
}")})

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

(def context
  "Map of resource -> [create-fn destroy-fn] or [create-fn destroy-fn deps]"
  {:layout
   [#(rr/make-vertex-layout false true 0)
    #(.free %)]
   :vertices
   [#(MemoryUtil/memAlloc (* 8 (+ (* 3 4) 4)))
    #(MemoryUtil/memFree %)]
   :vbh
   [#(rr/make-vertex-buffer (:vertices %) (:layout %) cube-vertices)
    #(bgfx destroy-vertex-buffer %)
    #{:vertices :layout}]
   :indices
   [#(MemoryUtil/memAlloc (* 2 (count cube-indices)))
    #(MemoryUtil/memFree %)]
   :ibh
   [#(rr/make-index-buffer (:indices %) cube-indices)
    #(bgfx destroy-index-buffer %)
    #{:indices}]
   :shading-program
   [#(let [{:keys [vertex fragment]} (->> shaders :cubes rs/compile rr/load-shader)]
       (bgfx create-program vertex fragment true)) ; true destroyShaders
    #(bgfx destroy-program %)]
   :u-id
   [#(bgfx create-uniform "u_id" (BGFX uniform-type-vec4) 1)
    #(bgfx destroy-uniform %)]
   :t-id ; picking colour target
   [#(bgfx create-texture-2d 8 8 false 1 (BGFX texture-format-rgba8)
               (bit-or
                 (BGFX texture-rt)
                 (BGFX sampler-min-point)
                 (BGFX sampler-mag-point)
                 (BGFX sampler-mip-point)
                 (BGFX sampler-u-clamp)
                 (BGFX sampler-v-clamp)) nil)
    #(bgfx destroy-texture %)]
   :d-id ; picking depth buffer
   [#(bgfx create-texture-2d 8 8 false 1 (BGFX texture-format-d32f)
               (bit-or
                 (BGFX texture-rt)
                 (BGFX sampler-min-point)
                 (BGFX sampler-mag-point)
                 (BGFX sampler-mip-point)
                 (BGFX sampler-u-clamp)
                 (BGFX sampler-v-clamp)) nil)
    #(bgfx destroy-texture %)]
   :b-id ; CPU picking blit texture
   [#(bgfx create-texture-2d 8 8 false 1 (BGFX texture-format-rgba8)
               (bit-or
                 (BGFX texture-blit-dst)
                 (BGFX texture-read-back)
                 (BGFX sampler-min-point)
                 (BGFX sampler-mag-point)
                 (BGFX sampler-mip-point)
                 (BGFX sampler-u-clamp)
                 (BGFX sampler-v-clamp)) nil)
    #(bgfx destroy-texture %)]
   :fb-id
   [#(let [sa (short-array [(:t-id %) (:d-id %)])
           sb (doto (MemoryUtil/memAllocShort 2) (.put sa) .flip)] ; FIXME prob leaks
       (bgfx create-frame-buffer-from-handles sb true)) ; true destroyTextures
    #(bgfx destroy-frame-buffer %)
    #{:t-id :d-id}]
   :picking-program
   [#(let [{:keys [vertex fragment]} (->> shaders :pick rs/compile rr/load-shader)]
       (bgfx create-program vertex fragment true))
    #(bgfx destroy-program %)]
   :view-buf
   [#(MemoryUtil/memAllocFloat 16)
    #(MemoryUtil/memFree %)]
   :proj-buf
   [#(MemoryUtil/memAllocFloat 16)
    #(MemoryUtil/memFree %)]
   :model-buf
   [#(MemoryUtil/memAllocFloat 16)
    #(MemoryUtil/memFree %)]})

(defn renderer
  [{:keys [view-buf proj-buf model-buf vbh ibh
           shading-program] :as context}
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
      (bgfx encoder-submit encoder 0 shading-program 0 0))
    
    (bgfx encoder-end encoder)))

(comment
  (@main/refresh-thread!)
  )
