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
import java.util.concurrent.CountDownLatch;

/**
 * Abstract class for blocking behaviors in relation with retry functionality
 */
abstract class STMBlockingBehavior {
    protected Set<Ref> refSet;
    protected CountDownLatch cdl;
    protected long blockPoint;

    /**
     *  Default constructor
     *
     * @param refSet   A list of ref to block on
     */
    STMBlockingBehavior(Set<Ref> refSet, long blockPoint) {
        this.refSet = refSet;
        this.blockPoint = blockPoint;
        this.cdl = new CountDownLatch(1);
    }

    /**
     * Awaits the count down of this blocking behaviors CountDownLatch
     */
    void await() throws InterruptedException {
        if ( ! shouldUnblock()) {
            this.cdl.await();
        }
    }

    /**
     * Unblock the blocking behavior based on handleChanged
     */
    void handleChanged() {
        if (shouldUnblock()) {
            this.cdl.countDown();
        }
    }

    /**
     * Return a boolean if the blocking behavior should unblock
     */
    abstract protected boolean shouldUnblock();
}
