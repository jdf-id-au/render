(ns main
  "Also see Poster/main.clj"
  ;; after https://github.com/LWJGL/lwjgl3/blob/master/modules/samples/src/test/java/org/lwjgl/demo/bgfx/HelloBGFXMT.java
  (:require [cider.nrepl]
            [nrepl.server]
            [logo]
            [comfort.core :as cc])
  (:import (java.util Objects)
           (java.util.function Consumer)
           (org.lwjgl.glfw GLFW Callbacks GLFWErrorCallback GLFWKeyCallbackI)
           (org.lwjgl.bgfx BGFX BGFXInit)
           (org.lwjgl.system Platform MemoryStack MemoryUtil)
           (java.util.concurrent CountDownLatch TimeUnit)
           (java.util.concurrent.atomic AtomicBoolean)))

(defn start-repl [port]
  (println "Starting cider-enabled nREPL on port" port)
  (try
    (nrepl.server/start-server :port port :handler cider.nrepl/cider-nrepl-handler)
    (println "Ready for cider-connect-clj")
    (catch Exception e
      (println "Problem starting nREPL" (.getMessage e)))))

(defn stop-repl [repl]
  (try
    (nrepl.server/stop-server repl)
    (catch Exception e
      (println "Problem stopping nREPL" (.getMessage e)))))

(defn open-window [width height]
  (println "Opening window")
  (.set (GLFWErrorCallback/createThrow))
  (assert (GLFW/glfwInit))
  (GLFW/glfwDefaultWindowHints)
  (GLFW/glfwWindowHint GLFW/GLFW_CLIENT_API, GLFW/GLFW_NO_API)
  (GLFW/glfwWindowHint GLFW/GLFW_VISIBLE GLFW/GLFW_FALSE)
  (when (= (Platform/get) Platform/MACOSX)
    (GLFW/glfwWindowHint GLFW/GLFW_COCOA_RETINA_FRAMEBUFFER GLFW/GLFW_FALSE))
  
  (let [window (GLFW/glfwCreateWindow width height "bgfx?" MemoryUtil/NULL MemoryUtil/NULL)]
    (assert window)
    (GLFW/glfwSetKeyCallback window
      (reify GLFWKeyCallbackI
        (invoke [this window key scancode action mods]
          (case action
            GLFW/GLFW_RELEASE nil
            (case key
              GLFW/GLFW_KEY_ESCAPE (GLFW/glfwSetWindowShouldClose window GLFW/GLFW_TRUE)
              nil)))))
    window))

(defn close-window [window]
  (println "Closing window" window)
  (Callbacks/glfwFreeCallbacks window)
  (GLFW/glfwDestroyWindow window)
  (GLFW/glfwTerminate)
  (.free (Objects/requireNonNull (GLFW/glfwSetErrorCallback nil)))
  (shutdown-agents))

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
(reset! renderer* ; C-M-x this
  (fn renderer [window width height]
    (BGFX/bgfx_set_view_rect 0 0 0 width height)
    (BGFX/bgfx_touch 0)
    (BGFX/bgfx_dbg_text_clear 0 false)
    (let [x (int (- (max (/ width 2 8) 20) 20))
          y (int (- (max (/ height 2 16) 66) 6))]
      (BGFX/bgfx_dbg_text_image 10 10
        40 12 (logo/logo) 160) ; TODO think about resource management, currently just memoized...
      (BGFX/bgfx_dbg_text_printf 10 10  0x1f "25-c99 -> java -> clojure"))))

(defn make-graphics-renderer [window width height]
  (println "Making renderer")
  (cc/with-resource [stack (MemoryStack/stackPush) nil
                     init (BGFXInit/malloc stack) nil]
    (BGFX/bgfx_init_ctor init)
    (.resolution init (reify Consumer
                        (accept [this o]
                          (.width o width)
                          (.height o height)
                          (.reset o BGFX/BGFX_RESET_VSYNC))))
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
    (assert (BGFX/bgfx_init init))
    (println "bgfx renderer:" (BGFX/bgfx_get_renderer_name (BGFX/bgfx_get_renderer_type)))
    (BGFX/bgfx_set_debug BGFX/BGFX_DEBUG_TEXT)
    (BGFX/bgfx_set_view_clear 0 (bit-or BGFX/BGFX_CLEAR_COLOR BGFX/BGFX_CLEAR_DEPTH)
      0x303030ff 1.0 0)
    renderer*))

(defn close-graphics-renderer [_]
  (println "Closing renderer")
  (BGFX/bgfx_shutdown))

(defn make-graphics-thread [window width height graphics-latch has-error?]
  (println "Making graphics thread")
  (Thread.
    (fn []
      (cc/with-resource [graphics-renderer
                         (make-graphics-renderer window width height)
                         close-graphics-renderer]
        (try
          (.countDown graphics-latch)
          (loop [] ; ────────────────────────────────────────────────── graphics
            (when (not (GLFW/glfwWindowShouldClose window))
              (try (@graphics-renderer window width height)
                   (catch Throwable t
                     (.printStackTrace t)
                     (Thread/sleep 5)))
              (BGFX/bgfx_frame false)
              (recur))) ; full speed!
          (catch Throwable t
            (.printStackTrace t)
            (.set has-error? true)
            (.countDown graphics-latch)))))))

(defn -main [& args]
  (println "Startup")
  (let [width 1000 height 500]
    (cc/with-resource
      [repl (start-repl 12345) stop-repl 
       window (open-window width height) close-window
       has-error? (AtomicBoolean.) nil
       graphics-latch (CountDownLatch. 1) nil
       graphics-thread (make-graphics-thread window width height graphics-latch has-error?) nil]
      (println "Starting graphics thread")
      (.start graphics-thread)
      (loop [break? false] ; await thread
        (when-not break?
          (recur (try (GLFW/glfwPollEvents)
                      (.await graphics-latch 16 TimeUnit/MILLISECONDS)
                      (catch InterruptedException e
                        (throw (IllegalStateException. e)))))))
      (println "Showing window")
      (GLFW/glfwShowWindow window)
      (println "Event loop")
      (loop [] ; ───────────────────────────────────────────────────────── event
        (when (and
                (not (GLFW/glfwWindowShouldClose window))
                (not (.get has-error?)))
          (GLFW/glfwWaitEvents) ; wait vs poll because graphics separate
          (recur)))
      (println "Shutdown")
      (try (.join graphics-thread)
           (catch InterruptedException e
             (.printStackTrace e))))))

(comment
  ;; Windows, from emacs:
  ;;(setq cider-clojure-cli-global-options "-A:windows-x64")
  ;; Mac, from cli:
  ;; % clj -M:macos-x64 -m main
  ;; then cider-connect-clj to localhost:port
  (-main)
  )
