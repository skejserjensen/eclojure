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

/**
 * EventFn class for storing functions for later execution
 */
public class EventFn {
    final private IFn fn;
    final private ISeq args;
    final private boolean deleteAfterRun;

    /**
     * Constructor allowing to delete function when it has been executed once
     *
     * @param fn             The function to be executed on run
     * @param args           The arguments to the function given as fn
     * @param deleteAfterRun Whether or not to delete the function when it has been executed once
     */
    EventFn(IFn fn, ISeq args, boolean deleteAfterRun) {
        this.fn = fn;
        this.args = args;
        this.deleteAfterRun = deleteAfterRun;
    }

    /**
     * Indicates if the thunk should be deleted after being executed
     *
     * @return Returns the return value of the function executed
     */
    boolean deleteAfterRun() {
        return this.deleteAfterRun;
    }

    /**
     * Execute the thunk and returns the value computed
     *
     * @return Returns the return value of the function executed
     */
    Object run() {
        return fn.applyTo(this.args);
    }
}
