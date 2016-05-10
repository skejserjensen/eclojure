;; An STM Based Implementation Of The Santa Claus Problem Written in Clojure
;; =========================================================================
;; https://github.com/jonase/eastwood
;; https://github.com/jonase/kibit
;; https://github.com/dakrone/lein-bikeshed
;; =========================================================================
;; https://github.com/bbatsov/clojure-style-guide

;; Forward Declarations
(declare worker)
(declare santa-wake)

;; Helper Functions
(defn sleep-random-interval [n]
  (Thread/sleep (* 1000 (rand-int n))))

(defn elem? [elem collection]
  (cond
    (empty? collection) false
    (== elem (first collection)) true
    :else (elem? elem (rest collection))))

(defn enough-reindeers? [reindeer-queue-ref]
  (== 9 (count (deref reindeer-queue-ref))))

(defn enough-elfs? [elf-queue-ref]
  (== 3 (count (deref elf-queue-ref))))

(defn start-workers [queue-ref max-sleep max-queue sleep-sem
                     worker-sem worker-barrier number]
  (add-watch queue-ref :key (fn [_key _ref old-state new-state]
                              (when (== (count new-state) max-queue)
                                (santa-wake sleep-sem))))
  (doseq [wid (range 0 number)]
    (.start (Thread.
              (fn [] (worker wid queue-ref max-sleep max-queue sleep-sem
                             worker-sem worker-barrier))))))

;; Worker
(defn goto-santa [wid queue-ref]
    (if-not (elem? wid (deref queue-ref))
      (alter queue-ref conj wid)
      (throw (java.lang.IllegalStateException.))))

(defn worker [wid queue-ref max-sleep max-queue sleep-sem
              worker-sem worker-barrier]
  (sleep-random-interval max-sleep)
  (try
    (dosync
      (let [queue-length (count (deref queue-ref))]
        (if (< queue-length max-queue)
          (goto-santa wid queue-ref)
          (throw (java.lang.IllegalStateException.)))))
    (catch java.lang.IllegalStateException ise
      (.acquire worker-sem 1)
      (.await worker-barrier)))
  (recur wid queue-ref max-sleep max-queue sleep-sem worker-sem worker-barrier))

;; Santa
(defn santa-sleep [sleep-sem]
  (.acquire sleep-sem))

(defn santa-wake [sleep-sem]
  (.release sleep-sem))

(defn santa-work [queue-ref phrase semaphore permits]
  (println "Santa: ho ho" phrase (deref queue-ref))
  (dosync
    (alter queue-ref empty))
  (.release semaphore permits))

(defn santa [reindeer-queue-ref elf-queue-ref sleep-sem reindeer-sem elf-sem]
  (dosync
    (santa-sleep sleep-sem))
  (cond
    (enough-reindeers? reindeer-queue-ref)
      (santa-work reindeer-queue-ref "delivering presents with reindeers:"
                  reindeer-sem (count (deref reindeer-queue-ref)))
    (enough-elfs? elf-queue-ref)
      (santa-work elf-queue-ref "solving problems for elfs:"
                  elf-sem (count (deref elf-queue-ref)))
    :else
      (throw (IllegalStateException. "wrong number of workers at the door")))
  (recur reindeer-queue-ref elf-queue-ref sleep-sem reindeer-sem elf-sem))

;; Main
(def reindeer-queue-ref (ref []))
(def elf-queue-ref (ref []))

(def sleep-sem (java.util.concurrent.Semaphore. 0))
(def reindeer-sem (java.util.concurrent.Semaphore. 9))
(def elf-sem (java.util.concurrent.Semaphore. 3))

(def reindeer-barrier (java.util.concurrent.CyclicBarrier. 9))
(def elf-barrier (java.util.concurrent.CyclicBarrier. 3))

(start-workers reindeer-queue-ref 15 9 sleep-sem reindeer-sem reindeer-barrier 9)
(start-workers elf-queue-ref 10 3 sleep-sem elf-sem elf-barrier 30)
(santa reindeer-queue-ref elf-queue-ref sleep-sem reindeer-sem elf-sem)
(shutdown-agents)
