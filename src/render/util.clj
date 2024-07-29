(ns render.util
  (:require [clojure.string :as str])
  (:import (java.io File)))

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

;; TODO gen/import docs for repl use...

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

(defn temp-file [suffix]
  (doto (File/createTempFile "render" suffix)
    (.deleteOnExit)))

;; ──────────────────────────────────────────────────────── DAG from jdf/comfort
;; for reference https://groups.google.com/g/clojure/c/h1m6Qjuh3wA/m/pRqNY5HlYJEJ

(defn add-node-id
  [graph id]
  (if (graph id)
    graph
    (assoc graph id {:next #{} :prev #{}})))

(defn add-edge
  [graph from-id to-id]
  (-> graph
    (add-node-id from-id)
    (add-node-id to-id)
    (update-in [from-id :next] conj to-id)
    (update-in [to-id :prev] conj from-id)))

(defn graph
  "Use to reduce colls of nodes into map of {node-id {:prev #{node-id} :next #{node-id}}}.
   from-id = to-id only add node-id, not an edge.
   edge-fn needs to return [from-id to-id]"
  [graph [from-id to-id :as node]]
  (if (and from-id to-id)
    (if (= from-id to-id) ; strictly this is a cycle, but is elided
      (add-node-id graph from-id)
      (add-edge graph from-id to-id))
    (do
      (println "Skipped node (need both from-id and to-id):" node)
      graph)))

(defn dag-impl
  "Cycle at root will return empty map." ; FIXME?
  ([graph] (into {} (for [[node-id {:keys [prev]}] graph
                          :when (empty? prev)]
                      [node-id (dag-impl node-id graph '())])))
  ([node-id graph path]
   (let [seen (set path)
         proposed (conj path node-id)]
     (if (seen node-id)
       (throw (ex-info "cycle detected" {:node-id node-id :path path}))
       (some->> (for [child (get-in graph [node-id :next])]
                 [child (dag-impl child graph proposed)])
         seq (into {}))))))

(defn dag [nodes]
  (->> nodes (reduce graph {}) dag-impl))

(defn deps-order
  "List ids from nodes of [from-id to-id] such that no id depends
  on one which appears later in list."
  [nodes]
  (let [dag (dag nodes)
        queue (loop [[k & r :as queue] nil
                     [[dk dv] & dr :as deps] dag]
                (if dv
                  (recur (conj queue dk) (concat dv dr))
                  (if dk
                    (recur (conj queue dk) dr)
                    queue)))]
    (distinct queue)))
