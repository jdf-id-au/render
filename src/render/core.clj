(ns render.core
  "Start GLFW (on first thread if macOS), and REPL.
  Open new window and start BGFX. Allow reload without killing REPL/JVM.
  Currently supports only one window." ; TODO both GLFW and BGFX support multiple windows!
  ;; distantly after https://github.com/LWJGL/lwjgl3/blob/master/modules/samples/src/test/java/org/lwjgl/demo/bgfx/HelloBGFXMT.java
  (:require [cider.nrepl]
            [nrepl.server]
            [render.renderer :as rr]
            [render.callbacks :as rc]
            [render.util :as ru :refer [with-resource glfw GLFW bgfx BGFX]]
            [clojure.pprint :refer [pprint]])
  (:import (java.util Objects)
           (org.lwjgl.glfw Callbacks GLFWErrorCallback GLFWKeyCallbackI)
           (org.lwjgl.system Platform MemoryUtil)))

(defn start-repl [port] ; ═════════════════════════════════════════════════ REPL
  (println "Starting cider-enabled nREPL on port" port "from thread" (ru/current-thread))
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

(defn open-glfw-session [] ; ══════════════════════════════════════════════ GLFW
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
  [title width height]
  (println "Opening window")
  (let [window
        (glfw create-window width height title MemoryUtil/NULL MemoryUtil/NULL)]
    (assert window)
    (glfw set-window-pos window 0 0)
    window))

(defn close-window [window]
  (println "Closing window" window)
  (glfw destroy-window window))

(defonce retry-on-window-close? (atom true)) ; without quitting REPL and JVM

(defonce refresh-thread! ; ═════════════════════════════════════ graphics thread
  ;; TODO could eventually track multiple threads/windows...
  (atom (fn [] (throw (ex-info "No thread refresher yet" {})))))

(defn make-graphics-thread [window width height
                            context-var renderer-var]
  (println "🎨 Making graphics thread from" (ru/current-thread))
  (add-watch context-var :refresh
    (fn [k r o n] (println "Refreshing graphics thread for renderer context")
      (@refresh-thread!)))
  (add-watch renderer-var :refresh
    (fn [k r o n] (println "Refreshing renderer")
      (@rr/refresh!)))
  (let [status (atom {:window window})]
    {:thread
     (Thread.
       (fn []
         (println "Graphics thread running on" (ru/current-thread))
         (try
           (let [[setup-fn teardown-fn] (rr/make-setup (var-get context-var))]
             (with-resource [session
                             (rr/open-bgfx-session window width height)
                             rr/close-bgfx-session
                             
                             context (setup-fn) teardown-fn]
               (swap! status assoc
                 :renderer-var renderer-var
                 :started (glfw get-timer-value))
               (reset! refresh-thread!
                 (fn [] (swap! status dissoc :renderer-var) (glfw post-empty-event)))
               (reset! rr/refresh!
                 (fn [] (swap! status assoc :renderer-var renderer-var)))
               (loop [frame-time 0] ; ──────────────────────────────── render loop
                 (cond
                   (glfw window-should-close window) ; NB also checked in event loop
                   (swap! status assoc
                     :stopped (glfw get-timer-value)
                     :close? true)

                   (:renderer-var @status)
                   (do
                     ;; TODO event handler which `bgfx_reset`s width and height
                     ;; and somehow gets it through to renderer
                     (let [pre (glfw get-timer-value)
                           freq (glfw get-timer-frequency)
                           period-ms (/ 1000. freq)
                           time (case freq 0 0 (-> pre (- (:started @status)) (/ freq) float))
                           renderer (-> status deref :renderer-var var-get)]
                       (try (renderer context @status
                              width height time (* frame-time period-ms))
                            (bgfx frame false)
                            (catch Throwable t
                              (pprint t)
                              (println "Renderer error; retrying in 5s")
                              (Thread/sleep 5000)))
                       (recur (- (glfw get-timer-value) pre)))) ; full speed!

                   :else nil))))
           (catch Throwable t
             (swap! status assoc :startup-error (Throwable->map t))))
         #_(println "Graphics thread stopping properly")))
     :status status})) ; could add-watch, or just close the window...

(defn join-graphics-thread [{:keys [thread]}]
  (println "Joining graphics thread") ; ...expecting it to die
  (try (.join thread)
       (catch InterruptedException e
         (.printStackTrace e))))

(defn main [{[context-var renderer-var] :renderer ; ═══════════════════════ main
             [width height title] :window
             callbacks-var :callbacks}
            & args]
  (println "Startup")
  (with-resource
    [repl (start-repl 12345) stop-repl
     glfw-session (open-glfw-session) close-glfw-session]
    (loop [] ; ────────────────────────────────────────────────────────── window
      (with-resource [window (open-window title width height) close-window]
        (loop [{:keys [close?] :as status} nil] ; ─────────────────────── thread
          (when-not close?
            (recur
              (with-resource [callbacks
                              (rc/setup window callbacks-var)
                              rc/teardown

                              graphics
                              (make-graphics-thread window width height
                                context-var renderer-var)
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
                      (loop [{:keys [renderer-var]} @status] ; ────── event loop
                        (cond
                          ;; Exit event loop, close window, possibly retry
                          (glfw window-should-close window) ; NB also checked in render loop
                          (swap! status assoc :close? true)

                          ;; Continue event loop, consume events even if renderer-error
                          renderer-var
                          (do (glfw wait-events) ; not poll; graphics thread separate
                              (recur @status))

                          ;; Exit event loop, keep window, restart graphics thread
                          :else @status)))

                    (:startup-error @status)
                    (do (pprint (:startup-error @status))
                        (println "Graphics thread startup error; retrying in 5s")
                        (Thread/sleep 5000)
                        @status))))))))
      (when @retry-on-window-close? (recur)))))
