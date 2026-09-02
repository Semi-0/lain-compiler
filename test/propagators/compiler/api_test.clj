(ns propagators.compiler.api-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.compiler.api :as api]
            [propagators.compiler.compiler.basis :as basis]))

(deftest compile-form-exposes-stable-result
  (let [result (api/compile-form 42 (basis/default-env) {})]
    (is (map? (:network result)))
    (is (some? (:environment result)))
    (is (vector? (:installed-propagator-ids result)))
    (is (vector? (:application-metadata result)))
    (is (= [] (:diagnostics result)))))

(deftest compiler-api-rejects-invalid-input
  (testing "nil forms are explicit errors"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must not be nil"
                          (api/compile-form nil (basis/default-env) {}))))
  (testing "non-string source is an explicit error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be a string"
                          (api/compile-source 42 (basis/default-env) {}))))
  (testing "invalid options are explicit errors"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"options must be a map"
                          (api/compile-form 42 (basis/default-env) [])))))
