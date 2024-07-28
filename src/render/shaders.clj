(ns render.shaders
  "Build shaders; relies on host shell and version+platform matching binaries!
  e.g. https://www.lwjgl.org/browse/release/3.3.4/macosx/x64/bgfx-tools"
  ;; TODO lisp-curse-write shaders too? infer/learn/fake-until-make
  )

(defn compile [{:keys [varying vertex fragment]}]
  ;; drop blank first line
  ;; TODO insert contents of "bgfx_shader.sh" (after $input and $output lines?)
  ;; TODO write to tmp files
  
  (str "shaderc -f " :fragment-tmp
    " --varyingdef " :varying-tmp
    " -o " :suitable-path ; or just tmp and load immediately!
    " --type " :vertex-or-fragment
    " --platform " :suitable
    " --profile " :lookup
  ))

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



