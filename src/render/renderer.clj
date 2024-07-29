(ns render.renderer
  (:require [render.util :as ru :refer [with-resource glfw GLFW bgfx BGFX]]
            [clj-commons.primitive-math :as m]
            [clojure.java.io :as io])
  (:import (java.util.function Consumer)
           (java.nio.file Files)
           (org.lwjgl.bgfx BGFXInit
             BGFXVertexLayout
             BGFXReleaseFunctionCallback BGFXReleaseFunctionCallbackI)
           (org.lwjgl.system Platform MemoryStack MemoryUtil)))

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

(defn make-vertex-layout [normals? colour? nUVs] ; ═══════════════════════ setup
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
   (let [r (bgfx make-ref buffer)]
     (bgfx create-vertex-buffer r layout (BGFX buffer-none))))
  ([buffer layout vertices]
   (doseq [vertex vertices
           attr vertex]
     #_(println "buffering" (type attr) attr)
     (condp = (type attr)
       Double (.putFloat buffer (unchecked-float attr)) ; `1.` literals are Double
       Long (.putInt buffer (unchecked-int attr))
       Float (.putFloat buffer attr)
       Integer (.putInt buffer attr)))
   (assert (zero? (.remaining buffer)))
   (.flip buffer)
   (make-vertex-buffer buffer layout)))

(defn make-index-buffer [buffer indices]
  (doseq [index indices] (.putShort buffer index))
  (assert (zero? (.remaining buffer)))
  (.flip buffer)
  (bgfx create-index-buffer (bgfx make-ref buffer) (BGFX buffer-none)))

(defn load-resource
  "Caller's responsibility to free."
  [path]
  (let [r (-> path io/resource io/file)
        ;; memAlloc (C malloc, "off heap") vs BufferUtils/createByteBuffer ?
        res (MemoryUtil/memAlloc (Files/size (.toPath r)))]
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

(defn load-shader [x]
  (if (map? x)
    (into {} (for [[k v] x] [k (load-shader v)]))
    (let [bin
          (cond (string? x)
                (load-resource
                  (str "shaders/"
                    (condp = (bgfx get-renderer-type)
                      ;; 9 vulkan...
                      (BGFX renderer-type-direct3d11) "dx11/"
                      (BGFX renderer-type-direct3d12) "dx11/"
                      (BGFX renderer-type-opengl) "glsl/"
                      (BGFX renderer-type-metal) "metal/") x ".bin"))

                (bytes? x)
                (doto (MemoryUtil/memAlloc (count x)) (.put x) .flip))]
      ;; 0 is _userData void pointer aka long so not nil
      (bgfx create-shader (bgfx make-ref-release bin release-memory-cb 0)))))

(defn load-texture [s]
  (let [data (load-resource (str "textures/" s))]
    (bgfx create-texture
      (bgfx make-ref-release data release-memory-cb nil)
      (BGFX texture-none) 0 nil)))

(defn supported?
  "e.g. (supported? (BGFX caps-texture-blit))"
  [cap]
  (not (zero? (bit-and cap (.supported (bgfx get-caps))))))

(defn check-setup
  [context] ; TODO more functionality
  {:deps (ru/dag (for [[k [_ _ deps]] context, d deps] [k d]))})

(defn make-setup [context]
  (let [order (ru/deps-order (for [[k [_ _ deps]] context, d deps] [k d]))]
    [(fn setup []
       (loop [acc {}
              [k & r] (into (keys context) (reverse order))]
         (if k
           (if (acc k)
             (recur acc r)
             (let [[create! _ deps] (context k)]
               (recur (assoc acc k
                        (do #_(println "Creating" (name k)
                              (if (seq deps) (str "which depends on " deps) ""))
                            (if (seq deps)
                              (create! acc)
                              (create!)))) r)))
           acc)))
     (fn teardown [m]
       (reduce (fn [acc k]
                 (if-let [target (acc k)]
                   (let [[_ destroy! _] (context k)]
                     #_(println "Destroying" (name k) target)
                     (try
                       (destroy! target)
                       (catch Exception e ; invisible otherwise! because effectively within a (finally ...)?
                         (println "Failed to destroy" (name k) target e)))
                     (dissoc acc k))
                   acc))
         m (into (keys context) order)))]))

(defonce refresh! ; ═══════════════════════════════════════════════════ renderer
  (atom (fn [] (throw (ex-info "No renderer refresher yet" {})))))

(defn open-bgfx-session
  "Initialise BGFX and configure window."
  [window width height]
  (println "Making renderer")
  (with-resource [stack (MemoryStack/stackPush) nil
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
      ;; TODO getWaylandDisplay
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
      0x303030ff 1.0 0)))

(defn close-bgfx-session [_]
  (println "Closing renderer")
  (bgfx shutdown))
