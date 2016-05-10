; eClojure
;   Copyright (c) Daniel Rune Jensen, Thomas Stig Jacobsen and
;   Søren Kejser Jensen. All rights reserved.
;   The use and distribution terms for this software are covered by the Eclipse
;   Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which
;   can be found in the file epl-v10.html at the root of this distribution. By
;   using this software in any fashion, you are agreeing to be bound by the
;   terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns eclojure.eclojure-test-helper
  (:use clojure.test clojure.eclojure))

;; Small fixture that just exposes a ref
(def eclojure-test-ref (ref 0))
(defn test-ref-fixture
  "Fixture for running a test with a single ref rest each run"
  [func]
  (dosync
    (ref-set eclojure-test-ref 0))
    (func))

;; Refs needed for dosync-fixture, global vars is simpler then local vars
(def eclojure-deref-ref (ref 0))
(def eclojure-commute-ref (ref 0))
(def eclojure-alter-ref (ref 0))
(def eclojure-ref-set-ref (ref 0))
(def eclojure-ensure-ref (ref 0))

(defn dosync-fixture
  "Fixture for running a test with a set of refs changed"
  [func]
  (let [lockbject (Object.)
        thread (Thread.
                #(dosync
                   (deref eclojure-deref-ref)
                   (commute eclojure-commute-ref identity)
                   (alter eclojure-alter-ref identity)
                   (ref-set eclojure-alter-ref 0)
                   (ensure eclojure-ensure-ref)
                   ; Sub thread waits for main thread to sleep
                   (locking lockbject
                     (.notify lockbject)
                     (.wait lockbject))))]
              ; Main thread waits for sub thread to alter
              (locking lockbject
                (.start thread)
                (.wait lockbject))
      ; Main thread executes the body passed to it
      (func)
      (locking lockbject
       (.notify lockbject))))

;; Var needed for the retry fixture, using a var prevents retry
;; on parallel test execution as they are thread local
(def retry-ref (ref true))
(def retry-lockbject (Object.))

(defn alter-retry-ref []
  (locking retry-lockbject
    (.notify retry-lockbject)))

(defn retry-fixture
  "Fixture for running a test with a set of refs blocked by retry"
  [func]
  (let [thread (Thread.
                 #(dosync
                    (locking retry-lockbject
                      (.notify retry-lockbject)
                      (.wait retry-lockbject))
                    (alter retry-ref not)))]
    ; Main thread waits for sub thread to alter
    (locking retry-lockbject
      (.start thread)
      (.wait retry-lockbject))
    ; Main thread executes the body passed to it
    (func)
    (dosync
      (ref-set retry-ref true))))

;; Asserts for checking retrying in a dosync bloc k
(defmacro assert-retry
  "Asserts if the function body retries if executed in a dosync block"
  [& body]
  `(with-local-vars [retried# true]
    (try ; Breaks execution through exception on retry
      (dosync
        ; retried# should be "false" on a successful run for the "is" check
        (var-set retried# (not @retried#))
        (when @retried#
          (throw (Exception.)))
        ~@body)
      ; The exeception is used to exit dosync, but other exceptions should not
      (catch Exception e#
        (when-not @retried#
          (throw e#))))
    (is @retried# "The dosync block did not retry at least once")))

(defmacro assert-not-retry
  "Asserts if the function body does not retry if executed in a dosync block"
  [& body]
  `(with-local-vars [retried# true]
    (try ; Breaks execution through exception on retry
      (dosync
        ; retried# should be "false" on a successful run for the "is" check
        (var-set retried# (not @retried#))
        (when @retried#
          (throw (Exception.)))
        ~@body)
      ; The exception is used to exit dosync, but other exceptions should not
      (catch Exception e#
        (when-not @retried#
          (throw e#))))
    (is (not @retried#) "The dosync block retried at least once")))
