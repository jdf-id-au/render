(ns render.shaders
  "Build shaders; relies on host shell and version+platform matching binaries!
  e.g. https://www.lwjgl.org/browse/release/3.3.4/macosx/x64/bgfx-tools"
  ;; TODO lisp-curse-write shaders too? infer/learn/fake-until-make
  (:require [render.util :as ru]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import (java.io StringWriter)
           (java.nio.file Files))
  (:refer-clojure :exclude [compile]))

(defn add-include [code]
  (let [w (StringWriter.)
        [io-lines normal-lines] (->> code str/split-lines
                                  (remove str/blank?)
                                  (partition-by #(str/starts-with? % "$")))]
    (doseq [group [io-lines
                   ["#include \"bgfx_shader.sh\""]
                   normal-lines]
            line group]
      (.write w line)
      (.write w (int \newline)))
    (.toString w)))

(defn compile
  "Compile supplied shaders using `shaderc` on OS command line."
  [{:keys [varying] :as code}]
  {:pre [varying (some code #{:vertex :fragment :compute})]}
  (let [vdsc (ru/temp-file "varying.def.sc")
        platform (condp #(str/starts-with? %2 %1) (System/getProperty "os.name")
                   "Linux" "linux"
                   "Mac" "osx"
                   "Windows" "windows")
        profile ({"linux" "440" ; GLSL (OpenGL)
                  "osx" "metal" ; MSL (Metal)
                  "windows" "s_5_0" ; HLSL (DirectX)
                  } platform)]
    (spit vdsc (str/trim varying))
    ;; TODO could consider compiling and loading asynchronously...
    (into {}
      (for [shader #{:vertex :fragment :compute}
            :when (shader code)
            :let [in (ru/temp-file (name shader))
                  plus-include (->> code shader add-include)
                  _ (spit in plus-include)
                  out (ru/temp-file (str (name shader) ".bin"))]]
        (let [proc (.start (ProcessBuilder.
                             ["shaderc"
                              "-f" (.getCanonicalPath in)
                              "-i" (->> "bgfx_shader.sh" io/resource io/file .getParent) 
                              "--varyingdef" (.getCanonicalPath vdsc)
                              "-o" (.getCanonicalPath out)
                              "--type" (name shader)
                              "--platform" platform
                              "--profile" profile]))
              exit-code (.waitFor proc)
              stdout (slurp (.inputReader proc))
              stderr (slurp (.errorReader proc))]
          [shader
           (case exit-code
             0 (-> out .toPath Files/readAllBytes)
             (throw (ex-info (str "shaderc failed with exit code " exit-code)
                      {:exit-code exit-code
                       :platform platform :profile profile
                       :code (str/split-lines plus-include)
                       :file (.getCanonicalPath in)
                       :stdout (str/split-lines stdout)
                       :stderr (str/split-lines stderr)})))])))))

;; Maybe don't do this yet ─────────────────────────────────────────────────────

(def varying-labels
  #{:POSITION :NORMAL :BINORMAL :TANGENT
    :COLOR0
    :TEXCOORD0 :TEXCOORD1 :TEXCOORD2 :TEXCOORD3 :TEXCOORD4 :TEXCOORD5 :TEXCOORD6 :TEXCOORD7
    :FOG})

(def vertex-shader-inputs
  {:a_position :POSITION
   :a_normal :NORMAL
   :a_tangent :TANGENT
   :a_bitangent :BITANGENT
   :a_color0 :COLOR0
   :a_color1 :COLOR1
   :a_color2 nil; i.e. not seen in examples; may be sensible to implement
   :a_color3 nil
	 :a_indices nil
   :a_weight nil
	 :a_texcoord0 :TEXCOORD0
   :a_texcoord1 nil
   :a_texcoord2 nil
   :a_texcoord3 nil
   :a_texcoord4 nil
   :a_texcoord5 nil
   :a_texcoord6 nil
   :a_texcoord nil
	 :i_data0 nil ; e.g. TEXCOORD7...
   :i_data1 nil
   :i_data2 nil
   :i_data3 nil
   :i_data4 nil
   nil nil})

(defn vecn [n & defaults]
  {:pre [(#{2 3 4} n)
         (#{0 n} (count defaults))
         (every? number? defaults)]}
  (let [ty (str "vec" n)]
    [ty
     (when (seq defaults) 
       (str " = " ty \( (->> defaults (map float) (interpose ", ") (apply str)) \)))]))
