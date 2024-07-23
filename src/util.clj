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

(defn kebab->pascal [s]
  (let [segs (str/split (str s) #"-")]
    (->> segs (map str/capitalize) (apply str))))

(defmacro glfw
  "Briefer function calls"
  {:clj-kondo/ignore [:unresolved-symbol]}
  [function-name & args]
  (let [f# (symbol (str "org.lwjgl.glfw.GLFW/glfw" (kebab->pascal function-name)))]
    `(~f# ~@args)))

(defmacro GLFW
  "Briefer enums" ; via `public static final long`?
  {:clj-kondo/ignore [:unresolved-symbol]}
  [enum-name]
  (let [e# (symbol (str "org.lwjgl.glfw.GLFW/GLFW_" (-> enum-name (str/replace  \- \_) str/upper-case)))]
    `~e#))

;; trying clj-commons/primitive-math instead
#_(defn cast->byte
  "Cast u8 to i8 aka java byte.
   u8 0        ..127      ..128      ..255
   i8 0        ..127      ..-128     ..-1
   0b 0000 0000..0000 1111..0001 0000..1111 1111
   0x 00       ..7f       ..80       ..ff"
  [i]
  (byte (if (> i Byte/MAX_VALUE) (- i  0x100) i)))

