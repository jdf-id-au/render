(ns example.cube-picking
  "Example project using jdf/render."
  (:require [render.renderer :as rr]
            [render.util :as ru :refer [with-resource glfw GLFW bgfx BGFX
                                        hex-str abgr->argb]]
            [render.shaders :as rs]
            [render.core :as rc]
            [clojure.java.io :as io])
  (:import (org.lwjgl.system MemoryUtil)
           (org.joml Matrix4f Matrix4x3f Vector3f Vector4f)
           (java.awt.image BufferedImage)
           (javax.imageio ImageIO)))

(def save? (atom false))

(def callbacks
  {:mouse/button-1 (fn [window action] #_(println "saving!" window action @save?)
                     (when (pos? action) (swap! save? not)))
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
  gl_Position = mul(u_modelViewProj, vec4(a_position, 1.0));
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
uniform vec4 u_pick;
void main() {
  gl_FragColor.rgba = u_pick.rgba;
  //gl_FragColor.b = 1.0;
  //gl_FragColor.a = 1.0;
}")})

(add-watch #'shaders :refresh (fn [_ _ _ _] (@rc/refresh!)))

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
  [0 1 2, 1 3 2, 4 6 5, 5 6 7, 0 2 4, 4 2 6,
   1 5 3, 5 7 3, 0 4 1, 4 5 1, 2 3 6, 6 3 7])

(def pass
  "bgfx_view_id_t"
  {:shading 0
   :id 1
   :blit 2})

(def pick-dim 128 #_8)

(defn id->uniform [x y]
  (float-array [(/ x 12.) (/ y 12.) 1. 1.])) ; rgba here, reads back as abgr!

(defn abgr->id [i]
  (let [a (bit-and (bit-shift-right i 24) 0xFF)
        b (bit-and (bit-shift-right i 16) 0xFF)
        g (bit-and (bit-shift-right i  8) 0xFF)
        r (bit-and i 0xFF)
        id #(-> % float (/ 0xFF) (* 12))]
    [(id r) (id g)]))

(comment
  (rr/check-setup context)
  )

(def context
  "Map of resource -> [create-fn destroy-fn] or [create-fn destroy-fn deps]"
  {:layout [#(rr/make-vertex-layout false true 0) #(.free %)]
   :vertices [#(MemoryUtil/memAlloc (* 8 (+ (* 3 4) 4))) #(MemoryUtil/memFree %)]
   :vertex-buffer [#(rr/make-vertex-buffer (:vertices %) (:layout %) cube-vertices)
                   #(bgfx destroy-vertex-buffer %)
                   #{:vertices :layout}]
   :indices [#(MemoryUtil/memAlloc (* 2 (count cube-indices))) #(MemoryUtil/memFree %)]
   :index-buffer [#(rr/make-index-buffer (:indices %) cube-indices)
                  #(bgfx destroy-index-buffer %)
                  #{:indices}]
   :shading-program [#(let [{:keys [vertex fragment]} (->> shaders :cubes rs/compile rr/load-shader)]
                        (bgfx create-program vertex fragment true)) ; true destroyShaders
                     #(bgfx destroy-program %)]
   :pick-uniform [#(bgfx create-uniform "u_pick" (BGFX uniform-type-vec4) 1)
                  #(bgfx destroy-uniform %)]
   :pick-target [#(bgfx create-texture-2d pick-dim pick-dim false 1 (BGFX texture-format-rgba8)
                        (BGFX texture-rt
                              sampler-min-point sampler-mag-point sampler-mip-point
                              sampler-u-clamp sampler-v-clamp) nil)
                 #(bgfx destroy-texture %)]
   :pick-depth-buffer [#(bgfx create-texture-2d pick-dim pick-dim false 1 (BGFX texture-format-d32f)
                              (BGFX texture-rt
                                    sampler-min-point sampler-mag-point sampler-mip-point
                                    sampler-u-clamp sampler-v-clamp) nil)
                       #(bgfx destroy-texture %)]
   :pick-blit-texture [#(bgfx create-texture-2d pick-dim pick-dim false 1 (BGFX texture-format-rgba8)
                              (BGFX texture-blit-dst
                                    texture-read-back

                                    sampler-min-point sampler-mag-point sampler-mip-point
                                    sampler-u-clamp sampler-v-clamp) nil)
                       #(bgfx destroy-texture %)]
   :pick-data [#(MemoryUtil/memAlloc (* pick-dim pick-dim 4)) #(MemoryUtil/memFree %)]
   :pick-framebuffer [#(let [sa (short-array [(:pick-target %) (:pick-depth-buffer %)])
                             sb (doto (MemoryUtil/memAllocShort 2) (.put sa) .flip)] ; FIXME prob leaks
                         (bgfx create-frame-buffer-from-handles sb true)) ; true destroyTextures
                      #(bgfx destroy-frame-buffer %)
                      #{:pick-target :pick-depth-buffer}]
   :picking-program [#(let [{:keys [vertex fragment]} (->> shaders :pick rs/compile rr/load-shader)]
                        (when-not (rr/supported? (BGFX caps-texture-blit))
                          (throw (ex-info "texture blit not supported"
                                   {:supported (.supported (bgfx get-caps))})))
                        (bgfx create-program vertex fragment true))
                     #(bgfx destroy-program %)]})

(defn renderer
  [{:keys [vertex-buffer index-buffer shading-program
           pick-uniform pick-target pick-depth-buffer pick-blit-texture pick-data
           pick-framebuffer picking-program] :as context}
   {:keys [window] :as status} width height time frame-time]
  
  (let [at (Vector3f. 0. 0. 0.)
        eye (Vector3f. 0. 0. -40.)
        up (Vector3f. 0. 1. 0.)
        ;; left-handed i.e. x right, y up, z away; 4 cols vs math convention
        view (.setLookAtLH (Matrix4x3f.) eye at up)
        
        fov 60. near 0.1 far 100.
        fov-radians (-> fov (/ 180) (* Math/PI)) ; vertical
        aspect (/ width (float height))
        z0-1? (not (.homogeneousDepth (bgfx get-caps))) ; https://www.youtube.com/watch?v=o-xwmTODTUI
        proj (.setPerspectiveLH (Matrix4f.) fov-radians aspect near far z0-1?)

        ;; TODO explore bgfx window/fb size concepts
        cur-x (double-array 1) cur-y (double-array 1)
        win-w (int-array 1) win-h (int-array 1)
        _ (glfw get-cursor-pos window cur-x cur-y)
        _ (glfw get-window-size window win-w win-h)
        ;; ───────────────────────────────────────────────────────────── picking
        view-proj (.mul (Matrix4f. view) proj)
        pick-at (Vector3f.) ; more like pick-dir, same effect presumably
        pick-eye (Vector3f.)
        _ (.unprojectRay view-proj (get cur-x 0) (get cur-y 0) ; win: x y
            (int-array [0 0 (get win-w 0) (get win-h 0)]) ; viewport: x y w h
            pick-at pick-eye) ; origin dir, why swapped?

        pick-up (Vector3f. -1. 0. 0.) ; why?
        pick-view (.setLookAtLH (Matrix4x3f.) pick-eye pick-at pick-up)
        pick-proj (.setPerspectiveLH (Matrix4f.) fov-radians 1 near far z0-1?)

        ;; currently giving approx
        ;; 6,6           5,6
        ;;
        ;; 6,5           5,5
        ;; i.e. mainly too zoomed in (plus maybe flipped etc)
        ;; instead of (maybe)
        ;;  0, 0        11, 0
        ;;
        ;; 11, 1        11,11
        encoder (bgfx encoder-begin false)]

    (doseq [[i s] (map-indexed vector [(format "t=%.2f" time)
                                       (format "f=%.2f" frame-time)
                                       (format "xy=%.2f %.2f"
                                         (get cur-x 0) (get cur-y 0))
                                       (format "wh=%d %d"
                                         (get win-w 0) (get win-h 0))
                                       (format "exyz=%.2f %.2f %.2f"
                                         (.x eye) (.y eye) (.z eye))
                                       (format "axyz=%.2f %.2f %.2f"
                                         (.x at) (.y at) (.z at))
                                       (format "pexyz=%.5f %.5f %.5f"
                                         (.x pick-eye) (.y pick-eye) (.z pick-eye))
                                       (format "paxyz=%.5f %.5f %.5f"
                                         (.x pick-at) (.y pick-at) (.z pick-at))])]
      (bgfx dbg-text-printf 0 i 0x1f s))

    ;; render pass is needed to inspect a previous pass' generated image
    (bgfx set-view-clear (:id pass) (BGFX clear-color clear-depth)
          0x000000ff 1.0 0) ; should be in setup

    (bgfx set-view-frame-buffer (:id pass) pick-framebuffer)
    (bgfx set-view-rect (:id pass) 0 0 pick-dim pick-dim)
    (bgfx set-view-transform (:id pass)
          (.get4x4 pick-view (float-array 16))
          (.get pick-proj (float-array 16)))
    
    (bgfx set-view-rect (:shading pass) 0 0 width height)
    (bgfx set-view-transform (:shading pass)
          (.get4x4 view (float-array 16))
          (.get proj (float-array 16)))
    (doseq [yy (range 12) xx (range 12)]
      (bgfx encoder-set-transform encoder
            (-> (Matrix4x3f.)
              (.translation
                (-> xx (* 3.) (- 15))
                (-> yy (* 3.) (- 15))
                5.)
              (.rotateXYZ
                (-> xx (* 0.21) #_ (+ time))
                (-> yy (* 0.37) #_(+ time))
                0.)
              (.get4x4 (float-array 16))))
      ;; encoder, stream, handle, startvertex, numvertices
      (bgfx encoder-set-vertex-buffer encoder 0 vertex-buffer 0 8)
      ;; encoder, handle, firstindex, numindices
      (bgfx encoder-set-index-buffer encoder index-buffer 0 36)
      ;; encoder, state, rgba
      (bgfx encoder-set-state encoder (BGFX state-default) 0)
      ;; submit primitive for rendering: encoder, view_id, program, depth, flags
      (bgfx encoder-submit encoder (:shading pass) shading-program 0 0)

      ;; encoder, handle, value, numelements
      (bgfx encoder-set-uniform encoder pick-uniform (id->uniform xx yy) 1)
      (bgfx encoder-submit encoder (:id pass) picking-program 0 0)
      )

    (bgfx encoder-blit encoder (:blit pass)
          pick-blit-texture 0 0 0 0 ; dest mip x y z
          pick-target 0 0 0 0 ; src mip x y z
          pick-dim pick-dim 0) ; w h d
    (bgfx read-texture pick-blit-texture pick-data 0) ; handle, data, mip
    
    (when @save?
      (let [im (BufferedImage. pick-dim pick-dim BufferedImage/TYPE_INT_ARGB)
            ib (.asIntBuffer pick-data)]
        (doseq [x (range pick-dim) y (range pick-dim)
                :let [i (+ (* x pick-dim) y)
                      v (.get ib i)]]
          (when (= (/ pick-dim 2) x y) (println x y (hex-str v) (abgr->id v))) 
          (.setRGB im x y (abgr->argb v)))
        (ImageIO/write im "png" (io/file "wtf.png"))
        (swap! save? not)))
    (bgfx encoder-end encoder)))
