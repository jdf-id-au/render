(ns render.callbacks
  ;; https://www.glfw.org/docs/3.3/input_guide.html
  (:require [render.util :as util :refer [with-resource glfw GLFW bgfx BGFX]]
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
         (-> nskw namespace util/kebab->screaming-snake)
         "_"
         (-> nskw name util/kebab->screaming-snake))
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

(defn setup [window callbacks-var]
  (let [defs (reduce group-bindings {}
               (merge defaults (var-get callbacks-var)))]
    #_(pprint defs)
    ;; TODO GLFWCharCallbackI ...as well?
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
            (cursor window xoffset yoffset))))))
  window)

(defn teardown [window]
  (Callbacks/glfwFreeCallbacks window))
