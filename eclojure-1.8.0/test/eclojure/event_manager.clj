; eClojure
;   Copyright (c) Daniel Rune Jensen, Thomas Stig Jacobsen and
;   Søren Kejser Jensen. All rights reserved.
;   The use and distribution terms for this software are covered by the Eclipse
;   Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which
;   can be found in the file epl-v10.html at the root of this distribution. By
;   using this software in any fashion, you are agreeing to be bound by the
;   terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns eclojure.event-manager
  (:use clojure.test clojure.eclojure eclojure.eclojure-test-helper))

(use-fixtures :once dosync-fixture)

; Events exception test
(deftest stm-listen-exception
  (is (thrown? java.lang.Exception
               (stm-listen :test #(identity 0)))))

(deftest stm-notify-exception
  (is (thrown? java.lang.Exception
               (stm-notify :test))))

(deftest listen-exception
  (is (thrown? java.lang.Exception
               (dosync
                 (listen :test #(identity 0))))))

(deftest notify-exception
  (is (thrown? java.lang.Exception
               (dosync
                 (notify :test)))))

(deftest on-commit-retry-exception
  (is (thrown? java.lang.RuntimeException
               (dosync
                 (on-commit
                   (retry eclojure-alter-ref))))))

(deftest on-abort-retry-exception
  (is (thrown? java.lang.RuntimeException
               (dosync
                 (on-abort
                   (retry eclojure-alter-ref))
                 (alter eclojure-alter-ref inc)))))

; Basic STM events
(deftest stm-listen-multiple
  (let [event-ref (ref 0)]
    (dosync
      (stm-listen :test #(alter event-ref inc))
      (stm-notify :test)
      (stm-notify :test))
    (is (== @event-ref 2))))

(deftest stm-listen-once-multiple
  (let [event-ref (ref 0)]
    (dosync
      (stm-listen-once :test #(alter event-ref inc))
      (stm-notify :test)
      (stm-notify :test))
    (is (== @event-ref 1))))

; Macro STM events
(deftest on-abort-event
  ; The transaction aborts so a ref cannot be changed
  (with-local-vars [event-var 0]
    (is (assert-retry
          (dosync
            (on-abort
              (var-set event-var 5))
            (alter eclojure-alter-ref inc))))
    (is (== @event-var 5))))

(deftest on-commit-context
  (let [event-ref (ref 0)]
    (dosync
      (on-commit
        (ref-set ((context) event-ref) 5))
      ; The context contain "vals" any ref we wrote to
    (alter event-ref identity))
    (is (== @event-ref 5))))

(deftest after-commit-event
  ; The transaction aborts so a ref cannot be changed
  (with-local-vars [event-var 0]
    (dosync
      (after-commit
        (var-set event-var 5)))
    (is (== @event-var 5))))

(deftest dismiss-all-events-on-restart
  (with-local-vars [event-var 0]
    (dosync
      ; Sets only the event and restarts on first run to prevent infinite loops
      (when (== @event-var 0)
        (on-commit
          (var-set event-var 5))
        (var-set event-var 1)
        (alter eclojure-alter-ref inc)))
    ; If the event was removed one restart then event-var should be one
    (is (== @event-var 1))))


; Normal events
(deftest listen-dismiss
  (with-local-vars [event-var 0 dismiss-key (listen :test #(var-set event-var (inc @event-var)))]
    (notify :test)
    (dismiss :test @dismiss-key :all)
    (notify :test)
    (is (== @event-var 1))))

(deftest listen-global
  (with-local-vars [event-var 0 dismiss-key (listen-with-params :test false false #(var-set event-var (inc @event-var)))]
    (notify :test)
    (dismiss :test @dismiss-key :local)
    (notify :test)
    (dismiss :test @dismiss-key :all)
    (is (== @event-var 2))))

(deftest listen-once
  (with-local-vars [event-var 0 dismiss-key (listen-with-params :test true true #(var-set event-var (inc @event-var)))]
    (notify :test)
    (notify :test)
    (dismiss :test @dismiss-key :all)
    (is (== @event-var 1))))

(deftest listen-contex
  (let [dismiss-key (listen :test #(is (== 5 (context))))]
    (notify :test 5)
    (dismiss :test dismiss-key :all)))

(deftest listen-global-context-threads
    (let [dismiss-key (listen-with-params :test false false #(is (== 7 (context))))]
      (future (notify :test 7))
      (dismiss :test dismiss-key :all)))

; Alter-Run and Commute-Run
(deftest alter-method-execute
  (let [array-ref (ref (java.util.ArrayList. [1 2 3 4 5]))]
    (dosync
      (alter array-ref #(.add % 6)))
    (is @array-ref)))

(deftest commute-method-execute
  (let [array-ref (ref (java.util.ArrayList. [1 2 3 4 5]))]
    (dosync
      (commute array-ref #(.add % 6)))
    (is @array-ref)))

(deftest alter-run-method-execute
  (let [array-ref (ref (java.util.ArrayList. [1 2 3 4 5]))]
    (dosync
      (alter-run array-ref .add 6))
    (is (instance? java.util.ArrayList @array-ref))
    (is (== 6 (.size @array-ref)))))

(deftest commute-run-method-execute
  (is (thrown? java.lang.Exception
    (let [array-ref (ref (java.util.ArrayList. [1 2 3 4 5]))]
    (dosync
      (commute-run array-ref .add 6))))))

(deftest commute-on-commit-run-method-execute
  (let [array-ref (ref (java.util.ArrayList. [1 2 3 4 5]))]
    (dosync
      (on-commit
        (commute-run array-ref .add 6)))
    (is (instance? java.util.ArrayList @array-ref))
    (is (== 6 (.size @array-ref)))))

; Java-Ref
(deftest java-ref-construct-test
    (is (instance? clojure.lang.JavaRef (java-ref 10))))

(deftest java-ref-history-test
    (is (== 1 (.getMaxHistory (java-ref 10)))))

(deftest java-ref-deref-test
  (let [java-ref-ref (java-ref 10)]
    (dosync
      (deref java-ref-ref))
    (is (nil? @java-ref-ref))))

(deftest java-ref-set-min-history-test
  (is (thrown? java.lang.UnsupportedOperationException
               (.setMinHistory (java-ref 10) 5))))

(deftest java-ref-set-min-history-test
  (is (thrown? java.lang.UnsupportedOperationException
               (.setMaxHistory (java-ref 10) 5))))

(deftest java-ref-ensure-test
  (dosync
    (ensure (java-ref 10))
    (is true)))

(deftest alter-method-execute-java-ref
  (let [array-ref (java-ref (java.util.ArrayList. [1 2 3 4 5]))]
    (dosync
      (alter array-ref #(.add % 6)))
    (is @array-ref)))

(deftest commute-method-execute-java-ref
  (let [array-ref (java-ref (java.util.ArrayList. [1 2 3 4 5]))]
    (dosync
      (commute array-ref #(.add % 6)))
    (is @array-ref)))

(deftest alter-run-method-execute-java-ref
  (let [array-ref (java-ref (java.util.ArrayList. [1 2 3 4 5]))]
    (dosync
      (alter-run array-ref .add 6))
    (is (instance? java.util.ArrayList @array-ref))
    (is (== 6 (.size @array-ref)))))

(deftest commute-run-method-execute-java-ref
  (is (thrown? java.lang.Exception
    (let [array-ref (java-ref (java.util.ArrayList. [1 2 3 4 5]))]
    (dosync
      (commute-run array-ref .add 6))))))

(deftest commute-on-commit-run-method-execute-java-ref
  (let [array-ref (java-ref (java.util.ArrayList. [1 2 3 4 5]))]
    (dosync
      (on-commit
        (commute-run array-ref .add 6)))
    (is (instance? java.util.ArrayList @array-ref))
    (is (== 6 (.size @array-ref)))))

; Locks-Refs
(deftest lock-refs-symbol-test
  (let [test-ref (ref 0)]
    (dosync
      (lock-refs alter []
                 (on-commit
                   (is (not (empty? (context)))))))))

(deftest lock-refs-symbol-alter-test
  (let [test-ref (ref 0)]
    (dosync
      (lock-refs alter []
                 (on-commit
                   (is (not (empty? (context))))
                   (alter test-ref inc))))))

(defn evil-ref-updating-param-func [test-ref]
  (alter test-ref inc))
(deftest lock-refs-func-param-test
  (let [test-ref (ref 0)]
    (dosync
      (lock-refs alter []
                 (on-commit
                   (is (not (empty? (context))))
                   (evil-ref-updating-param-func test-ref))))))

(def global-test-ref (ref 0))
(defn evil-ref-updating-global-func []
  (alter global-test-ref inc))
(deftest lock-refs-func-global-test
  (dosync
    (lock-refs alter []
               (on-commit
                 (is (empty? (context)))
                 (evil-ref-updating-global-func)))))

(deftest lock-refs-func-global-elem-exclusive-test
  (dosync
    (lock-refs alter eclojure.event-manager/global-test-ref)
    (on-commit
      (is (not (empty? (context))))
      (evil-ref-updating-global-func))))

(deftest lock-refs-func-global-elem-inclusive-test
  (dosync
    (lock-refs alter eclojure.event-manager/global-test-ref
    (on-commit
      (is (not (empty? (context))))
      (evil-ref-updating-global-func)))))

(deftest lock-refs-func-global-vector-exclusive-test
  (dosync
    (lock-refs alter [eclojure.event-manager/global-test-ref])
    (on-commit
      (is (not (empty? (context))))
      (evil-ref-updating-global-func))))

(deftest lock-refs-func-global-vector-inclusive-test
  (dosync
    (lock-refs alter [eclojure.event-manager/global-test-ref]
    (on-commit
      (is (not (empty? (context))))
      (evil-ref-updating-global-func)))))
