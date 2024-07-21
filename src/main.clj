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
  (println "Starting nREPL")
  (try
    (nrepl.server/start-server :port port :handler cider.nrepl/cider-nrepl-handler)
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
    (println "Initialising bgfx" init)
    (assert (BGFX/bgfx_init init))
    (println "bgfx renderer:" (BGFX/bgfx_get_renderer_name (BGFX/bgfx_get_renderer_type)))
    (BGFX/bgfx_set_debug BGFX/BGFX_DEBUG_TEXT)
    (BGFX/bgfx_set_view_clear 0 (bit-or BGFX/BGFX_CLEAR_COLOR BGFX/BGFX_CLEAR_DEPTH)
      0x303030ff 1.0 0)
    (fn renderer [width height]
      (BGFX/bgfx_set_view_rect 0 0 0 width height)
      (BGFX/bgfx_touch 0)
      (BGFX/bgfx_dbg_text_clear 0 false)
      (println "trying to draw")
      (BGFX/bgfx_dbg_text_image
          (- (max (/ width 2 8) 20) 20)
          (- (max (/ height 2 16) 66) 6)
          40 12 (logo/logo) 160) ; TODO think about resource management, currently just memoized...
      (BGFX/bgfx_dbg_text_printf 0 1 0x1f "25-c99 -> java -> clojure"))))

(defn close-graphics-renderer [_]
  (println "Closing renderer")
  (BGFX/bgfx_shutdown))

(defn make-graphics-thread [window width height graphics-latch has-error?]
  (println "Making graphics thread")
  (Thread.
    (fn []
      (cc/with-resource [graphics-renderer (make-graphics-renderer window width height) close-graphics-renderer]
        (try
          (.countDown graphics-latch)
          (loop []
            (when (not (GLFW/glfwWindowShouldClose window))
              (graphics-renderer width height)
              (BGFX/bgfx_frame false)))
          (catch Throwable t
            (.printStackTrace t)
            (.set has-error? true)
            (.countDown graphics-latch)))))))

(defn -main [& args]
  (println "Starting")
  (let [width 1000 height 500]
    (cc/with-resource
      [repl (start-repl 12345) stop-repl 
       window (open-window width height) close-window
       has-error? (AtomicBoolean.) nil
       graphics-latch (CountDownLatch. 1) nil
       graphics-thread (make-graphics-thread window width height graphics-latch has-error?) nil]
      (println "Starting graphics thread")
      (.start graphics-thread)
      (loop [break? false]
        (when-not break?
          (recur (try (GLFW/glfwPollEvents)
                      (.await graphics-latch 16 TimeUnit/MILLISECONDS)
                      (catch InterruptedException e
                        (throw (IllegalStateException. e)))))))
      (GLFW/glfwShowWindow window)
      (loop []
        (when (and
                (not (GLFW/glfwWindowShouldClose window))
                (not (.get has-error?)))
          (GLFW/glfwWaitEvents)
          (recur)))
      (try (.join graphics-thread)
           (catch InterruptedException e
             (.printStackTrace e))))))

(comment
  ;; Windows, from emacs:
  ;;(setq cider-clojure-cli-global-options "-A:windows-x64")
  ;; Mac, from cli:
  ;; % clj -M:macos-x64 -m main
  (-main)
  )
