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
 * Ref class optimised for holding Java references to mutable objects
 */
public class JavaRef extends Ref {
    public JavaRef(Object initVal) {
        this(initVal, null);
    }

    public JavaRef(Object initVal, IPersistentMap meta) {
        super(initVal, meta);
        this.maxHistory = 1;
    }

    public Object deref() {
        LockingTransaction t = LockingTransaction.getRunning();
        if(t == null)
            return currentVal();

        //To discourage aliasing is non-destructive reads not allowed inside
        //transaction, as the value otherwise would be trivial to duplicate
        //between multiple references.
        Object tValue = t.doGet(this);
        LockingTransaction.getEx().doSet(this, null);
        return tValue;
    }

    public Ref setMinHistory(int minHistory) {
        throw new java.lang.UnsupportedOperationException("java-ref does not support changes to min history");
    }

    public Ref setMaxHistory(int maxHistory) {
        throw new java.lang.UnsupportedOperationException("java-ref does not support changes to max history");
    }
}
