; eClojure
;   Copyright (c) Daniel Rune Jensen, Thomas Stig Jacobsen and
;   Søren Kejser Jensen. All rights reserved.
;   The use and distribution terms for this software are covered by the Eclipse
;   Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which
;   can be found in the file epl-v10.html at the root of this distribution. By
;   using this software in any fashion, you are agreeing to be bound by the
;   terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns eclojure.transaction-control
  (:use clojure.test clojure.eclojure eclojure.eclojure-test-helper))

(use-fixtures :once dosync-fixture)

; Retry
(deftest retry-by-gets
  (let [retry-ref (ref 0)]
    ; Start unlock thread
    (future
      (Thread/sleep 1000) ; Test sync with time, deadlocks if missed
      (dosync
        (alter retry-ref inc)))
    ; Blocks main thread
    (dosync
      (when (== 0 @retry-ref)
        (retry)))))

(deftest retry-by-ref
  (let [retry-ref (ref 0)]
    ; Start unlock thread
    (future
      (Thread/sleep 1000) ; Test sync with time, deadlocks if missed
      (dosync
        (alter retry-ref inc)))
    ; Blocks main thread
    (dosync
      (when (== 0 @retry-ref)
        (retry retry-ref)))))

(deftest retry-by-ref-and-func
  (let [retry-ref (ref 0)]
    ; Start unlock thread
    (future
      (dosync
        (alter retry-ref inc))
      (Thread/sleep 1000) ; Test sync with time, deadlocks if missed
      (dosync
        (alter retry-ref inc)))
    ; Blocks main thread
    (dosync
      (when (not (== 2 @retry-ref))
        (retry retry-ref #(== @retry-ref 2)))
      (is (== @retry-ref 2)))))

; Retry-All
(deftest retry-all-by-gets
  (let [retry-ref-one (ref 0) retry-ref-two (ref 0)]
    ; Start unlock thread
    (future
      ; First ref should not unlock
      (dosync
        (alter retry-ref-one inc))
      (Thread/sleep 1000) ; Test sync with time, deadlocks if missed
      (is (not (== @retry-ref-one 2))) ; Checks if retry is awake
      (dosync
        (alter retry-ref-two inc)))
    ; Blocks main thread
    (dosync
      (deref retry-ref-one)
      (when (== 0 @retry-ref-two)
        (retry))
      (alter retry-ref-one inc))))

(deftest retry-all-by-refs
  (let [retry-ref-one (ref 0) retry-ref-two (ref 0)]
    ; Start unlock thread
    (future
      ; First ref should not unlock
      (dosync
        (alter retry-ref-one inc))
      (Thread/sleep 1000) ; Test sync with time, deadlocks if missed
      (is (not (== @retry-ref-one 2))) ; Checks if retry is awake
      (dosync
        (alter retry-ref-two inc)))
    ; Blocks main thread
    (dosync
      (deref retry-ref-one)
      (when (== 0 @retry-ref-two)
        (retry [retry-ref-one retry-ref-two]))
      (alter retry-ref-one inc))))

(deftest retry-all-by-refs-and-func
  (let [retry-ref-one (ref 0) retry-ref-two (ref 0)]
    ; Start unlock thread
    (future
      ; First ref should not unlock
      (dosync
        (alter retry-ref-one inc))
      (Thread/sleep 1000) ; Test sync with time, deadlocks if missed
      (is (not (== @retry-ref-one 2))) ; Checks if retry-all is awake
      ; Second ref should not unlock due to func
      (dosync
        (alter retry-ref-two inc))
      (Thread/sleep 1000) ; Test sync with time, deadlocks if missed
      (is (not (== @retry-ref-one 2))) ; Checks if retry-all is awake
      (dosync
        (alter retry-ref-one inc)
        (alter retry-ref-two inc)))
    ; Blocks main thread
    (dosync
      (deref retry-ref-one)
      (when (== 0 @retry-ref-two)
        (retry [retry-ref-one retry-ref-two] #(== @retry-ref-one 2)))
      (alter retry-ref-one inc))))

; Or-Else
(deftest or-else-test
  (let [or-else-ref (ref 0)]
    (dosync
      (or-else
        #(retry or-else-ref)
        #(ref-set or-else-ref 5)
        #(ref-set or-else-ref 7)))
    (is (== @or-else-ref 5))))

(deftest or-else-retry-test
  (let [or-else-ref (ref 0)]
    (is (assert-retry
          (dosync
            (or-else
              #(alter eclojure-alter-ref inc)
              #(ref-set or-else-ref 5)
              #(ref-set or-else-ref 7)))))))

(deftest or-else-all-test
  (let [or-else-ref (ref 0)]
    (dosync
      (or-else-all
        #(alter eclojure-alter-ref inc)
        #(ref-set or-else-ref 5)
        #(ref-set or-else-ref 7)))
    (is (== @or-else-ref 5))))

; Terminate
(deftest terminate-test
  (let [terminate-ref (ref 0)]
    (dosync
      (terminate)
      (ref-set terminate-ref 7))
    (dosync
      (ref-set terminate-ref 5))
    (is (== @terminate-ref 5))))
