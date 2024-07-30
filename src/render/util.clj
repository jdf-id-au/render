(ns render.util
  (:require [clojure.string :as str])
  (:import (java.io File)))

(defmacro with-resource ; ───────────────────────── pseudo-RAII from jdf/comfort
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

(defn kebab->pascal [s] ; ────────────────────────────── ergonomic abbreviations
  (let [segs (str/split (str s) #"-")]
    (->> segs (map str/capitalize) (apply str))))

(defn kebab->snake [s]
  (str/replace s \- \_))

(defn kebab->screaming-snake [s]
  (-> s kebab->snake str/upper-case))

;; TODO gen/import docs for repl use...

(defn rename
  [prefix converter sym]
  (symbol (str prefix (converter sym))))

(defmacro bgfx
  "Briefer function calls"
  {:clj-kondo/ignore [:unresolved-symbol]}
  [function-name & args]
  (let [f (rename 'org.lwjgl.bgfx.BGFX/bgfx_ kebab->snake function-name)]
    `(~f ~@args)))

(defmacro BGFX ; needs to be macro so can use unquoted symbols
  "Briefer enums"
  {:clj-kondo/ignore [:unresolved-symbol]}
  [& enum-names]
  (let [f (partial rename 'org.lwjgl.bgfx.BGFX/BGFX_ kebab->screaming-snake)
        [e & r] (map f enum-names)]
    (if r `(bit-or ~e ~@r) e)))

(defmacro glfw
  "Briefer function calls"
  {:clj-kondo/ignore [:unresolved-symbol]}
  [function-name & args]
  (let [f (rename 'org.lwjgl.glfw.GLFW/glfw kebab->pascal function-name)]
    `(~f ~@args)))

(defmacro GLFW
  "Briefer enums" ; via `public static final long` at least...
  {:clj-kondo/ignore [:unresolved-symbol]}
  [& enum-names]
  (let [f (partial rename 'org.lwjgl.glfw.GLFW/GLFW_ kebab->screaming-snake)
        [e & r] (map f enum-names)]
    (if r `(bit-or ~e ~@r) e)))

(comment
  ;; try C-c RET for cider-macroexpand-1
  (bgfx create-frame-buffer-from-handles sb true)
  (BGFX texture-blit-dst texture-read-back)
  (glfw get-cursor-pos window cur-x cur-y)
  (GLFW cocoa-retina-framebuffer)
  )

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
