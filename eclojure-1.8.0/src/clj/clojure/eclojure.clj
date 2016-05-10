; eClojure
;   Copyright (c) Daniel Rune Jensen, Thomas Stig Jacobsen and
;   Søren Kejser Jensen. All rights reserved.
;   The use and distribution terms for this software are covered by the Eclipse
;   Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which
;   can be found in the file epl-v10.html at the root of this distribution. By
;   using this software in any fashion, you are agreeing to be bound by the
;   terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.eclojure
  (:import clojure.lang.EventManager)
  (:import clojure.lang.LockingTransaction)
  (:import clojure.lang.RT))


;;; Environment setup
(set! *warn-on-reflection* true)


;;; Event Handling Helper Functions
(defn- ref? [sym]
  "Tests if a symbol is a reference"
  (instance? clojure.lang.IRef sym))

(defn extract-refs
  "Extract refs from a list of expressions and accumulate them in the acc list,
  namespace is determined at runtime so the function have the scope of caller"
  [acc & body]
  (distinct (reduce
    ; Reduce Function
    (fn [acc elem]
      (cond
        ; Element var containing a ref
        (and (ref? elem) (var? elem)) (conj acc @elem)
        ; Element is a raw ref
        (ref? elem) (conj acc elem)
        ; Element is a symbol that could be a ref
        (symbol? elem) (do
                         (let [elem-var (some-> elem resolve var-get)]
                           ; Symbol is a var containing a ref
                           (if (ref? elem-var)
                             (conj acc elem-var)
                             ; Symbol is a sequences so we need to check it
                             (if (or (seq? elem-var) (vector? elem-var))
                               (extract-refs acc elem-var)
                               ; Symbol was neither a ref or sequence
                               acc))))))
    ; Removes nesting to only require recursion for vars with sequences
    acc (flatten body))))


;;; Transactional Event Handling
(defn stm-listen
  "Registers a thread local transactional event identified by event-key"
  [event-key event-fn & event-args]
  (EventManager/stmListen event-key event-fn event-args false))

(defn stm-listen-once
  "Registers a single run transactional event identified by event-key"
  [event-key event-fn & event-args]
  (EventManager/stmListen event-key event-fn event-args true))

(defn stm-notify
  "Notifies the transactional events identified by the event-key keyword, and
  gives each event accesses to data given as context"
  ([event-key] (EventManager/stmNotify event-key nil))
  ([event-key context] (EventManager/stmNotify event-key context)))

