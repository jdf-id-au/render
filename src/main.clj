(ns cljbg.main
  "Also see Poster/main.clj"
  (:import (org.lwjgl.glfw GLFW Callbacks GLFWErrorCallback)
           (org.lwjgl.bgfx BGFX)
           (org.lwjgl.system MemoryStack MemoryUtil)))

(defn -main
  [& args]
  (.set (GLFWErrorCallback/createPrint System/err))
  (GLFW/glfwInit)
  (GLFW/glfwWindowHint GLFW/GLFW_VISIBLE GLFW/GLFW_FALSE)
  (GLFW/glfwWindowHint GLFW/GLFW_RESIZABLE GLFW/GLFW_TRUE)
  (let [width 1920
        height 1080
        window (GLFW/glfwCreateWindow width height "bgfx?"
                 MemoryUtil/NULL MemoryUtil/NULL)]
    (GLFW/glfwMakeContextCurrent window)
    (GLFW/glfwSwapInterval 1)
    (GLFW/glfwShowWindow window)

    ;; not on the main thread; unclear why need to call explicitly TODO explore
    (doto (Thread. #(clojure.main/main)) (.start))

    (nrepl.server/start-server
      :port 12345
      :handler cider.nrepl/cider-nrepl-handler)
    (println "Cider-enabled nREPL server started at localhost:" (12345))

    (let [quit! (fn []
                  (Callbacks/glfwFreeCallbacks window)
                  (GLFW/glfwHideWindow window)
                  (GLFW/glfwDestroyWindow window)
                  (GLFW/glfwPollEvents)

                  (GLFW/glfwTerminate)
                  (.free (GLFW/glfwSetErrorCallback nil))
                  (shutdown-agents))]
      (loop []
        (when (not (GLFW/glfwWindowShouldClose window))
          (try
            (catch Exception e
              (println "Draw exception" e)))
          (GLFW/glfwSwapBuffers window)
          (GLFW/glfwWaitEvents)
          (recur))
        (quit!)))))
