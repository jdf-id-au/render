(ns render.util
  (:require [clojure.string :as str])
  (:import (java.io File)
           (org.joml Matrix4f Matrix4x3f Vector3f Vector4f)))

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

(defn hex-str ; ─────────────────────────────────── represent and convert things
  "Just show the int as hex. e.g. 16 -> 0x10" ; how to output literal 0x10? custom pretty-printer?
  [i]
  (str "0x" (Integer/toUnsignedString (unchecked-int i) 16)))

(defn rgba->argb [i]
  (let [rgb (bit-and (bit-shift-right i 8) 0xFFFFFF)
        a (bit-and i 0xFF)]
    (unchecked-int (+ (bit-shift-left a 24) rgb))))

(defn abgr->argb [i]
  (let [a (bit-and (bit-shift-right i 24) 0xFF)
        b (bit-and (bit-shift-right i 16) 0xFF)
        g (bit-and (bit-shift-right i  8) 0xFF)
        r (bit-and i 0xFF)]
    (unchecked-int (+ (bit-shift-left a 24)
                     (bit-shift-left r 16)
                     (bit-shift-left g 8)
                     b))))

(defn bracket
  "Render `coll` of floats as string showing row-major matrix with `cols` columns."
  [cols & coll]
  {:pre [(pos? cols) (zero? (mod (count coll) cols))]}
  (let [[LU RU LM RM LL RL L R] (seq "⎡⎤⎢⎥⎣⎦[]" ; proper brackets
                                  #_"┌┐││└┘[]") ; box drawing
        rows (/ (count coll) cols)]
    (apply str ; more readable than StringWriter
      (reduce (fn [acc [i v]]
                (let [v (format "% 5.1f " v)
                      row (quot i cols)
                      col (mod i cols)]
                  (condp = col
                    0 (conj acc (case rows
                                 1 L
                                  (condp = row
                                    0 LU
                                    (dec rows) LL
                                    LM)) v)
                    (dec cols) (conj acc v (case rows
                                             1 R
                                             (condp = row
                                               0 RU
                                               (dec rows) RL
                                               RM)) \newline)
                    (conj acc v)))
                )
        [] (map-indexed vector coll)))))

(defprotocol MathFormat
  (notate [this]))

(extend-protocol MathFormat
  Matrix4f
  (notate [x] (bracket 4 
              (.m00 x) (.m10 x) (.m20 x) (.m30 x)
              (.m01 x) (.m11 x) (.m21 x) (.m31 x)
              (.m02 x) (.m12 x) (.m22 x) (.m32 x)
              (.m03 x) (.m13 x) (.m23 x) (.m33 x)))
  Matrix4x3f
  (notate [x] (bracket 4 
              (.m00 x) (.m10 x) (.m20 x) (.m30 x)
              (.m01 x) (.m11 x) (.m21 x) (.m31 x)
              (.m02 x) (.m12 x) (.m22 x) (.m32 x))))

(comment
  (hex-str 0xaabbccdd) ; => "0xaabbccdd"
  (hex-str (rgba->argb 0xaabbccdd)) ; => "0xddaabbcc"
  (hex-str (rgba->argb 0xaabbcc00)) ; => "0xaabbcc"
  (hex-str (rgba->argb 0xaabbccff)) ; => "0xffaabbcc"
  (hex-str (rgba->argb 0xffff95bf)) ; => "0xbfffff95"
  )

(defn mapmap ; ──────────────────────────────── from jdf/comfort for print-table
  "Map f over each coll within c."
  [f c]
  (map #(map f %) c))

(defn print-table
  "Doesn't currently support ragged tables. Better for multiline vals than clojure.pprint/print-table."
  ;; entirely unoptimised
  [& rows]
  {:pre [(apply = (map count rows))]}
  (let [rcl (mapmap (fnil str/split-lines "") rows) ; nested rows->cols->lines
        widest-line (fn [lines] (->> lines (map count) (apply max)))
        widths (apply map ; i.e. all first cols then all second cols
                 (fn [& cols] (->> cols (map widest-line) (apply max 1)))
                 rcl)
        heights (map
                  (fn [cols] (->> cols (map count) (apply max 1)))
                  rcl)
        [TL T TT TR ; top left, top (plain), top tick, top right
         L LT R RT
         H V HT
         BL B BT BR] #_(seq "┌─┬┐│├│┤─│┼└─┴┘") (repeat \space)]
    (->>
      (concat
        [(loop [[w & r] widths
                acc [TL]]
           (if r
             (recur r (concat acc (repeat w T) [TT]))
             (apply str (concat acc (repeat w T) [TR]))))]
        (for [[r [h row]] (map-indexed vector (map vector heights rcl))
              l (range (inc h))
              :when (not (and (= l h) (= (inc r) (count rcl))))]
          (str
            (if (= l h) LT L)
            (->>
              (for [[w col] (map vector widths row)
                    :let [v (if (= l h)
                              (apply str (repeat w H))
                              (get col l))
                          n (count v)]
                    ]
                (apply str v (repeat (- w n) \space)))
              (interpose (if (= l h) HT V))
              (apply str))
            (if (= l h) RT R)))
        [(loop [[w & r] widths
                acc [BL]]
           (if r
             (recur r (concat acc (repeat w B) [BT]))
             (apply str (concat acc (repeat w B) [BR]))))])
      (map println) dorun)))

(defmacro print-table-with
  "Print table of unevaluated xs then the values of (f x).
  Useful for narrow multiline string values."
  [f & xs]
  `(print-table ~(mapv str xs) (map ~f ~(vec xs))))
