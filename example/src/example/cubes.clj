(ns example.cubes
  "Example project using jdf/render."
  (:require [render.renderer :as rr]
            [render.core :as rc]
            [render.util :as ru :refer [glfw GLFW bgfx BGFX]]
            [render.shaders :as rs])
  (:import (org.lwjgl.system MemoryUtil)
           (org.joml Matrix4f Matrix4x3f Vector3f)))

(def callbacks
  {:mouse/button-1 (fn [window action] (println "button-1" window action))
   [:mouse/button-1 :mod/shift] (fn [window action] (println "shift-button-1"))
   #_#_:scroll (fn [window x y] (println "scroll " x " " y))})

(def shaders
  ;; Copyright 2011-2024 Branimir Karadzic. Modified.
  ;; License: https://github.com/bkaradzic/bgfx/blob/master/LICENSE
  ;; reminder: uniform per-primitive, attribute per-vertex, varying per-fragment (pixel)
  {:cubes
   {:varying "
vec4 v_color0    : COLOR0    = vec4(1.0, 0.0, 0.0, 1.0);
vec3 a_position  : POSITION;
vec4 a_color0    : COLOR0;"
    :vertex "
$input a_position, a_color0
$output v_color0
void main()
{
	gl_Position = mul(u_modelViewProj, vec4(a_position, 1.0) );
	v_color0 = a_color0;
}"
    :fragment "
$input v_color0
void main()
{
	gl_FragColor = v_color0;
}"}})

(add-watch #'shaders :refresh (fn [_ _ _ _] (@rc/refresh!)))

(def cube-vertices
  [[-1.  1.  1. 0xff000000] ;; Double Double Double Long (abgr!!)
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

(def context
  "Map of resource -> [create-fn destroy-fn] or [create-fn destroy-fn deps]"
  {:layout [#(rr/make-vertex-layout (BGFX attrib-color0)) #(.free %)]
   ;; ideally? would calc size per vertex from layout...
   :vertices [#(MemoryUtil/memAlloc (* (count cube-vertices) (+ (* 3 4) 4))) #(MemoryUtil/memFree %)]
   :vertex-buffer
   [#(rr/make-vertex-buffer (:vertices %) (:layout %) cube-vertices)
    #(bgfx destroy-vertex-buffer %)
    #{:vertices :layout}]
   :indices [#(MemoryUtil/memAlloc (* (count cube-indices) 2)) #(MemoryUtil/memFree %)]
   :index-buffer
   [#(rr/make-index-buffer (:indices %) cube-indices)
    #(bgfx destroy-index-buffer %)
    #{:indices}]
   :program
   [#(let [{:keys [vertex fragment]} (->> shaders :cubes rs/compile rr/load-shader)]
       (bgfx create-program vertex fragment true)) ; third arg is destroyShaders
    #(bgfx destroy-program %)]})

(comment ; blank renderer
  (defn renderer [_ _ width height _ _ ]
    (bgfx set-view-rect 0 0 0 width height)
    (bgfx touch 0))
  )

(defn renderer
  [{:keys [vertex-buffer index-buffer program] :as context}
   status width height time frame-time]
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

        ;; encoder is for multi-threaded draw call submission
        encoder (bgfx encoder-begin false)]
    (bgfx dbg-text-printf 0 0 0x1f (str frame-time))
    (bgfx set-view-rect 0 0 0 width height)
    (bgfx set-view-transform 0
          (.get4x4 view (float-array 16))
          (.get proj (float-array 16)))
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
              (.get4x4 (float-array 16))))
      ;; encoder, stream, handle, startvertex, numvertices
      (bgfx encoder-set-vertex-buffer encoder 0 vertex-buffer 0 (count cube-vertices)) ; use-case for startvertex, numvertices?
      ;; encoder, handle, firstindex, numindices
      (bgfx encoder-set-index-buffer encoder index-buffer 0 (count cube-indices))
      ;; encoder, state, rgba
      (bgfx encoder-set-state encoder (BGFX state-default) 0)
      ;; encoder, view id, program, depth (for sorting), flags (discard or preserve states)
      (bgfx encoder-submit encoder 0 program 0 0))
    (bgfx encoder-end encoder)))

(defn -main [& args]
  (rc/main {:renderer [#'context #'renderer]
            :window [800 600 "cubes"]
            :callbacks #'callbacks}))

(comment
  ;; Windows, from emacs: M-:
  ;;(setq cider-clojure-cli-global-options "-A:windows-x64")
  ;; then cider-jack-in-clj, cider-load-buffer, and eval:
  (-main) ; although seems slow to eval within emacs
  ;; vs:
  ;; PS> cd example
  ;; PS> clj -M:windows-x64 -m example.cubes
  
  ;; Mac, from terminal:
  ;; % cd example
  ;; % clj -M:macos-x64 -m example.cubes
  ;; then cider-connect-clj to localhost:port
  ;; (-main will already be running)
  )
