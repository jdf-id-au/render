(ns main
  "Also see Poster/main.clj"
  ;; after https://github.com/LWJGL/lwjgl3/blob/master/modules/samples/src/test/java/org/lwjgl/demo/bgfx/HelloBGFXMT.java
  (:require [cider.nrepl]
            [nrepl.server]
            [util :refer [glfw bgfx]]
            [renderer]
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
  (glfw default-window-hints)
  (glfw window-hint GLFW/GLFW_CLIENT_API, GLFW/GLFW_NO_API)
  (glfw window-hint GLFW/GLFW_VISIBLE GLFW/GLFW_FALSE)
  (when (= (Platform/get) Platform/MACOSX)
    (glfw window-hint GLFW/GLFW_COCOA_RETINA_FRAMEBUFFER GLFW/GLFW_FALSE))
  
  (let [window (glfw create-window width height "bgfx?" MemoryUtil/NULL MemoryUtil/NULL)]
    (assert window)
    (glfw set-key-callback window
      (reify GLFWKeyCallbackI
        (invoke [this window key scancode action mods]
          (case action
            GLFW/GLFW_RELEASE nil
            (case key
              GLFW/GLFW_KEY_ESCAPE (glfw set-window-should-close window GLFW/GLFW_TRUE)
              nil)))))
    window))

(defn close-window [window]
  (println "Closing window" window)
  (Callbacks/glfwFreeCallbacks window)
  (glfw destroy-window window)
  (glfw terminate)
  (.free (Objects/requireNonNull (glfw set-error-callback nil)))
  (shutdown-agents))

(defn make-graphics-renderer [window width height]
  (println "Making renderer")
  (cc/with-resource [stack (MemoryStack/stackPush) nil
                     init (BGFXInit/malloc stack) nil]
    (bgfx init-ctor init)
    (.resolution init (reify Consumer ; does this mean it should detect change?
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
    (assert (bgfx init init))
    (println "bgfx renderer:" (bgfx get-renderer-name (bgfx get-renderer-type)))
    (bgfx set-debug BGFX/BGFX_DEBUG_TEXT)
    (bgfx set-view-clear 0 (bit-or BGFX/BGFX_CLEAR_COLOR BGFX/BGFX_CLEAR_DEPTH)
      0x303030ff 1.0 0)
    renderer/renderer*))

(defn close-graphics-renderer [_]
  (println "Closing renderer")
  (bgfx shutdown))

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
            (when (not (glfw window-should-close window))
              ;; TODO event handler which `bgfx_reset`s width and height
              ;; and somehow gets it through to renderer
              (try (@graphics-renderer width height)
                   (catch Throwable t
                     (.printStackTrace t)
                     (Thread/sleep 5000)))
              (bgfx frame false)
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
          (recur (try (glfw poll-events)
                      (.await graphics-latch 16 TimeUnit/MILLISECONDS)
                      (catch InterruptedException e
                        (throw (IllegalStateException. e)))))))
      (println "Showing window")
      (glfw show-window window)
      (println "Event loop")
      (loop [] ; ───────────────────────────────────────────────────────── event
        (when (and
                (not (glfw window-should-close window))
                (not (.get has-error?)))
          (glfw wait-events) ; wait vs poll because graphics separate
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
