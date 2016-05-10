// eClojure
/**
 * Copyright (c) Daniel Rune Jensen, Thomas Stig Jacobsen and
 * Søren Kejser Jensen. All rights reserved.
 * The use and distribution terms for this software are covered by the Eclipse
 * Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which
 * can be found in the file epl-v10.html at the root of this distribution. By
 * using this software in any fashion, you are agreeing to be bound by the
 * terms of this license.
 * You must not remove this notice, or any other, from this software.
 */

package clojure.lang;

import java.util.Set;

/**
 * Class for handling the blocking behavior used in retry
 */
class STMBlockingBehaviorAny extends STMBlockingBehavior {

    STMBlockingBehaviorAny(Set<Ref> refSet, long blockPoint) {
        super(refSet, blockPoint);
    }

    /**
     * Return a boolean if the blocking behavior should unblock
     */
    protected boolean shouldUnblock() {
        for (Ref ref : this.refSet) {
            if (ref.tvals.point > this.blockPoint) {
                return true;
            }
        }
        return false;
    }
}
