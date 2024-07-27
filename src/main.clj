(ns main
  "Start GLFW (on first thread if macOS), and REPL.
  Open new window and start BGFX. Allow reload without killing REPL/JVM.
  Currently supports only one window." ; TODO both GLFW and BGFX support multiple windows!
  ;; distantly after https://github.com/LWJGL/lwjgl3/blob/master/modules/samples/src/test/java/org/lwjgl/demo/bgfx/HelloBGFXMT.java
  (:require [cider.nrepl]
            [nrepl.server]
            [util :refer [glfw GLFW bgfx BGFX]]
            [clj-commons.primitive-math :as m]
            [renderer]
            [comfort.core :as cc]
            [clojure.pprint :refer [pprint]])
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
      (println "Problem stopping nREPL" (.getMessage e))))
  (shutdown-agents))

(defn open-glfw-session []
  (.set (GLFWErrorCallback/createThrow))
  (assert (glfw init))
  (glfw default-window-hints)
  (glfw window-hint (GLFW client-api) (GLFW no-api))
  (glfw window-hint (GLFW visible) (GLFW false))
  (when (= (Platform/get) Platform/MACOSX)
    (glfw window-hint (GLFW cocoa-retina-framebuffer) (GLFW false))))

(defn close-glfw-session [_]
  (glfw terminate)
  (.free (Objects/requireNonNull (glfw set-error-callback nil))))

(defn open-window
  "Initialise GLFW and open window. Return window handle."
  [width height]
  (println "Opening window")
  (let [window (glfw create-window width height "cljbg" MemoryUtil/NULL MemoryUtil/NULL)]
    (assert window)
    (glfw set-key-callback window
      (reify GLFWKeyCallbackI
        (invoke [this window key scancode action mods] ; could be dynamic with indirection
          (condp = action
            (GLFW release) nil
            (condp = key
              (GLFW key-escape) (glfw set-window-should-close window true)
              nil)))))
    window))

(defn close-window [window]
  (println "Closing window" window)
  (Callbacks/glfwFreeCallbacks window)
  (glfw destroy-window window))

(defn make-graphics-thread [window width height]
  (println "Making graphics thread from" (util/current-thread))
  (let [status (atom nil)]
    {:thread
     (Thread.
       (fn []
         (println "Graphics thread running on" (util/current-thread))
         (try 
           (cc/with-resource [renderer (renderer/make window width height) renderer/close
                              setup (renderer/setup) renderer/teardown]
             (swap! status assoc
               :started (glfw get-timer-value)
               :fresh? true)
             (loop [frame-time 0] ; ──────────────────────────────── render loop
               (if (glfw window-should-close window) ; NB also checked in event loop
                 (swap! status assoc
                   :stopped (glfw get-timer-value)
                   :close? true)
                 (do
                   ;; TODO event handler which `bgfx_reset`s width and height
                   ;; and somehow gets it through to renderer
                   (let [pre (glfw get-timer-value)
                         freq (glfw get-timer-frequency)
                         period-ms (/ 1000. freq)
                         time (case freq 0 0 (-> pre (- (:started @status)) (/ freq) float))]
                     (try (@renderer setup status width height time (* frame-time period-ms))
                          (bgfx frame false)
                          (catch Throwable t
                            (pprint t)
                            (println "Renderer error; retrying in 5s")
                            (Thread/sleep 5000)))
                     (recur (- (glfw get-timer-value) pre))))))) ; full speed!
           (catch Throwable t
             (swap! status assoc :startup-error (Throwable->map t))))))
     :status status})) ; could add-watch, or just close the window...

(defn join-graphics-thread [{:keys [thread]}]
  (println "Joining graphics thread") ; ...expecting it to die
  (try (.join thread)
       (catch InterruptedException e
         (.printStackTrace e))))

(defonce retry-on-window-close? (atom true)) ; allows window to close without quitting repl and jvm

(defn -main [& args]
  (println "Startup")
  (let [width 1920 height 1200]
    (cc/with-resource
      [repl (start-repl 12345) stop-repl
       glfw-session (open-glfw-session) close-glfw-session]
      (loop [] ; ────────────────────────────────────── retryable window opening
        (when @retry-on-window-close?
          (cc/with-resource [window (open-window width height) close-window]
            (loop [{:keys [close?] :as status} nil] ; reloadable graphics thread
              (when-not close?
                (recur
                  (cc/with-resource [graphics
                                     (make-graphics-thread window width height)
                                     join-graphics-thread]
                    (let [{:keys [thread status]} graphics]
                      (println "Starting graphics thread")
                      (.start thread)
                      (loop [{:keys [started startup-error]} @status] ; await renderer startup
                        (glfw poll-events)
                        (when-not (or started startup-error)
                          (do (Thread/sleep 16)
                              (recur @status))))
                      (cond
                        (:started @status)
                        (do
                          (println "Showing window")
                          (glfw show-window window)
                          (println "Event loop")
                          (loop [{:keys [fresh?]} @status] ; ──────── event loop
                            (cond
                              ;; Exit event loop, close window, possibly retry
                              (glfw window-should-close window) ; NB also checked in render loop
                              (swap! status assoc :close? true)

                              ;; Continue event loop, consume events even if renderer-error
                              fresh?
                              (do (glfw wait-events) ; not poll; graphics thread separate
                                  (recur @status))

                              ;; Exit event loop, keep window, restart graphics thread
                              :else @status)))

                        (:startup-error @status)
                        (do (pprint (:startup-error @status))
                            (println "Graphics thread startup error; retrying in 5s")
                            (Thread/sleep 5000)
                            @status))))))))
          (recur))))))

(comment
  ;; Windows, from emacs: M-:
  ;;(setq cider-clojure-cli-global-options "-A:windows-x64")
  ;; Mac, from cli:
  ;; % clj -M:macos-x64 -m main
  ;; then cider-connect-clj to localhost:port
  (-main)
  )
