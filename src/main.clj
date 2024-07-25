(ns main
  "Also see Poster/main.clj"
  ;; after https://github.com/LWJGL/lwjgl3/blob/master/modules/samples/src/test/java/org/lwjgl/demo/bgfx/HelloBGFXMT.java
  (:require [cider.nrepl]
            [nrepl.server]
            [util :refer [glfw GLFW bgfx BGFX]]
            [renderer]
            [comfort.core :as cc])
  (:import (java.util Objects)
           (java.util.function Consumer)
           (org.lwjgl.glfw Callbacks GLFWErrorCallback GLFWKeyCallbackI)
           (org.lwjgl.bgfx BGFXInit)
           (org.lwjgl.system Platform MemoryStack MemoryUtil)
           (java.util.concurrent CountDownLatch TimeUnit)
           (java.util.concurrent.atomic AtomicBoolean)))

(defn start-repl [port]
  (println "Starting cider-enabled nREPL on port" port "from thread" (util/current-thread))
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
  (assert (glfw init))
  (glfw default-window-hints)
  (glfw window-hint (GLFW client-api) (GLFW no-api))
  (glfw window-hint (GLFW visible) (GLFW false))
  (when (= (Platform/get) Platform/MACOSX)
    (glfw window-hint (GLFW cocoa-retina-framebuffer) (GLFW false)))
  
  (let [window (glfw create-window width height "cljbg" MemoryUtil/NULL MemoryUtil/NULL)]
    (assert window)
    (glfw set-key-callback window
      (reify GLFWKeyCallbackI
        (invoke [this window key scancode action mods]
          (condp = action
            (GLFW release)
            nil
            (condp =  key
              (GLFW key-escape) (glfw set-window-should-close window (GLFW true))
              nil)))))
    window))

(defn close-window [window]
  (println "Closing window" window)
  (Callbacks/glfwFreeCallbacks window)
  (glfw destroy-window window)
  (glfw terminate)
  (.free (Objects/requireNonNull (glfw set-error-callback nil)))
  (shutdown-agents))

(defn make-graphics-thread [window width height
                            graphics-latch has-error?]
  (println "Making graphics thread from" (util/current-thread))
  (Thread.
    (fn []
      (println "Graphics thread running on" (util/current-thread))
      (cc/with-resource [graphics-renderer (renderer/make window width height) renderer/close
                         renderer-setup (renderer/setup) (renderer/teardown)
                         ]
        (try
          (.countDown graphics-latch)
          (loop [] ; ────────────────────────────────────────────────── graphics
            (when (not (glfw window-should-close window))
              ;; TODO event handler which `bgfx_reset`s width and height
              ;; and somehow gets it through to renderer
              (try (@graphics-renderer width height renderer-setup)
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
  (let [width 1920 height 1200]
    (cc/with-resource
      [repl (start-repl 12345) stop-repl ; i.e. on main thread?
       window (open-window width height) close-window
       has-error? (AtomicBoolean.) nil
       graphics-latch (CountDownLatch. 1) nil
       graphics-thread (make-graphics-thread window width height
                         graphics-latch has-error?) nil]
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
  ;; Windows, from emacs: M-:
  ;;(setq cider-clojure-cli-global-options "-A:windows-x64")
  ;; Mac, from cli:
  ;; % clj -M:macos-x64 -m main
  ;; then cider-connect-clj to localhost:port
  (-main)
  )
