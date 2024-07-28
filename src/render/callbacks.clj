(ns render.callbacks
  (:require [render.util :as util :refer [with-resource glfw GLFW bgfx BGFX]]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str])
  (:import (org.lwjgl.glfw Callbacks GLFWErrorCallback
             GLFWKeyCallbackI
             GLFWMouseButtonCallbackI)
           (org.lwjgl.system Platform MemoryStack MemoryUtil)))

(def defaults
  {:key/escape #(glfw set-window-should-close % true)
   #_#_:mouse/button-1 #(glfw set-window-should-close % true)})

(defn kw->enum
  "Convert :type/name to GLFW_TYPE_NAME."
  [nskw]
  (->> (str "org.lwjgl.glfw.GLFW/GLFW_"
         (-> nskw namespace util/kebab->screaming-snake)
         "_"
         (-> nskw name util/kebab->screaming-snake))
    symbol eval))

(defn group-bindings
  "Reduce bindings into hierarchy of {:key-type {mods {key action-fn}}}.
  Disregards OS key mapping!"
  [acc [nskw action-fn]] ; TODO method for expressing mods
  (assoc-in acc [(keyword (namespace nskw)) nil (kw->enum nskw)] action-fn))

(defn setup [window callbacks-var]
  (let [defs (reduce group-bindings {}
               (merge defaults (var-get callbacks-var)))]
    #_(pprint defs)
    ;; TODO GLFWCharCallbackI ...as well?
    (glfw set-key-callback window
      (reify GLFWKeyCallbackI
        (invoke [this window key scancode action mods]
          #_(println "responding to key " action key)
          (condp = action
            (GLFW release) nil
            ;; press or repeat:
            (when-let [d (get-in defs [:key nil key])]
              (d window))))))
    (glfw set-mouse-button-callback window
      (reify GLFWMouseButtonCallbackI
        (invoke [this window button action mods]
          #_(println "responding to button " action button)
          (condp = action
            (GLFW press) nil
            ;; release:
            (when-let [d (get-in defs [:mouse nil button])]
              (d window))
            )))))
  window)

(defn teardown [window]
  (Callbacks/glfwFreeCallbacks window))
