(ns example.cube-picking
  "Example project using jdf/render."
  (:require [render.renderer :as rr]
            [render.util :as ru :refer [with-resource glfw GLFW bgfx BGFX]]
            [render.shaders :as rs])
  (:import (org.lwjgl.system MemoryUtil)
           (org.joml Matrix4f Matrix4x3f Vector3f)))

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

(def render-pass
  "bgfx_view_id_t"
  {:shading 0
   :id 1
   :blit 2})

(def id-dim 8)

(comment
  (rr/check-setup context)
  )

(def context
  "Map of resource -> [create-fn destroy-fn] or [create-fn destroy-fn deps]"
  {:layout
   [#(rr/make-vertex-layout false true 0)
    #(.free %)]
   :vertices ; use MemoryUtil when need SomethingBuffer...
   ;; This doesn't crash but also doesn't work.
   #_[#(java.nio.ByteBuffer/allocateDirect (* 8 (+ (*  3 4) 4)))]
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
   [#(bgfx create-texture-2d id-dim id-dim false 1 (BGFX texture-format-rgba8)
       (BGFX texture-rt
         sampler-min-point
         sampler-mag-point
         sampler-mip-point
         sampler-u-clamp
         sampler-v-clamp) nil)
    #(bgfx destroy-texture %)]
   :d-id ; picking depth buffer
   [#(bgfx create-texture-2d id-dim id-dim false 1 (BGFX texture-format-d32f)
       (BGFX texture-rt
         sampler-min-point
         sampler-mag-point
         sampler-mip-point
         sampler-u-clamp
         sampler-v-clamp) nil)
    #(bgfx destroy-texture %)]
   :b-id ; CPU picking blit texture
   [#(bgfx create-texture-2d id-dim id-dim false 1 (BGFX texture-format-rgba8)
       (BGFX texture-blit-dst
         texture-read-back
         sampler-min-point
         sampler-mag-point
         sampler-mip-point
         sampler-u-clamp
         sampler-v-clamp) nil)
    #(bgfx destroy-texture %)]
   :fb-id
   [#(let [sa (short-array [(:t-id %) (:d-id %)])
           sb (doto (MemoryUtil/memAllocShort 2) (.put sa) .flip)] ; FIXME prob leaks
       (bgfx create-frame-buffer-from-handles sb true)) ; true destroyTextures
    #(bgfx destroy-frame-buffer %)
    #{:t-id :d-id}]
   :picking-program
   [#(let [{:keys [vertex fragment]} (->> shaders :pick rs/compile rr/load-shader)]
       (when-not (rr/supported? (BGFX caps-texture-blit))
         (throw (ex-info "texture blit not supported" {:supported (.supported (bgfx get-caps))})))
       (bgfx create-program vertex fragment true))
    #(bgfx destroy-program %)]})

(defn renderer
  [{:keys [ vbh ibh
           shading-program] :as context}
   {:keys [window] :as status} width height time frame-time]
  #_(bgfx touch (:shading render-pass))
  #_(bgfx dbg-text-printf 0 0 0x1f (str (format "%.2f" frame-time)))
  
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
        inv-proj (Matrix4f.)
        _ (.invert proj inv-proj)

        ;; potentially stack-allocated subject to jvm escape analysis?
        ;; not guaranteed not to be moved?? risk invalidating glfw's "pointer"?
        ;; revert to MemoryUtil and explicit frees if crashy
        view-buf (float-array 16)
        proj-buf (float-array 16)
        model-buf (float-array 16)

        ;; TODO explore bgfx window/fb size concepts
        cur-x (double-array 1) cur-y (double-array 1)
        win-w (int-array 1) win-h (int-array 1)
        _ (glfw get-cursor-pos window cur-x cur-y)
        _ (glfw get-window-size window win-w win-h)

        encoder (bgfx encoder-begin false)]

    (doseq [[i s] (map-indexed vector [(format "t=%.2f" time)
                                       (format "f=%.2f" frame-time)
                                       (format "x=%.2f" (get cur-x 0))
                                       (format "y=%.2f" (get cur-y 0))
                                       (format "w=%d" (get win-w 0))
                                       (format "h=%d" (get win-h 0))])]
      (bgfx dbg-text-printf 0 i 0x1f s))
    
    (bgfx set-view-rect (:shading render-pass) 0 0 width height)
    (bgfx set-view-transform (:shading render-pass)
                (.get4x4 view view-buf)
                (.get proj proj-buf))
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
      (bgfx encoder-submit encoder (:shading render-pass) shading-program 0 0))
    
    (bgfx encoder-end encoder)))

(comment
  (@main/refresh-thread!)
  )
