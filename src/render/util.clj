(ns render.util
  (:require [clojure.string :as str]))

(defmacro with-resource ; from jdf/comfort, to remove dependency
  "bindings => [name init deinit ...]

  Evaluates body in a try expression with names bound to the values
  of the inits, and a finally clause that calls (deinit name) on
  each name in reverse order."
  {:clj-kondo/ignore [:unresolved-symbol]}
  [bindings & body]
  (assert (vector? bindings))
  (assert (zero? (mod (count bindings) 3)))
  (cond
    (= (count bindings) 0) `(do ~@body)
    (symbol? (bindings 0)) `(let ~(subvec bindings 0 2) 
                              (try
                                (with-resource ~(subvec bindings 3) ~@body)
                                (finally
                                  (when-let [deinit# ~(bindings 2)]
                                    (deinit# ~(bindings 0))))))
    :else (throw (IllegalArgumentException.
                   "with-resource only allows Symbols in bindings"))))

(defn kebab->pascal [s]
  (let [segs (str/split (str s) #"-")]
    (->> segs (map str/capitalize) (apply str))))

(defn kebab->snake [s]
  (str/replace s \- \_))

(defn kebab->screaming-snake [s]
  (-> s kebab->snake str/upper-case))

(defmacro bgfx
  "Briefer function calls"
  {:clj-kondo/ignore [:unresolved-symbol]}
  [function-name & args]
  (let [f# (symbol (str "org.lwjgl.bgfx.BGFX/bgfx_" (kebab->snake function-name)))]
    `(~f# ~@args)))

(defmacro BGFX ; TODO consider reader literals?
  "Briefer enums"
  {:clj-kondo/ignore [:unresolved-symbol]}
  [enum-name]
  (symbol (str "org.lwjgl.bgfx.BGFX/BGFX_" (kebab->screaming-snake enum-name))))

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
  (symbol (str "org.lwjgl.glfw.GLFW/GLFW_" (kebab->screaming-snake enum-name))))

#_(defn cast->byte
  "Cast u8 to i8 aka java byte.
   u8 0        ..127      ..128      ..255
   i8 0        ..127      ..-128     ..-1
   0b 0000 0000..0000 1111..0001 0000..1111 1111
   0x 00       ..7f       ..80       ..ff"
  [i]
  (byte (if (> i Byte/MAX_VALUE) (- i  0x100) i)))

#_(defn cast->int
  "Cast i64 aka long which should be u32 to i32
   u32 0       .. 65536    .. 4294967296"
  [i]
  (int (if (> i Integer/MAX_VALUE) (- i 0x100000000) i)))

(defn current-thread []
  (.getName (Thread/currentThread)))
