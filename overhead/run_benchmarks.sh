#!/bin/bash
#   Copyright (c) Daniel Rune Jensen, Thomas Stig Jacobsen and
#   Søren Kejser Jensen. All rights reserved.
#   The use and distribution terms for this software are covered by the Eclipse
#   Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which
#   can be found in the file epl-v10.html at the root of this distribution. By
#   using this software in any fashion, you are agreeing to be bound by the
#   terms of this license.
#   You must not remove this notice, or any other, from this software.

# Sets the path of two Clojure jars and the Criterium jar
clojure="libraries/clojure-1.8.0.jar"
eclojure="libraries/eclojure-1.8.0.jar"
criterium="libraries/criterium-0.4.3.jar"

# Verifies that the necessary jar files are available
if [[ ! -f "$clojure" || ! -f "$eclojure" || ! -f $criterium ]]
then
    echo "ERROR: please ensure the necessary jars are available"
    exit -1
fi

# Function for running the benchmarks with a specific version of Clojure
function run_benchmarks {
    # $1: The path to a jar containing the Clojure runtime
    java -cp "$1:$criterium":. clojure.main "benchmark_overhead.clj"
}

# Stores time stamp for grouping the two experiments in the folder results
timestamp=$(date -u +"%Y-%m-%dT%H-%M-%SZ")
mkdir -p "results"

# Executes the benchmarks using Clojure 1.8.0
echo "Running Clojure Benchmarks: $clojure"
run_benchmarks "$clojure" > "results/$timestamp-clojure-1.8.0.txt"

# Executes the benchmarks using eClojure 1.8.0
echo "Running eClojure Benchmarks: $eclojure"
run_benchmarks "$eclojure" > "results/$timestamp-eclojure-1.8.0.txt"
