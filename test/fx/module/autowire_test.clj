(ns fx.module.autowire-test
  (:require
   [clojure.test :refer :all]
   [duct.core :as duct]
   [integrant.core :as ig]
   [fx.module.autowire :as sut]
   [malli.core :as m]
   [malli.instrument :as mi]
   [fx.module.stub-functions :as sf]))


(duct/load-hierarchy)
(mi/instrument!)


(deftest find-project-namespaces-test
  (let [result-spec [:sequential {:min 1} :symbol]]
    (testing "output result"
      (is (m/validate result-spec (sut/find-project-namespaces 'fx.module))
          "result should be a sequence of symbols"))

    (testing "input parameter"
      (is (m/validate result-spec (sut/find-project-namespaces "fx.module"))
          "input can be a string as well")

      (testing "keywords not supported as input"
        (is (thrown? Exception
                     (sut/find-project-namespaces :fx.module)))

        (testing "wouldn't throw w/o malli instrumentation, but return nothing"
          (mi/unstrument!)
          (is (empty? (sut/find-project-namespaces :fx.module)))
          (mi/instrument!)))

      (is (m/validate result-spec (sut/find-project-namespaces))
          "should work w/o any parameters")

      (is (not= (sut/find-project-namespaces nil)
                (sut/find-project-namespaces))
          "passing nil isn't the same as calling w/o argument"))))


(deftest collect-autowired-test
  (testing "components w/o metadata should be skipped"
    (is (empty?
         (sut/collect-autowired "some-ns" {} 'my-component {:some "value"}))))

  (testing "component should be added to config map"
    (let [component-val (with-meta {:some "value"} {sut/AUTOWIRED-KEY true})
          result        (sut/collect-autowired "some-ns" {} 'my-component component-val)]
      (is (contains? result :some-ns/my-component))
      (is (identical? (get result :some-ns/my-component)
                      component-val)))))


(deftest find-components-test
  (testing "returns all autowired items as map of names to vars"
    (let [result (sut/find-components '(fx.module.stub-functions))]
      (is (contains? result :fx.module.stub-functions/constant-value))
      (is (= (get result :fx.module.stub-functions/constant-value)
             (var fx.module.stub-functions/constant-value))))))


(defn simple-func [num]
  (+ num num))


(def simple-val
  [1 2 3])


(deftest get-comp-deps-test
  (testing "returns empty collection if no metadata set on function"
    (is (empty? (sut/get-comp-deps (meta #'simple-func))))
    (is (empty? (sut/get-comp-deps (meta #'simple-val))))
    (is (empty? (sut/get-comp-deps (meta #'sf/health-check)))))

  (testing "returns all autowired items as map of names to vars"
    (let [result (sut/get-comp-deps (meta #'sf/status))]
      (is (vector? result))
      (is (= 1 (count result)))
      (is (= :fx.module.stub-functions/db-connection (first result))))))


(deftest prep-component-test
  (let [result (sut/prep-component {} :fx.module.stub-functions/status #'sf/status)]
    (is (contains? result :fx.module.stub-functions/status))
    (is (ig/ref? (get-in result [:fx.module.stub-functions/status :db-connection])))

    (testing "integrant methods should be in place"
      (testing "init key"
        (let [init-method   (get-method ig/init-key :fx.module.stub-functions/status)
              method-result (init-method nil {:db-connection (fn [] :connected)})]
          (is (= {:connection :connected
                  :status     :ok}
                 (method-result)))))

      (testing "halt key"
        (let [_             (sut/prep-component {} :fx.module.stub-functions/close-connection #'sf/close-connection)
              halt-method   (get-method ig/halt-key! :fx.module.stub-functions/db-connection)
              method-result (halt-method nil nil)]
          (is (= :closed method-result)))))))



;; =============================================================================
;; System tests
;; =============================================================================

(def valid-config
  {:duct.profile/base  {:duct.core/project-ns 'test}
   :fx.module/autowire {:root 'fx.module.stub-functions}})


(deftest autowire-config-prep
  (let [config (duct/prep-config valid-config)
        system (ig/init config)]

    (testing "basic component"
      (is (some? (:fx.module.stub-functions/health-check system)))
      (is (= {:status :ok}
             ((:fx.module.stub-functions/health-check system) {} {}))))

    (testing "parent - child component"
      (is (some? (get system :fx.module.stub-functions/status)))
      (is (= {:status     :ok
              :connection {:connected :ok}}
             ((get system :fx.module.stub-functions/status)))))

    (ig/halt! system)))


(deftest autowire-di-test
  (let [config (duct/prep-config valid-config)
        system (ig/init config)]

    (testing "dependency injection configured properly"
      (let [status-handler-conf (get config :fx.module.stub-functions/status)
            db-connection-conf  (:fx.module.stub-functions/db-connection config)]
        (is (some? (:db-connection status-handler-conf)))
        (is (ig/ref? (:db-connection status-handler-conf)))

        (is (some? db-connection-conf))

        (testing "components return correct results"
          (let [status-handler (get system :fx.module.stub-functions/status)]
            (is (= {:status     :ok
                    :connection {:connected :ok}}
                   (status-handler)))))))

    (ig/halt! system)))


(deftest autowire-parent-components
  (let [config (duct/prep-config valid-config)
        system (ig/init config)]

    (testing "single parent component"
      (is (= (->> [:fx.module.stub-functions/test-1 :fx.module.stub-functions/parent-test-component]
                  (get config)
                  :component)
             :test-1)))

    (testing "multi parent component"
      (is (= (->> [:fx.module.stub-functions/test-2 :fx.module.stub-functions/multi-parent-test-component]
                  (get config)
                  :component)
             [:test-1 :test-2]))

      (is (= (->> [:fx.module.stub-functions/test-2 :fx.module.stub-functions/multi-parent-test-component]
                  (get config)
                  :component)
             [:test-1 :test-2])))

    (ig/halt! system)))
