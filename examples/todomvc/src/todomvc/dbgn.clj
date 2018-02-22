(ns todomvc.dbgn
  (:require [clojure.zip :as z]
            [cljs.analyzer :as analyzer]
            [debux.common.macro-specs :as ms]
            [debux.common.skip :as sk]
            [debux.common.util :as ut]
            [debux.macro-types :as mt]
            [debux.cs.macro-types :as cs.mt]
            [re-frame.trace :as trace]))


;;; Basic strategy for dbgn

;; 1. original form
;;
;; (let [a 10
;;       b (+ a 20)]
;;   (+ a b))

;; 2. after insert-skip
;;
;; (let (o-skip [(skip a) 10
;;               (skip b) (+ a 20)])
;;   (+ a b))

;; 3. after insert-d
;;
;; (d (let (o-skip [(skip a) 10
;;                  (skip b) (d (+ (d a) 20))])
;;      (d (+ (d a) (d b)))))

;; 4. after remove-skip
;;
;; (d (let [a 10
;;          b (d (+ (d a) 20))]
;;      (d (+ (d a) (d b))))


(defn- macro-types [env]
  (if (ut/cljs-env? env)
    @cs.mt/macro-types*
    @mt/macro-types*))


;;; insert skip
(defn insert-skip
  "Marks the form to skip."
  [form env]
  (loop [loc (ut/sequential-zip form)]
    (let [node (z/node loc)]
      ;(ut/d node)
      (cond
        (z/end? loc) (z/root loc)

        ;; in case of (skip ...)
        (and (seq? node) (= `ms/skip (first node)))
        (recur (ut/right-or-next loc))

        (and (seq? node) (symbol? (first node)))
        (let [sym (ut/ns-symbol (first node) env)]
          ;(ut/d sym)
          (cond
            ((:def-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-in-def node))
                z/next
                recur)

            ((:defn-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-in-defn node))
                z/next
                recur)

            ((:fn-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-in-fn node))
                z/next
                recur)


            (or ((:let-type (macro-types env)) sym)
                ((:loop-type (macro-types env)) sym))
            (-> (z/replace loc (sk/insert-skip-in-let node))
                z/next
                recur)

            ((:letfn-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-in-letfn node))
                z/next
                recur)


            ((:for-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-in-for node))
                z/next
                recur)

            ((:case-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-in-case node))
                z/next
                recur)


            ((:skip-arg-1-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-arg-1 node))
                z/next
                recur)

            ((:skip-arg-2-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-arg-2 node))
                z/next
                recur)

            ((:skip-arg-1-2-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-arg-1-2 node))
                z/next
                recur)

            ((:skip-arg-2-3-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-arg-2-3 node))
                z/next
                recur)

            ((:skip-arg-1-3-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-arg-1-3 node))
                z/next
                recur)

            ((:skip-form-itself-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-form-itself node))
                ut/right-or-next
                recur)


            ((:expand-type (macro-types env)) sym)
            (-> (z/replace loc (seq (if (ut/cljs-env? env)
                                      (analyzer/macroexpand-1 {} node)
                                      (macroexpand-1 node))))
                recur)

            ((:dot-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-in-dot node))
                z/down z/right
                recur)

            :else
            (recur (z/next loc))))

        :else (recur (z/next loc))))))


;;; insert/remove d
(defn insert-d [form d-sym env]
  (loop [loc (ut/sequential-zip form)]
    (let [node (z/node loc)]
      ;(ut/d node)
      (cond
        (z/end? loc) (z/root loc)

        ;; in case of (skip ...)
        (and (seq? node) (= `ms/skip (first node)))
        (recur (ut/right-or-next loc))

        ;; in case of (o-skip ...)
        (and (seq? node)
             (= `ms/o-skip (first node)))
        (cond
          ;; <ex> (o-skip [(skip a) ...])
          (vector? (second node))
          (recur (-> loc z/down z/next z/down))

          ;; <ex> (o-skip (recur ...))
          :else
          (recur (-> loc z/down z/next z/down ut/right-or-next)))

        ;; in case that the first symbol is defn/defn-
        (and (seq? node)
             (symbol? (first node))
             (`#{defn defn-} (ut/ns-symbol (first node) env)))
        (recur (-> loc z/down z/next))

        ;; in case of the first symbol except defn/defn-/def
        (and (seq? node) (ifn? (first node)))
        (recur (-> (z/replace loc (concat [d-sym] [node]))
                   z/down z/right z/down ut/right-or-next))

        (vector? node)
        (recur (-> (z/replace loc (concat [d-sym] [node]))
                   z/down z/right z/down))

        ;; in case of symbol, map, or set
        (or (symbol? node) (map? node) (set? node))
        (recur (-> (z/replace loc (concat [d-sym] [node]))
                   ut/right-or-next))

        :else
        (recur (z/next loc))))))

(defn remove-d [form d-sym]
  (loop [loc (ut/sequential-zip form)]
    (let [node (z/node loc)]
      ;(ut/d node)
      (cond
        (z/end? loc) (z/root loc)

        ;; in case of (d ...)
        (and (seq? node)
             (= d-sym (first node)))
        (recur (z/replace loc (second node)))

        :else
        (recur (z/next loc))))))


(defmacro d [form]
  `(let [opts#   ~'+debux-dbg-opts+
         msg#    (:msg opts#)
         n#      (or (:n opts#) @ut/print-seq-length*)

         result# ~form
         result# (ut/take-n-if-seq n# result#)
         code#   (get-in trace/*current-trace* [:tags :code] [])]
     (trace/merge-trace! {:tags {:code (conj code# {:form '~(remove-d form 'todomvc.dbgn/d) :result result#})}})
     #_(ut/print-form-with-indent (ut/form-header '~(remove-d form 'debux.dbgn/d) msg#)
                                @ut/indent-level*)
     #_(ut/pprint-result-with-indent result# @ut/indent-level*)
     result#))


;;; remove skip
(defn remove-skip [form]
  (loop [loc (ut/sequential-zip form)]
    (let [node (z/node loc)]
      ;(ut/d node)
      (cond
        (z/end? loc) (z/root loc)

        ;; in case of (skip ...)
        (and (seq? node)
             (= `ms/skip (first node)))
        (recur (-> (z/replace loc (second node))
                   ut/right-or-next))

        ;; in case of (o-skip ...)
        (and (seq? node)
             (= `ms/o-skip (first node)))
        (recur (-> (z/replace loc (second node))
                   z/next))

        :else
        (recur (z/next loc))))))


;;; dbgn
(defmacro dbgn
  "DeBuG every Nested forms of a form.s"
  [form & [{:keys [condition] :as opts}]]
  `(let [~'+debux-dbg-opts+ ~(if (ut/cljs-env? &env)
                               (dissoc opts :style :js :once)
                               opts)
         condition# ~condition]
     (try
       (if (or (nil? condition#) condition#)
         (let [title# (str "\ndbgn: " (ut/truncate (pr-str '~form)) " =>")]
           (trace/merge-trace! {:tags {:f1 (pr-str '~form)
                                       :form   '~(remove-d form 'todomvc.dbgn/d)}})
           (println title#)
           ~(-> (if (ut/include-recur? form)
                  (sk/insert-o-skip-for-recur form &env)
                  form)
                (insert-skip &env)
                (insert-d 'todomvc.dbgn/d &env)
                remove-skip))
         ~form)
       (catch Exception ~'e (throw ~'e)))))
