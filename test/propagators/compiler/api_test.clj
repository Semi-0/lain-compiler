(ns propagators.compiler.api-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.compiler.api :as api]
            [propagators.compiler.compiler.basis :as basis]
            [propagators.compiler.model.env :as env]
            [propagators.infra.ids :as ids]
            [propagators.infra.network :as net]))

(defn- live-environment []
  (let [environment-id (ids/new-node-id)
        declared (env/declare-root net/empty-net
                                   environment-id
                                   (basis/default-bindings))]
    [environment-id
     {:net (:net declared)
      :environment-props (:props declared)}]))

(deftest compile-form-exposes-stable-result
  (let [[environment options] (live-environment)
        result (api/compile-form 42 environment options)]
    (is (map? (:network result)))
    (is (ids/node-id? (:environment result)))
    (is (vector? (:installed-propagator-ids result)))
    (is (vector? (:application-metadata result)))
    (is (= [] (:diagnostics result)))))

(deftest compiler-api-rejects-invalid-input
  (let [[environment _options] (live-environment)]
    (testing "nil forms are explicit errors"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"must not be nil"
                            (api/compile-form nil environment {}))))
    (testing "non-string source is an explicit error"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"must be a string"
                            (api/compile-source 42 environment {}))))
    (testing "invalid options are explicit errors"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"options must be a map"
                            (api/compile-form 42 environment []))))))