(defmacro lock-refs
  "Takes the appropriate locks on all extractable refs in body of code"
  [func & body]
  ; Extracts the lexical scoped symbols from the environment
  (let [lexically-scoped-bindings (keys &env)
    locking-fn (case func
                 ensure #(ensure %)
                 commute #(commute % identity)
                 alter #(alter % identity)
                 (throw (IllegalArgumentException. "func must be ensure, commute, or alter")))]
    `(do
       (doseq [r# (extract-refs '() '~body ~@lexically-scoped-bindings)]
         (~locking-fn r#))
       ~@body)))


;;; Special Transactional Events
(defmacro on-abort
  "Registers a list of expressions to be run if the transaction aborts"
  [& body]
  `(EventManager/stmListen LockingTransaction/ONABORTKEYWORD (fn [] ~@body) nil false))

(defn on-abort-fn
  "Registers a function to be run if the transaction aborts"
  [event-fn & event-args]
  (EventManager/stmListen LockingTransaction/ONABORTKEYWORD event-fn event-args false))

(defmacro on-commit
  "Registers a list of expression to be run when the transaction commits"
  [& body]
  `(EventManager/stmListen LockingTransaction/ONCOMMITKEYWORD (fn [] ~@body) nil false))

(defn on-commit-fn
  "Registers a function to be run when the transaction commits"
  [event-fn & event-args]
  (EventManager/stmListen LockingTransaction/ONCOMMITKEYWORD  event-fn event-args false))

(defmacro after-commit
  "Registers a list of expressions to be run after the transaction commit"
  [& body]
  `(EventManager/stmListen LockingTransaction/AFTERCOMMITKEYWORD (fn [] ~@body) nil false))

(defn after-commit-fn
  "Registers a function to be run after the transaction commit"
  [event-fn & event-args]
  (EventManager/stmListen LockingTransaction/AFTERCOMMITKEYWORD event-fn event-args false))


;;; Ref Method Execution Functions
(defmacro alter-run
  "Executes a method on an object in a ref without changing the value of the ref using alter"
  [input-ref func & args]
  `(with-local-vars [return-val# nil]
     (alter ~input-ref (fn [ref-val#]
                         (var-set return-val# (~func ref-val# ~@args))
                         ref-val#))
     return-val#))

(defmacro commute-run [input-ref func & args]
  "Executes a method on an object in a ref without changing the value of the ref using commute.
  This function can only be used in the on-abort and on-commit events, a IllegalStateException
  is thrown if the function is used elsewhere due to the double execution nature of commute"
  `(with-local-vars [return-val# nil]
     (commute ~input-ref (fn [ref-val#]
                         (var-set return-val# (~func ref-val# ~@args))
                         ref-val#))
     return-val#))


;;; JavaRef
(defn ^{:private true}
  setup-reference [^clojure.lang.ARef r options]
  (let [opts (apply hash-map options)]
    (when (:meta opts)
      (.resetMeta r (:meta opts)))
    (when (:validator opts)
      (.setValidator r (:validator opts)))
    r))

(defn java-ref
  "Creates and returns a JavaRef with an initial value of x and zero or
  more options (in any order):

  :meta metadata-map

  :validator validate-fn

  If metadata-map is supplied, it will become the metadata on the
  ref. validate-fn must be nil or a side-effect-free fn of one
  argument, which will be passed the intended new state on any state
  change. If the new state is unacceptable, the validate-fn should
  return false or throw an exception. validate-fn will be called on
  transaction commit, when all refs have their final values.

  Manipulation of min-history and max-history is not possible for java-ref, as
  history is disabled to prevent aliasing of references to mutable objects"
  ([x] (new clojure.lang.JavaRef x))
  ([x & options] (setup-reference (java-ref x) options)))


;;; Generic Event Handling
(defn listen
  "Registers a thread local event for the event identified by event-key"
  [event-key event-fn & event-args]
  (EventManager/listen event-key event-fn event-args true false))

(defn listen-with-params
  "Registers a event for the event identified by event-key, arguments can by
  given to configure if the listener should be thread local and if it is to be
  deleted after listener have been executed"
  [event-key thread-local delete-after-run event-fn & event-args]
  (EventManager/listen event-key event-fn event-args thread-local delete-after-run))

(defn notify
  "Notifies the events identified by the event-key keyword, and gives each
  event accesses to data given as context"
  ([event-key] (EventManager/notify event-key nil))
  ([event-key context] (EventManager/notify event-key context)))

(defn dismiss
  "Dismisses an event identified by the combination of event-key and event-fn, "
  [event-key event-fn dismiss-from]
  (EventManager/dismiss event-key event-fn dismiss-from))

(defn context
  "Returns the context for both types of events, returns nil if no context exists"
  []
  (EventManager/getContext))

;;; Transactional Control
(defn retry
  "Aborts a transaction and waits until any of the specified refs have changed"
  ([] (RT/stmBlocking nil nil nil false))
  ([refs] (RT/stmBlocking refs nil nil false))
  ([refs func & args] (RT/stmBlocking refs func args false)))

(defn retry-all
  "Aborts a transaction and waits until all of the specified refs have changed"
  ([] (RT/stmBlocking nil nil nil true))
  ([refs] (RT/stmBlocking refs nil nil true))
  ([refs func & args] (RT/stmBlocking refs func args true)))

(defn or-else
  "Execute the first expressions that do not result in a call to the retry functions in a list of expressions"
  [& body]
  (RT/stmOrElse false body))

(defn or-else-all
  "Execute the first expressions that do not result in that the transaction aborts
  or a call to directly to the retry or retry-all functions in a list of expressions"
  [& body]
  (RT/stmOrElse true body))

(defn terminate
  "Returns the context for both types of events, returns nil if no context exists"
  []
  (RT/stmAbort))
