(ns util
  (:require [clojure.string :as str]))

(defmacro bgfx
  {:clj-kondo/ignore [:unresolved-symbol]}
  [function-name & args]
  (let [f# (symbol (str "BGFX/bgfx_" (str/replace function-name \- \_)))]
    `(~f# ~@args)))

(defn snake->pascal [s]
  (let [segs (str/split (str s) #"-")]
    (->> segs (map str/capitalize) (apply str))))

(defmacro glfw
  {:clj-kondo/ignore [:unresolved-symbol]}
  [function-name & args]
  (let [f# (symbol (str "GLFW/glfw" (snake->pascal function-name)))]
    `(~f# ~@args)))
