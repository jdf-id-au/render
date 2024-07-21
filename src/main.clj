(ns cljbg.main
  "Also see Poster/main.clj"
  ;; after https://github.com/LWJGL/lwjgl3/blob/master/modules/samples/src/test/java/org/lwjgl/demo/bgfx/HelloBGFXMT.java
  (:require [cljbg.logo :as logo])
  (:import (org.lwjgl.glfw GLFW Callbacks GLFWErrorCallback)
           (org.lwjgl.bgfx BGFX BGFXInit)
           (org.lwjgl.system Platform MemoryStack MemoryUtil)
           (java.util.concurrent CountDownLatch TimeUnit)
           (java.util.concurrent.atomic AtomicBoolean)))

(defn -main
  [& args]
  (.set (GLFWErrorCallback/createPrint System/err))
  (assert (GLFW/glfwInit))
  (GLFW/glfwDefaultWindowHints)
  (GLFW/glfwWindowHint GLFW/GLFW_CLIENT_API, GLFW/GLFW_NO_API)
  (GLFW/glfwWindowHint GLFW/GLFW_VISIBLE GLFW/GLFW_FALSE)
  (when (= (Platfrom/get) Platform/MACOSX)
    (GLFW/glfwWindowHint(GLFW/GLFW_COCOA_RETINA_FRAMEBUFFER GLFW/GLFW_FALSE)))
  
  (let [width 1920
        height 1080
        window (GLFW/glfwCreateWindow width height "bgfx?"
                 MemoryUtil/NULL MemoryUtil/NULL)]
    (assert window)
    (GLFW/glfwSetKeyCallback window
      (fn [window key scancode action mods]
        (case action
          GLFW/GLFW_RELEASE nil
          (case key
            GLFW/GLFW_KEY_ESCAPE (GLFW/glfwSetWindowShouldClose window GLFW/GLFW_TRUE)
            nil))))
    #_(GLFW/glfwMakeContextCurrent window)
    #_(GLFW/glfwSwapInterval 1)
    #_(GLFW/glfwShowWindow window)

    ;; not on the main thread; unclear why need to call explicitly TODO explore
    (doto (Thread. #(clojure.main/main)) (.start))

    (nrepl.server/start-server
      :port 12345
      :handler cider.nrepl/cider-nrepl-handler)
    (println "Cider-enabled nREPL server started at localhost:" (12345))

    (let [close (fn []
                  (Callbacks/glfwFreeCallbacks window)
                  (GLFW/glfwDestroyWindow window)
                  
                  (GLFW/glfwTerminate)
                  (.free (Objects/requireNonNull (GLFW/glfwSetErrorCallback nil))))
          quit! (fn []
                  (Callbacks/glfwFreeCallbacks window)
                  #_(GLFW/glfwHideWindow window)
                  
                  (GLFW/glfwDestroyWindow window)
                  #_(GLFW/glfwPollEvents)

                  (GLFW/glfwTerminate)
                  (.free (GLFW/glfwSetErrorCallback nil))
                  
                  (shutdown-agents))
          has-error (AtomicBoolean.)
          logo* (atom nil)
          graphics-latch (CountDownLatch. 1)
          graphics-thread
          (Thread.
            (fn []
              ;; GraphicsRenderer constructor ──────────────────────────────────
              (let [stack (MemoryStack/stackPush) ; TODO java try(initialiser) syntax equiv
                    init (BGFXInit/malloc stack)]
                (BGFX/bgfx_init_ctor init)
                (.resolution init #(doto %
                                     (.width width)
                                     (.height height)
                                     (.reset BGFX/BGFX_RESET_VSYNC)))
                (condp = (Platform/get)
                  Platform/LINUX
                  (doto (.platformData init)
                    (.ndt org.lwjgl.glfw.GLFWNativeX11/glfwGetX11Display)
                    (.nwh org.lwjgl.glfw.GLFWNativeX11/glfwGetX11Window window))
                  Platform/MACOSX
                  (doto (.platformData init)
                    (.nwh org.lwjgl.glfw.GLFWNativeCocoa/glfwGetCocoaWindow window))
                  Platform/WINDOWS
                  (doto (.platformData init)
                    (.nwh org.lwjgl.glfw.GLFWNativeWin32/glfwGetWin32Window window)))
                (assert (BGFX/init init))
                (println "bgfx renderer:" (BGFX/bgfx_get_renderer_name (BGFX/bgfx_get_renderer_type)))
                (BGFX/bgfx_set_debug BGFX/BGFX_DEBUG_TEXT)
                (BGFX/bgfx_set_view_clear 0 (bitwise-or BGFX/BGFX_CLEAR_COLOR BGFX/BGFX_CLEAR_DEPTH)
                  0x303030ff 1.0 0)
                (reset! logo* (logo/logo))
                (.countDown graphics-latch)
                (loop []
                  (if (GLFW/glfwWindowShouldClose window)
                    nil
                    (try
                      ;; render ────────────────────────────────────────────────
                      (BGFX/bgfx_set_view_rect 0 0 width height)
                      (BGFX/bgfx_touch 0)

                      (BGFX/bgfx_dbg_text_clear 0 false)
                      (BGFX/bgfx_dbg_text_image
                        (- (max (/ width 2 8) 20) 20)
                        (- (max (/ height 2 16) 66) 6)
                        40 12 @logo* 160)
                      (BGFX/bgfx_dbg_text_printf 0 1 0x1f "25-c99 -> java -> clojure")
                      
                      (BGFX/bgfx_frame false)
                      (catch Throwable t
                        (.printStackTrace t)
                        (.set has-error true)
                        (.countDown graphics-latch)))
                    (when-not (.get has-error) (recur)))))))]
      (.start graphics-thread)
      (loop []
        (GLFW/glfwPollEvents)
        (if (.await graphics-latch 16 TimeUnit/MILLISECONDS)
          nil
          (recur)) ; catch InterruptedException...
        #_(when (not (GLFW/glfwWindowShouldClose window))
          (try
            (catch Exception e
              (println "Draw exception" e)))
          (GLFW/glfwSwapBuffers window)
          (GLFW/glfwWaitEvents)
          (recur))
        #_(quit!))
      (GLFW/glfwShowWindow window)
      (loop []
        (when (and
              (not (GLFW/glfwWindowShouldClose window))
              (not (.get has-error)))
          (GLFW/glfwWaitEvents)
          (recur)))
      (.join graphics-thread))))

(comment
  (-main)
  )
