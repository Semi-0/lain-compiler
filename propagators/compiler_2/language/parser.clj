(ns propagators.compiler-2.language.parser
  "Reader-backed source parser for compile-2 expressions.

  The surface language is intentionally small. `::` is not valid EDN, so the
  parser rewrites list-head `::` into the internal `:compiler/network` marker
  before reading."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [propagators.compiler-2.language.ast :as ast])
  (:import [java.io PushbackReader StringReader]))

(def network-marker :compiler/network)
(def ^:private eof (Object.))

(declare parse-form)

(defn- parse-error [message data]
  (throw (ex-info message data)))

(defn- preprocess-source [source]
  (-> source
      (str/replace #"\(\s*::(?=\s)" (str "(" network-marker))
      (str/replace #"(?<=\(|\s)be:/" "be:divide")))

(defn read-form
  "Read exactly one source form."
  [source]
  (let [reader (PushbackReader. (StringReader. (preprocess-source source)))
        form (edn/read {:eof eof} reader)
        trailing (edn/read {:eof eof} reader)]
    (when (identical? eof form)
      (parse-error "empty compiler-2 source" {:source source}))
    (when-not (identical? eof trailing)
      (parse-error "compiler-2 source must contain exactly one form"
                   {:source source
                    :form form
                    :trailing trailing}))
    form))

(defn- symbol-vector [v role]
  (when-not (vector? v)
    (parse-error (str role " must be a vector") {:value v}))
  (when-not (every? symbol? v)
    (parse-error (str role " must contain only symbols") {:value v}))
  v)

(defn- body-form [forms role]
  (when-not (seq forms)
    (parse-error (str role " requires at least one body expression")
                 {:body forms}))
  (if (= 1 (count forms))
    (parse-form (first forms))
    (apply ast/sequence* (map parse-form forms))))

(defn- parse-let-cell [[names & body]]
  (ast/let-cell (symbol-vector names "let-cell bindings")
                (body-form body "let-cell")))

(defn- parse-let [[bindings & body]]
  (when-not (vector? bindings)
    (parse-error "let bindings must be a vector" {:bindings bindings}))
  (when (odd? (count bindings))
    (parse-error "let bindings must contain name/expression pairs"
                 {:bindings bindings}))
  (let [pairs (partition 2 bindings)]
    (doseq [[name _expr] pairs]
      (when-not (symbol? name)
        (parse-error "let binding names must be symbols"
                     {:binding name})))
    (ast/let* (mapv (fn [[name expr]]
                      [name (parse-form expr)])
                    pairs)
              (body-form body "let"))))

(defn- parse-network [[params & body]]
  (ast/network (symbol-vector params ":: params")
               (body-form body "::")))

(defn- parse-cell [[params & body]]
  (ast/network (symbol-vector params "cell params")
               (body-form body "cell")))

(defn- parse-network-form [[inputs outputs & body]]
  (ast/compound {:inputs (symbol-vector inputs "network inputs")
                 :output (symbol-vector outputs "network outputs")}
                (body-form body "network")))

(defn- parse-def-net [[name inputs outputs & body]]
  (when-not (symbol? name)
    (parse-error "def-net name must be a symbol" {:name name}))
  (ast/def-net name
               (symbol-vector inputs "def-net inputs")
               (symbol-vector outputs "def-net outputs")
               (body-form body "def-net")))

(defn- parse-def-constraint [[name inputs & body]]
  (when-not (symbol? name)
    (parse-error "def-constraint name must be a symbol" {:name name}))
  (ast/def-constraint name
                      (symbol-vector inputs "def-constraint inputs")
                      (body-form body "def-constraint")))

(defn- parse-def [[name expr & more]]
  (when-not (symbol? name)
    (parse-error "def name must be a symbol" {:name name}))
  (when (seq more)
    (parse-error "def expects a name and optional expression"
                 {:name name :expr expr :extra more}))
  (ast/def* name (when (some? expr)
                   (parse-form expr))))

(defn- parse-def-cell [[name maybe-expr & body]]
  (when-not (symbol? name)
    (parse-error "def-cell name must be a symbol" {:name name}))
  (cond
    (nil? maybe-expr)
    (ast/def* name nil)

    (vector? maybe-expr)
    (ast/def-cell name
                  (symbol-vector maybe-expr "def-cell inputs")
                  (body-form body "def-cell"))

    (seq body)
    (parse-error "def-cell expression form expects only a name and expression"
                 {:name name :expr maybe-expr :extra body})

    :else
    (ast/def* name (parse-form maybe-expr))))

(defn- parse-def-cells [names]
  (when-not (seq names)
    (parse-error "def-cells expects at least one name" {:names names}))
  (doseq [name names]
    (when-not (symbol? name)
      (parse-error "def-cells names must be symbols" {:name name})))
  (apply ast/sequence* (map #(ast/def* % nil) names)))

(defn- suffix-symbol
  [sym suffix]
  (symbol (namespace sym) (str (name sym) suffix)))

(defn- behavior-declaration-forms
  [name]
  (when-not (symbol? name)
    (parse-error "behavior name must be a symbol" {:name name}))
  (let [events-name (suffix-symbol name "-events")]
    [(list 'def-cells events-name name)
     (list 'be:latest events-name name)]))

(defn- behavior-wiring-forms
  [name]
  (subvec (vec (behavior-declaration-forms name)) 1))

(defn- parse-def-behavior [[name & more]]
  (when (or (nil? name) (seq more))
    (parse-error "def-behavior expects one name"
                 {:name name :extra more}))
  (apply ast/sequence* (map parse-form (behavior-declaration-forms name))))

(defn- parse-def-behaviors [names]
  (when-not (seq names)
    (parse-error "def-behaviors expects at least one name" {:names names}))
  (apply ast/sequence*
         (map parse-form
              (mapcat behavior-declaration-forms names))))

(defn- parse-let-behavior [[names & body]]
  (let [names (symbol-vector names "let-behavior bindings")
        cell-names (mapcat (fn [name] [(suffix-symbol name "-events") name])
                           names)
        body* (concat (mapcat behavior-wiring-forms names) body)]
    (ast/let-cell cell-names
                  (body-form body* "let-behavior"))))

(declare parse-cond-form)

(defn- parse-compound-spec [spec]
  (cond
    (map? spec)
    (let [{:keys [inputs output]} spec]
      [(symbol-vector inputs "compound inputs") output])

    (vector? spec)
    [(symbol-vector spec "compound inputs") nil]

    :else
    (parse-error "compound expects an input vector or {:inputs ... :output ...}"
                 {:spec spec})))

(defn- parse-compound [[spec & rest-args]]
  (let [[inputs map-output] (parse-compound-spec spec)
        [output body] (if (some? map-output)
                        [map-output rest-args]
                        [(first rest-args) (rest rest-args)])]
    (when-not (or (symbol? output)
                  (and (vector? output) (every? symbol? output)))
      (parse-error "compound output must be a symbol or symbol vector"
                   {:output output}))
    (ast/compound {:inputs inputs
                   :output output}
                  (body-form body "compound"))))

(defn- removed-form [form]
  (parse-error (str (first form) " is not part of compiler-2 target syntax")
               {:form form}))

(defn- parse-if [[condition then-expr else-expr & more]]
  (when (or (nil? condition)
            (nil? then-expr)
            (nil? else-expr)
            (seq more))
    (parse-error "if expects condition, then expression, and else expression"
                 {:condition condition
                  :then then-expr
                  :else else-expr
                  :extra more}))
  (ast/app (ast/sym 'if)
           (parse-form condition)
           (parse-form then-expr)
           (parse-form else-expr)))

(defn- parse-when [[condition & body]]
  (when (or (nil? condition)
            (not (seq body)))
    (parse-error "when expects a condition and at least one body expression"
                 {:condition condition
                  :body body}))
  (ast/when-topology (parse-form condition)
                     (body-form body "when")))

(defn- parse-cond-form [[clauses]]
  (when-not (vector? clauses)
    (parse-error "cond expects one vector of condition/expression clauses"
                 {:clauses clauses}))
  (when (odd? (count clauses))
    (parse-error "cond clauses must contain condition/expression pairs"
                 {:clauses clauses}))
  (letfn [(build [pairs]
            (let [[[condition expr] & more] pairs]
              (cond
                (nil? condition)
                (parse-error "cond expects at least one clause"
                             {:clauses clauses})

                (= 'else condition)
                (if (seq more)
                  (parse-error "cond else clause must be last"
                               {:clauses clauses})
                  (parse-form expr))

                (seq more)
                (ast/app (ast/sym 'if)
                         (parse-form condition)
                         (parse-form expr)
                         (build more))

                :else
                (ast/app (ast/sym 'switch)
                         (parse-form expr)
                         (parse-form condition)))))]
    (build (partition 2 clauses))))

(defn- parse-application [forms]
  (apply ast/app (map parse-form forms)))

(defn parse-form
  "Normalize one reader form into compiler-2 source IR."
  [form]
  (cond
    (seq? form)
    (case (first form)
      let (parse-let (rest form))
      let-cell (parse-let-cell (rest form))
      :compiler/network (parse-network (rest form))
      cell (parse-cell (rest form))
      network (parse-network-form (rest form))
      def-net (parse-def-net (rest form))
      def-constraint (parse-def-constraint (rest form))
      def (parse-def (rest form))
      def-cell (parse-def-cell (rest form))
      def-cells (parse-def-cells (rest form))
      def-behavior (parse-def-behavior (rest form))
      def-behaviour (parse-def-behavior (rest form))
      def-behaviors (parse-def-behaviors (rest form))
      def-behaviours (parse-def-behaviors (rest form))
      define-behaviors (parse-def-behaviors (rest form))
      define-behaviours (parse-def-behaviors (rest form))
      let-behavior (parse-let-behavior (rest form))
      let-behaviour (parse-let-behavior (rest form))
      compound (parse-compound (rest form))
      if (parse-if (rest form))
      when (parse-when (rest form))
      cond (parse-cond-form (rest form))
      app-> (removed-form form)
      do (removed-form form)
      let-network (removed-form form)
      let-compound (removed-form form)
      (parse-application form))

    (vector? form)
    (mapv parse-form form)

    (symbol? form)
    (ast/sym form)

    :else
    (ast/lit form)))

(defn parse-string
  "Parse one source string into compiler-2 source IR."
  [source]
  (parse-form (read-form source)))

(def parse parse-string)


