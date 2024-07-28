(ns example.main
  "Example project using jdf/render."
  (:require [example.renderer :as er]
            [render.core :as rc]
            ))

(defn -main [& args]
  (rc/main #'er/setup #'er/teardown #'er/renderer))

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
