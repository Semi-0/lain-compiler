(ns propagators.compiler-2.parser
  "Reader-backed source parser for compile-2 expressions."
  (:require [clojure.edn :as edn]
            [propagators.compiler-2.ast :as ast])
  (:import [java.io PushbackReader StringReader]))

(def ^:private eof (Object.))

(declare parse-form)

(defn- parse-error [message data]
  (throw (ex-info message data)))

(defn read-form
  "Read exactly one EDN/Clojure data form from `source`."
  [source]
  (let [reader (PushbackReader. (StringReader. source))
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

(defn- body-form [forms]
  (case (count forms)
    0 (parse-error "body requires at least one expression" {:body forms})
    1 (parse-form (first forms))
    (apply ast/do* (map parse-form forms))))

(defn- parse-let-cell [args]
  (let [[names & body] args]
    (ast/let-cell (symbol-vector names "let-cell bindings")
                  (body-form body))))

(defn- parse-let-compound [args]
  (let [[name compound-expr & body] args]
    (when-not (symbol? name)
      (parse-error "let-compound name must be a symbol" {:name name}))
    (when-not compound-expr
      (parse-error "let-compound requires a compound expression" {:args args}))
    (ast/let-compound name
                      (parse-form compound-expr)
                      (body-form body))))

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

(defn- parse-compound [args]
  (let [[spec & rest-args] args
        [inputs map-output] (parse-compound-spec spec)
        [output body] (if (some? map-output)
                        [map-output rest-args]
                        [(first rest-args) (rest rest-args)])]
    (when-not (symbol? output)
      (parse-error "compound output must be a symbol" {:output output}))
    (ast/compound {:inputs inputs
                   :output output}
                  (body-form body))))

(defn- parse-app-out [args]
  (let [[op arg-forms out & extra] args]
    (when (seq extra)
      (parse-error "app-> expects exactly operator, args vector, and output"
                   {:args args}))
    (when-not (vector? arg-forms)
      (parse-error "app-> args must be a vector" {:args arg-forms}))
    (ast/app-> (parse-form op)
               (mapv parse-form arg-forms)
               (parse-form out))))

(defn- parse-application [forms]
  (let [op (first forms)
        args (rest forms)]
    (apply ast/app (parse-form op) (map parse-form args))))

(defn parse-form
  "Convert one reader form into compiler-2 AST data."
  [form]
  (cond
    (seq? form)
    (case (first form)
      do (body-form (rest form))
      let-cell (parse-let-cell (rest form))
      let-compound (parse-let-compound (rest form))
      compound (parse-compound (rest form))
      app-> (parse-app-out (rest form))
      (parse-application form))

    (symbol? form) (ast/sym form)

    :else (ast/lit form)))

(defn parse-string
  "Parse one source string into compiler-2 AST data."
  [source]
  (parse-form (read-form source)))

(def parse parse-string)
