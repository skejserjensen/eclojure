;   Copyright (c) Daniel Rune Jensen, Thomas Stig Jacobsen and
;   Søren Kejser Jensen. All rights reserved.
;   The use and distribution terms for this software are covered by the Eclipse
;   Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which
;   can be found in the file epl-v10.html at the root of this distribution. By
;   using this software in any fashion, you are agreeing to be bound by the
;   terms of this license.
;   You must not remove this notice, or any other, from this software.

(use 'criterium.core)

; Benchmark One - Empty: Execution of an empty dosync block.
(println "[Running Benchmark One] - Empty")
(bench
  (dosync))
(println)


; Benchmark Two - Deref Execution of a dosync block containing a deref.
(println "[Running Benchmark Two] - Deref")
(def deref-ref (ref 0))
(bench
  (dosync
    (deref deref-ref)))
(println)


; Benchmark Three - Database: Execution of the database example
; from Section 3.2 with  database-insert returning a random key.
(println "[Running Benchmark Three] - Database")
(def keys-ref (ref []))
(def rows-ref (ref (vec (range 100))))

(defn database-insert [row]
  (rand-int 10))

(bench
  (dosync
    (let [row (first (deref rows-ref))
          next-key (database-insert row)]
      (alter keys-ref conj next-key)
      (alter rows-ref rest))))
(println)
