(ns util
  (:require [clojure.string :as str]))

(defmacro bgfx
  "Briefer function calls"
  {:clj-kondo/ignore [:unresolved-symbol]}
  [function-name & args]
  (let [f# (symbol (str "org.lwjgl.bgfx.BGFX/bgfx_" (str/replace function-name \- \_)))]
    `(~f# ~@args)))

(defmacro BGFX ; TODO consider reader literals?
  "Briefer enums"
  {:clj-kondo/ignore [:unresolved-symbol]}
  [enum-name]
  (let [e# (symbol (str "org.lwjgl.bgfx.BGFX/BGFX_" (-> enum-name (str/replace \- \_) str/upper-case)))]
    `~e#))

(defn snake->pascal [s]
  (let [segs (str/split (str s) #"-")]
    (->> segs (map str/capitalize) (apply str))))

(defmacro glfw
  "Briefer function calls"
  {:clj-kondo/ignore [:unresolved-symbol]}
  [function-name & args]
  (let [f# (symbol (str "org.lwjgl.glfw.GLFW/glfw" (snake->pascal function-name)))]
    `(~f# ~@args)))

(defmacro GLFW
  "Briefer enums"
  {:clj-kondo/ignore [:unresolved-symbol]}
  [enum-name]
  (let [e# (symbol (str "org.lwjgl.glfw.GLFW/GLFW_" (-> enum-name (str/replace  \- \_) str/upper-case)))]
    `~e#))
