(ns render.callbacks
  ;; https://www.glfw.org/docs/3.3/input_guide.html
  (:require [render.util :as util :refer [glfw GLFW bgfx BGFX]]
            [comfort.core :as cc]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str])
  (:import (org.lwjgl.glfw Callbacks GLFWErrorCallback
             GLFWKeyCallbackI
             GLFWMouseButtonCallbackI
             GLFWCursorPosCallbackI
             GLFWScrollCallbackI)
           (org.lwjgl.system Platform MemoryStack MemoryUtil)))

;; Considered and rejected multimethods for callbacks.
(def defaults
  {:key/escape #(glfw set-window-should-close % true)
   #_#_:mouse/button-1 (fn [window action] (glfw set-window-should-close window true))})

(defn kw->enum
  "Convert :type/name to GLFW_TYPE_NAME."
  [nskw]
  (->> (str "org.lwjgl.glfw.GLFW/GLFW_"
         (-> nskw namespace cc/kebab->screaming-snake)
         "_"
         (-> nskw name cc/kebab->screaming-snake))
    symbol eval))

(defn group-bindings
  "Use to reduce bindings into hierarchy of {:key-type {mods {key-enum action-fn}}}.
  Disregards OS key mapping!"
  [acc [k action-fn]]
  (case k
    (:scroll :cursor) (assoc acc k action-fn)
    (cond
      (keyword? k)
      (assoc-in acc [(keyword (namespace k)) 0 (kw->enum k)] action-fn)

      (coll? k)
      (let [mods (->> k (filter #(#{"mod"} (namespace %))) (map kw->enum) (reduce bit-or 0))
            [b & r] (->> k (filter #(#{"key" "mouse"} (namespace %))))]
        (assert (nil? r) (str "Too many keys or buttons in binding: " k))
        (assoc-in acc [(keyword (namespace b)) mods (kw->enum b)] action-fn)))))

(defonce refresh!
  (atom (fn [] (throw (ex-info "No callbacks refresher yet" {})))))

(defn setup [window callbacks-var]
  (add-watch callbacks-var :refresh
    (fn [k r o n] (println "Refreshing callbacks")
      (@refresh!)))
  (reset! refresh!
    (fn []
      (Callbacks/glfwFreeCallbacks window) ; won't double-free
      (let [defs (reduce group-bindings {} (merge defaults (var-get callbacks-var)))]
        (glfw set-key-callback window
              (reify GLFWKeyCallbackI
                (invoke [this window key scancode action mods]
                  #_(println "responding to key " key action mods)
                  (condp = action
                    (GLFW release) nil
                    ;; press or repeat:
                    (when-let [d (get-in defs [:key mods key])]
                      (d window))))))
        (glfw set-mouse-button-callback window
              (reify GLFWMouseButtonCallbackI
                (invoke [this window button action mods]
                  #_(println "responding to button " button action button)
                  (when-let [d (get-in defs [:mouse mods button])]
                    (d window action)))))
        (when-let [scroll (:scroll defs)]
          (glfw set-scroll-callback window
                (reify GLFWScrollCallbackI
                  (invoke [this window xpos ypos]
                    (scroll window xpos ypos)))))
        (when-let [cursor (:cursor defs)]
          (glfw set-cursor-pos-callback window
                (reify GLFWCursorPosCallbackI
                  (invoke [this window xoffset yoffset]
                    (cursor window xoffset yoffset))))))))
  (@refresh!)
  window)

(defn teardown [window]
  (Callbacks/glfwFreeCallbacks window))
