;; An STM Based Implementation Of The Santa Claus Problem Written in Clojure
;; =========================================================================
;; https://github.com/jonase/eastwood
;; https://github.com/jonase/kibit
;; https://github.com/dakrone/lein-bikeshed
;; =========================================================================
;; https://github.com/bbatsov/clojure-style-guide
(use 'clojure.eclojure)

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

(defn start-workers [queue-ref max-sleep max-queue sleep-ref number]
  (doseq [wid (range 0 number)]
    (.start (Thread.
              (fn [] (worker wid queue-ref max-sleep max-queue sleep-ref))))))

;; Worker
(defn goto-santa [wid queue-ref]
    (if-not (elem? wid (deref queue-ref))
      (alter queue-ref conj wid)
      (retry [queue-ref] (fn [] (not (elem? wid (deref queue-ref)))))))

(defn worker [wid queue-ref max-sleep max-queue sleep-ref]
  (sleep-random-interval max-sleep)
  (dosync
    (let [queue-length (count (deref queue-ref))]
      (if (< queue-length max-queue) (goto-santa wid queue-ref) (retry))
      (when (== (inc queue-length) max-queue)
        (santa-wake sleep-ref))))
  (recur wid queue-ref max-sleep max-queue sleep-ref))

;; Santa
(defn santa-sleep [sleep-ref]
  (if (deref sleep-ref) (retry) (alter sleep-ref not)))

(defn santa-wake [sleep-ref]
  (if (deref sleep-ref) (alter sleep-ref not) (retry)))

(defn santa-work [queue-ref phrase]
  (println "Santa: ho ho" phrase (deref queue-ref))
  (dosync
    (alter queue-ref empty)))

(defn santa [reindeer-queue-ref elf-queue-ref sleep-ref]
  (dosync
    (santa-sleep sleep-ref))
  (cond
    (enough-reindeers? reindeer-queue-ref)
      (santa-work reindeer-queue-ref "delivering presents with reindeers:")
    (enough-elfs? elf-queue-ref)
      (santa-work elf-queue-ref "solving problems for elfs:")
    :else
      (throw (IllegalStateException. "wrong number of workers at the door")))
  (recur reindeer-queue-ref elf-queue-ref sleep-ref))

;; Main
(def sleep-ref (ref true))
(def reindeer-queue-ref (ref []))
(def elf-queue-ref (ref []))

(start-workers reindeer-queue-ref 15 9 sleep-ref 9)
(start-workers elf-queue-ref 10 3 sleep-ref 30)
(santa reindeer-queue-ref elf-queue-ref sleep-ref)
(shutdown-agents)
