(ns example.main
  "Example project using jdf/render."
  (:require [example.cubes :as cubes]
            [example.cube-picking :as picking]
            [render.core :as rc]
            ))

(defn -main [& args]
  (rc/main
    {:renderer [#'picking/context #'picking/renderer]
     :window [1920 1200 "picking"]
     :callbacks #'picking/callbacks})
  #_(rc/main
    {:renderer [#'cubes/context #'cubes/renderer]
     :window [1920 1200 "cubes"]
     :callbacks #'cubes/callbacks}))

(comment
  ;; Windows, from emacs: M-:
  ;;(setq cider-clojure-cli-global-options "-A:windows-x64")
  ;; then cider-jack-in-clj, cider-load-buffer, and eval:
  (-main) ; although seems slow to eval within emacs
  ;; vs:
  ;; PS> clj -M:windows-x64 -m example.main
  
  ;; Mac, from terminal:
  ;; % clj -M:macos-x64 -m example.main
  ;; then cider-connect-clj to localhost:port
  ;; (-main will already be running)
  )
