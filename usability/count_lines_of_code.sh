#!/bin/bash
#   Copyright (c) Daniel Rune Jensen, Thomas Stig Jacobsen and
#   Søren Kejser Jensen. All rights reserved.
#   The use and distribution terms for this software are covered by the Eclipse
#   Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which
#   can be found in the file epl-v10.html at the root of this distribution. By
#   using this software in any fashion, you are agreeing to be bound by the
#   terms of this license.
#   You must not remove this notice, or any other, from this software.

# Only the lines actually containing code is counted to reduce the impact of
# using comments and in general that programming style have an effect.
printf "with_extensions.clj (LOC):"
cat with_extensions.clj | sed '/^$/d;/^;/d' | wc -l

printf "without_extensions.clj (LOC):"
cat without_extensions.clj | sed '/^$/d;/^;/d' | wc -l
