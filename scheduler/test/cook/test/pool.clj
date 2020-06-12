(ns cook.test.pool
  (:require [clojure.test :refer :all]
            [cook.config :as config]
            [cook.pool :as pool]
            [clojure.tools.logging :as log])
  (:import (clojure.lang ExceptionInfo)))

(deftest test-guard-invalid-default-pool
  (with-redefs [pool/all-pools (constantly [{:pool/name "foo"}])
                config/default-pool (constantly "foo")]
    (is (nil? (pool/guard-invalid-default-pool nil))))
  (with-redefs [pool/all-pools (constantly [])
                config/default-pool (constantly nil)]
    (is (nil? (pool/guard-invalid-default-pool nil))))
  (with-redefs [pool/all-pools (constantly [{}])
                config/default-pool (constantly nil)]
    (is (thrown-with-msg? ExceptionInfo
                          #"There are pools in the database, but no default pool is configured"
                          (pool/guard-invalid-default-pool nil))))
  (with-redefs [pool/all-pools (constantly [])
                config/default-pool (constantly "foo")]
    (is (thrown-with-msg? ExceptionInfo
                          #"There is no pool in the database matching the configured default pool"
                          (pool/guard-invalid-default-pool nil))))
  (with-redefs [pool/all-pools (constantly [{:pool/name "bar"}])
                config/default-pool (constantly "foo")]
    (is (thrown-with-msg? ExceptionInfo
                          #"There is no pool in the database matching the configured default pool"
                          (pool/guard-invalid-default-pool nil)))))

(deftest test-guard-invalid-default-gpu-model
  (testing "test"
    (is (nil? (pool/guard-invalid-default-gpu-model))))
  (testing "valid default model"
    (with-redefs [config/valid-gpu-models (constantly [{:pool-regex    "test-pool"
                                                        :valid-models  #{"valid-gpu-model"}
                                                        :default-model "valid-gpu-model"}])]
      (is (nil? (pool/guard-invalid-default-gpu-model)))))
  (testing "no valid models"
    (with-redefs [config/valid-gpu-models (constantly [{:pool-regex    "test-pool"}])]
      (is (thrown-with-msg?
            ExceptionInfo
            #"Valid GPU models for pool-regex test-pool is not defined"
            (pool/guard-invalid-default-gpu-model)))))
  (testing "no default model"
    (with-redefs [config/valid-gpu-models (constantly [{:pool-regex    "test-pool"
                                                        :valid-models  #{"valid-gpu-model"}}])]
      (is (thrown-with-msg?
            ExceptionInfo
            #"Default GPU model for pool-regex test-pool is not defined"
            (pool/guard-invalid-default-gpu-model)))))
  (testing "invalid default model"
    (with-redefs [config/valid-gpu-models (constantly [{:pool-regex    "test-pool"
                                                        :valid-models  #{"valid-gpu-model"}
                                                        :default-model "invalid-gpu-model"}])]
      (is (thrown-with-msg?
            ExceptionInfo
            #"Default GPU model for pool-regex test-pool is not listed as a valid GPU model"
            (pool/guard-invalid-default-gpu-model))))))