eClojure
========
This repository contains the software complementing the paper **[Extending Software Transactional Memory in Clojure with Side-Effects and Transaction Control](http://dl.acm.org/citation.cfm?id=3005729.3005737)** presented at the **[9th European Lisp Symposium](http://www.european-lisp-symposium.org/editions/2016/)**.

The main contents is our extended version of Clojure 1.8, named eClojure, that extends the Software Transactional Memory (STM) of Clojure 1.8 in two directions. First support for synchronising side-effects using transactions is made possible through three events emitted: *after-commit*, *on-abort*, and *on-commit*. Second an implementation and extension of multiple transaction control methods pioneered by Haskell, *retry* and *orElse*, is provided.

For an in-depth description of these two sets of extensions see the above mentioned paper.

Structure
---------
- **eclojure_changes.clj:** A small script that can manipulate files changed in eClojure 1.8.
- **eclojure-1.8.0:** The source code for eClojure 1.8 in addition to a suite of unit tests.
- **overhead:** The source code used to benchmark the overhead of our additions as documented in the paper.
- **usability:** The two implementations of the Santa Claus problem developed as part of the usability evaluation documented in the paper.
- **presentation.pdf:** The slides used for the presentation at the **9th European Lisp Symposium**.

License
-------
Clojure 1.8 is made available under the Eclipse Public License (EPL) version 1.0 from *https://github.com/clojure/clojure*. We have elected to use the same license for both our extensions to Clojure 1.8 and the scripts in this repository. A copy of the license is placed in the root of this repository in addition to being available from *https://opensource.org/licenses/eclipse-1.0.php*.
