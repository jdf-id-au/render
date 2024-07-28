(ns example.main
  "Example project using jdf/render."
  (:require [example.cubes :as cubes]
            [render.core :as rc]
            ))

(defn -main [& args]
  (rc/main
    {:renderer [#'cubes/setup #'cubes/teardown #'cubes/renderer]
     :window [1920 1200 "cubes"]
     :callbacks #'cubes/callbacks}))

(comment
  ;; Windows, from emacs: M-:
  ;;(setq cider-clojure-cli-global-options "-A:windows-x64")
  ;; then cider-jack-in-clj and eval:
  (-main)
  
  ;; Mac, from terminal:
  ;; % clj -M:macos-x64 -m example.main
  ;; then cider-connect-clj to localhost:port
  ;; (-main will already be running)
  )
